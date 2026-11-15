package li.cil.oc.server.component

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util
import java.util.HexFormat
import java.util.concurrent.ThreadLocalRandom

import li.cil.oc.Settings
import li.cil.oc.api.Network
import li.cil.oc.api.driver.DeviceInfo
import li.cil.oc.api.driver.DeviceInfo.DeviceAttribute
import li.cil.oc.api.driver.DeviceInfo.DeviceClass
import li.cil.oc.api.machine.Arguments
import li.cil.oc.api.machine.Callback
import li.cil.oc.api.machine.Context
import li.cil.oc.api.network.Visibility
import li.cil.oc.api.prefab
import li.cil.oc.common.Tier
import net.neoforged.neoforge.server.ServerLifecycleHooks

import scala.collection.convert.ImplicitConversionsToJava._

/**
 * Mining card: hashes ForgeCoin block candidates in Java under a strict
 * per-card and server-wide hash budget, paid for with energy per hash.
 *
 * Hash format (must match blockchain_lib.calculateHash):
 *   sha256(prefix .. tostring(nonce)), prefix ends with "|", nonce in decimal.
 * Difficulty is the number of leading zero bits of the raw digest.
 */
abstract class MiningCard(val tier: Int) extends prefab.AbstractManagedEnvironment with DeviceInfo {
  override val node = Network.newNode(this, Visibility.Neighbors).
    withComponent("mining", Visibility.Neighbors).
    withConnector().
    create()

  private final val Scale = 1000L

  // Make sure the signing key gets loaded before the first hit.
  MiningSecret.request()

  private def hashesPerSecond: Int = Settings.get.miningCardHashesPerSecond(tier)

  private def maxPerCall: Int = Settings.get.miningCardMaxPerCall(tier)

  private def energyPerHash: Double = Settings.get.miningCardEnergyPerHash(tier)

  // Token bucket in milli-hashes, refilled lazily from the server tick counter.
  private var credit = 0L
  private var lastTick = -1L

  private def refill(): Unit = {
    val now = MiningBudget.currentTick
    val perTick = hashesPerSecond.toLong * Scale / 20L
    val cap = hashesPerSecond.toLong * Scale
    if (lastTick < 0) lastTick = now
    if (now > lastTick) {
      val gained = math.min(now - lastTick, 20L) * perTick
      credit = math.min(cap, credit + gained)
      lastTick = now
    }
  }

  private def availableHashes: Int = this.synchronized {
    refill()
    (credit / Scale).toInt
  }

  private def takeCredit(wanted: Int): Int = this.synchronized {
    refill()
    val granted = math.min(wanted.toLong, credit / Scale).toInt
    credit -= granted * Scale
    granted
  }

  private def returnCredit(count: Int): Unit = this.synchronized {
    if (count > 0) credit = math.min(hashesPerSecond.toLong * Scale, credit + count * Scale)
  }

  /** Synchronized calls run on the server thread; never hash there. */
  private def onServerThread: Boolean = {
    val server = ServerLifecycleHooks.getCurrentServer
    server != null && server.isSameThread
  }

  private def checkWholeNumber(args: Arguments, index: Int, name: String): Long = {
    args.checkAny(index) match {
      case value: java.lang.Double =>
        val d = value.doubleValue
        if (!java.lang.Double.isFinite(d) || d != math.floor(d)) throw new IllegalArgumentException(s"$name must be an integer")
        d.toLong
      case value: java.lang.Float =>
        val f = value.doubleValue
        if (!java.lang.Double.isFinite(f) || f != math.floor(f)) throw new IllegalArgumentException(s"$name must be an integer")
        f.toLong
      case _ => args.checkLong(index)
    }
  }

  /**
   * Reserves up to `wanted` hashes against card credit, global budget and energy.
   * Returns the number of hashes reserved (and paid for).
   */
  private def reserve(wanted: Int): Int = {
    val cost = energyPerHash
    val affordable =
      if (Settings.get.ignorePower || cost <= 0) wanted
      else math.min(wanted.toDouble, math.floor(node.globalBuffer / cost)).toInt
    if (affordable <= 0) throw new Exception("not enough energy")

    val fromCard = takeCredit(affordable)
    if (fromCard <= 0) return 0
    val granted = MiningBudget.reserve(fromCard)
    if (granted < fromCard) returnCredit(fromCard - granted)
    if (granted <= 0) return 0

    if (cost > 0 && !node.tryChangeBuffer(-granted * cost)) {
      MiningBudget.release(granted)
      returnCredit(granted)
      throw new Exception("not enough energy")
    }
    granted
  }

