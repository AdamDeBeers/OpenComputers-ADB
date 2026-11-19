package li.cil.oc.common.blockentity

import li.cil.oc.Settings
import li.cil.oc.api
import li.cil.oc.api.machine.Arguments
import li.cil.oc.api.machine.Callback
import li.cil.oc.api.machine.Context
import li.cil.oc.api.network.Visibility
import li.cil.oc.common.block.{AdbLight => AdbLightBlock}
import net.minecraft.core.{BlockPos, HolderLookup}
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.neoforged.neoforge.common.extensions.IBlockEntityExtension

class AdbLight(pos: BlockPos, state: BlockState)
  extends BlockEntity(BlockEntityTypes.ADB_LIGHT.get(), pos, state) with traits.Environment with traits.Tickable with IBlockEntityExtension {
  val node = api.Network.newNode(this, Visibility.Network).
    withComponent("adb_light").
    create()

  private var color = AdbLightBlock.DefaultColor

  private var brightness = AdbLightBlock.DefaultBrightness

  private var blinking = false

  def getColor: Int = color

  def getBrightness: Int = brightness

  def isBlinking: Boolean = blinking

  /**
   * Blink phase derived from the world game time, so all blinking lights in
   * the same world switch on and off together.
   */
  private def blinkPhaseOn: Boolean =
    getLevel == null || (getLevel.getGameTime / AdbLightBlock.BlinkPhaseTicks) % 2 == 0

  /** The light level the block should currently emit. */
  def effectiveLight: Int = if (blinking && !blinkPhaseOn) 0 else brightness

  // ----------------------------------------------------------------------- //

  @Callback(doc = """function(rgb:number) -- Set the light color as 0xRRGGBB.""")
  def setColor(context: Context, args: Arguments): Array[Object] = {
    val value = args.checkDouble(0)
    if (value.isNaN || value != math.floor(value) || value < 0 || value > 0xFFFFFF)
      throw new IllegalArgumentException("color must be an integer between 0x000000 and 0xFFFFFF")
    color = value.toInt
    onStateChanged()
    null
  }

  @Callback(direct = true, doc = """function():number -- Get the light color as 0xRRGGBB.""")
  def getColor(context: Context, args: Arguments): Array[Object] = result(color)

  @Callback(doc = """function(level:number) -- Set the brightness from 0 (off) to 15.""")
  def setBrightness(context: Context, args: Arguments): Array[Object] = {
    val value = args.checkDouble(0)
    if (value.isNaN || value != math.floor(value) || value < 0 || value > 15)
      throw new IllegalArgumentException("brightness must be an integer between 0 and 15")
    brightness = value.toInt
    onStateChanged()
    null
  }

  @Callback(direct = true, doc = """function():number -- Get the brightness (0-15).""")
  def getBrightness(context: Context, args: Arguments): Array[Object] = result(brightness)

  @Callback(doc = """function(enabled:boolean) -- Enable or disable blinking (about once per second).""")
  def setBlink(context: Context, args: Arguments): Array[Object] = {
    val value = args.checkBoolean(0)
    if (value != blinking) {
      blinking = value
      onStateChanged()
    }
    null
  }

  @Callback(direct = true, doc = """function():boolean -- Whether the light is currently blinking.""")
  def isBlinking(context: Context, args: Arguments): Array[Object] = result(blinking)

  // ----------------------------------------------------------------------- //

  override def updateEntity(): Unit = {
    super.updateEntity()
    if (isServer) {
      // Follows the shared blink phase; cheap when nothing changed, and also
      // fixes up the block state after loading.
      updateLightState()
    }
  }

  private def onStateChanged(): Unit = {
    setChanged()
    if (getLevel != null && isServer) {
      updateLightState()
      // Pushes the block entity update packet so clients get the new color.
      getLevel.sendBlockUpdated(getBlockPos, getBlockState, getBlockState, Block.UPDATE_CLIENTS)
    }
  }

  private def updateLightState(): Unit = {
    val current = getBlockState
    val target = effectiveLight
    if (current.hasProperty(AdbLightBlock.Light) && current.getValue(AdbLightBlock.Light).intValue != target) {
      getLevel.setBlock(getBlockPos, current.setValue(AdbLightBlock.Light, Integer.valueOf(target)), Block.UPDATE_ALL)
    }
  }

  // ----------------------------------------------------------------------- //

  private final val ColorTag = Settings.namespace + "adbLightColor"
  private final val BrightnessTag = Settings.namespace + "adbLightBrightness"
  private final val BlinkTag = Settings.namespace + "adbLightBlink"

  private def readState(nbt: CompoundTag): Unit = {
    if (nbt.contains(ColorTag)) color = nbt.getInt(ColorTag) & 0xFFFFFF
    if (nbt.contains(BrightnessTag)) brightness = math.max(0, math.min(15, nbt.getInt(BrightnessTag)))
    if (nbt.contains(BlinkTag)) blinking = nbt.getBoolean(BlinkTag)
  }

  private def writeState(nbt: CompoundTag): Unit = {
    nbt.putInt(ColorTag, color)
    nbt.putInt(BrightnessTag, brightness)
    nbt.putBoolean(BlinkTag, blinking)
  }

  override def loadForServer(nbt: CompoundTag, provider: HolderLookup.Provider): Unit = {
    super.loadForServer(nbt, provider)
    readState(nbt)
  }

  override def saveForServer(nbt: CompoundTag, provider: HolderLookup.Provider): Unit = {
    super.saveForServer(nbt, provider)
    writeState(nbt)
  }

  override def loadForClient(nbt: CompoundTag, provider: HolderLookup.Provider): Unit = {
    super.loadForClient(nbt, provider)
    readState(nbt)
    // Re-render the block so the color handler picks up the new tint.
    if (getLevel != null) {
      getLevel.sendBlockUpdated(getBlockPos, getBlockState, getBlockState, Block.UPDATE_IMMEDIATE)
    }
  }

  override def saveForClient(nbt: CompoundTag, provider: HolderLookup.Provider): Unit = {
    super.saveForClient(nbt, provider)
    writeState(nbt)
  }
}
