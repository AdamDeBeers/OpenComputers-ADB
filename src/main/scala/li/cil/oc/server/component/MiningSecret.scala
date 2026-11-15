package li.cil.oc.server.component

import java.security.SecureRandom

import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.saveddata.SavedData

/**
 * World-wide secret key used by mining cards to sign their results.
 *
 * The key is stored in the overworld's saved data and is never exposed to Lua.
 * Saved data must only be touched on the server thread, so cards only request
 * the key; it is loaded (or generated on first use) during the next server tick
 * and then handed to the worker threads through a volatile field.
 */
object MiningSecret {
  final val KeyLength = 32

  private final val DataName = "oc_mining_secret"

  private final val Factory = new SavedData.Factory[Data](
    () => Data.generate(),
    (tag: CompoundTag, _: HolderLookup.Provider) => Data.load(tag),
    null)

  @volatile private var requested = false

  // Server the cached key belongs to, so switching worlds never reuses a key.
  @volatile private var owner: MinecraftServer = _

  @volatile private var key: Array[Byte] = _

  /** Asks for the key to be loaded on the next server tick. */
  def request(): Unit = requested = true

  /** Current key, or null while it is not loaded yet. Callers must not modify it. */
  def current: Array[Byte] = key

  /** Called once per server tick on the server thread. */
  def onServerTick(server: MinecraftServer): Unit = {
    if (owner ne server) {
      key = null
      owner = server
    }
    if (requested && key == null) {
      val overworld = server.overworld()
      if (overworld != null) {
        key = overworld.getDataStorage.computeIfAbsent(Factory, DataName).key
      }
    }
  }

  final class Data(val key: Array[Byte]) extends SavedData {
    override def save(tag: CompoundTag, registries: HolderLookup.Provider): CompoundTag = {
      tag.putByteArray("key", key)
      tag
    }
  }

  object Data {
    private val random = new SecureRandom()

    def generate(): Data = {
      val bytes = new Array[Byte](KeyLength)
      random.nextBytes(bytes)
      val data = new Data(bytes)
      data.setDirty()
      data
    }

    def load(tag: CompoundTag): Data = {
      val bytes = tag.getByteArray("key")
      // A missing or broken key is replaced; old signatures become invalid.
      if (bytes.length == KeyLength) new Data(bytes) else generate()
    }
  }
}