  /** Gives back hashes that were reserved but not used. */
  private def refund(unused: Int): Unit = {
    if (unused <= 0) return
    MiningBudget.release(unused)
    returnCredit(unused)
    val cost = energyPerHash
    if (cost > 0) node.changeBuffer(unused * cost)
  }

  // ----------------------------------------------------------------------- //

  @Callback(direct = true, limit = 8, doc = """function(prefix:string, count:number, difficulty:number):number|nil, string|nil, number, string|nil -- Hashes prefix..nonce for up to count nonces starting at a random nonce chosen by the card. Returns the first nonce whose hash has at least difficulty leading zero bits, its hash as hex, the number of hashes done and a signature of the hit (hex) that verify() accepts. Nonce, hash and signature are nil when there is no hit. hashesDone == 0 means the budget is used up (or the card is not ready yet); sleep and retry.""")
  def mine(context: Context, args: Arguments): Array[AnyRef] = {
    val prefix = args.checkByteArray(0)
    val count = checkWholeNumber(args, 1, "count")
    val difficulty = checkWholeNumber(args, 2, "difficulty")
    if (prefix.length > MiningCard.MaxPrefixLength) throw new IllegalArgumentException("prefix too long")
    if (count < 1) throw new IllegalArgumentException("count must be at least 1")
    if (difficulty < 0 || difficulty > 256) throw new IllegalArgumentException("difficulty must be between 0 and 256")

    if (onServerThread) return result(null, null, Int.box(0))

    // Without the key a hit could not be signed; treat it like an empty budget.
    val secret = MiningSecret.current
    if (secret == null) return result(null, null, Int.box(0))

    val wanted = math.min(count, maxPerCall.toLong).toInt
    val granted = reserve(wanted)
    if (granted <= 0) return result(null, null, Int.box(0))

    val start = ThreadLocalRandom.current().nextLong(0L, MiningCard.NonceRange)
    val base = MiningCard.sha256()
    base.update(prefix)

    var i = 0
    while (i < granted) {
      val nonce = start + i
      val digest = base.clone().asInstanceOf[MessageDigest]
      digest.update(MiningCard.nonceBytes(nonce))
      val hash = digest.digest()
      if (MiningCard.hasLeadingZeroBits(hash, difficulty.toInt)) {
        refund(granted - (i + 1))
        val hashHex = MiningCard.Hex.formatHex(hash)
        val sig = MiningCard.Hex.formatHex(MiningCard.sign(secret, prefix, nonce, hashHex))
        return result(Long.box(nonce), hashHex, Int.box(i + 1), sig)
      }
      i += 1
    }
    result(null, null, Int.box(granted))
  }

  @Callback(direct = true, limit = 8, doc = """function(data:string):string|nil -- Single SHA-256 of data as lowercase hex. Costs one hash of budget and energy. Returns nil when the budget is used up. Never returns a signature.""")
  def hash(context: Context, args: Arguments): Array[AnyRef] = {
    val data = args.checkByteArray(0)
    if (data.length > MiningCard.MaxPrefixLength + 20) throw new IllegalArgumentException("data too long")
    if (onServerThread) return result(null)
    if (reserve(1) <= 0) return result(null)
    result(MiningCard.Hex.formatHex(MiningCard.sha256().digest(data)))
  }

  @Callback(direct = true, doc = """function(prefix:string, nonce:number, sig:string):boolean -- Checks a signature returned by mine() on any mining card in this world. Recomputes the hash of prefix..nonce itself. Costs no budget and no energy.""")
  def verify(context: Context, args: Arguments): Array[AnyRef] = {
    val prefix = args.checkByteArray(0)
    val nonce = checkWholeNumber(args, 1, "nonce")
    val sigHex = args.checkString(2)
    if (prefix.length > MiningCard.MaxPrefixLength) throw new IllegalArgumentException("prefix too long")

    val secret = MiningSecret.current
    if (secret == null) return result(java.lang.Boolean.FALSE)

    // Expected signatures are lowercase hex of a 32 byte HMAC; anything else cannot match.
    val sig =
      try MiningCard.Hex.parseHex(sigHex.toLowerCase(java.util.Locale.ROOT))
      catch { case _: IllegalArgumentException => return result(java.lang.Boolean.FALSE) }
    if (sig.length != MiningCard.SignatureLength) return result(java.lang.Boolean.FALSE)

    val digest = MiningCard.sha256()
    digest.update(prefix)
    val hashHex = MiningCard.Hex.formatHex(digest.digest(MiningCard.nonceBytes(nonce)))
    val expected = MiningCard.sign(secret, prefix, nonce, hashHex)
    // MessageDigest.isEqual compares in constant time.
    result(Boolean.box(MessageDigest.isEqual(expected, sig)))
  }

  @Callback(direct = true, doc = """function():number, number -- Hashes available right now from this card's budget, and the card's hashes per second.""")
  def getBudget(context: Context, args: Arguments): Array[AnyRef] = {
    result(Int.box(availableHashes), Int.box(hashesPerSecond))
  }

  @Callback(direct = true, doc = """function():table -- Card tier and limits.""")
  def getInfo(context: Context, args: Arguments): Array[AnyRef] = {
    val info = new util.HashMap[String, AnyRef]()
    info.put("tier", Int.box(tier + 1))
    info.put("hashesPerSecond", Int.box(hashesPerSecond))
    info.put("maxPerCall", Int.box(maxPerCall))
    info.put("energyPerHashFE", Double.box(Settings.get.miningCardEnergyPerHashFE(tier)))
    info.put("energyPerHash", Double.box(energyPerHash))
    result(info)
  }

  // ----------------------------------------------------------------------- //

  private final lazy val deviceInfo = Map(
    DeviceAttribute.Class -> DeviceClass.Processor,
    DeviceAttribute.Description -> "SHA-256 mining card",
    DeviceAttribute.Vendor -> "Imperial Forge",
    DeviceAttribute.Product -> s"FRG Miner T${tier + 1}",
    DeviceAttribute.Capacity -> s"${hashesPerSecond} H/s"
  )

  override def getDeviceInfo: util.Map[String, String] = deviceInfo
}

object MiningCard {
  /** Largest valid ForgeCoin prefix is about 420 bytes. */
  final val MaxPrefixLength = 1024

  /** Random start nonces are drawn from [0, 2^62), leaving headroom below 2^63-1. */
  final val NonceRange: Long = 1L << 62

  final val Hex: HexFormat = HexFormat.of()

  private val Digest = new ThreadLocal[MessageDigest]() {
    override def initialValue(): MessageDigest = MessageDigest.getInstance("SHA-256")
  }

  def sha256(): MessageDigest = {
    val digest = Digest.get()
    digest.reset()
    digest
  }

  final val SignatureLength = 32

  private val Hmac = new ThreadLocal[Mac]() {
    override def initialValue(): Mac = Mac.getInstance("HmacSHA256")
  }

  /** Decimal nonce as Lua's tostring() writes an integer. */
  def nonceBytes(nonce: Long): Array[Byte] =
    java.lang.Long.toString(nonce).getBytes(StandardCharsets.US_ASCII)

  /** HMAC-SHA256(secret, prefix .. "|" .. nonce .. "|" .. hashHex). */
  def sign(secret: Array[Byte], prefix: Array[Byte], nonce: Long, hashHex: String): Array[Byte] = {
    val mac = Hmac.get()
    mac.init(new SecretKeySpec(secret, "HmacSHA256"))
    mac.update(prefix)
    mac.update('|'.toByte)
    mac.update(nonceBytes(nonce))
    mac.update('|'.toByte)
    mac.doFinal(hashHex.getBytes(StandardCharsets.US_ASCII))
  }

  def hasLeadingZeroBits(hash: Array[Byte], bits: Int): Boolean = {
    val fullBytes = bits / 8
    var i = 0
    while (i < fullBytes) {
      if (hash(i) != 0) return false
      i += 1
    }
    val rest = bits % 8
    rest == 0 || ((hash(fullBytes) & 0xFF) >>> (8 - rest)) == 0
  }

  class Tier1 extends MiningCard(Tier.One)

  class Tier2 extends MiningCard(Tier.Two)

  class Tier3 extends MiningCard(Tier.Three)
}
