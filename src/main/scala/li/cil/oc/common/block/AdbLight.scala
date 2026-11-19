package li.cil.oc.common.block

import li.cil.oc.common.blockentity
import li.cil.oc.common.blockentity.BlockEntityTypes
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.{BlockEntity, BlockEntityType}
import net.minecraft.world.level.block.state.BlockBehaviour.Properties
import net.minecraft.world.level.block.state.properties.IntegerProperty
import net.minecraft.world.level.block.state.{BlockState, StateDefinition}

/**
 * A full-cube lamp that is controlled as a network component. The vanilla
 * light level is stored in the block state so lighting follows it directly;
 * color, brightness and blink settings live in the block entity.
 */
class AdbLight(props: Properties) extends SimpleBlock(props.lightLevel((state: BlockState) => state.getValue(AdbLight.Light).intValue())) with traits.Tickable {
  registerDefaultState(stateDefinition.any().setValue(AdbLight.Light, Integer.valueOf(AdbLight.DefaultBrightness)))

  protected override def createBlockStateDefinition(builder: StateDefinition.Builder[Block, BlockState]): Unit =
    builder.add(AdbLight.Light)

  override def newBlockEntity(pos: BlockPos, state: BlockState) = new blockentity.AdbLight(pos, state)

  override def getBlockEntityType: BlockEntityType[_ <: BlockEntity] = BlockEntityTypes.ADB_LIGHT.get()
}

object AdbLight {
  /** Current effective light level (0 = off), mirrored from the block entity. */
  final val Light: IntegerProperty = IntegerProperty.create("light", 0, 15)

  final val DefaultColor = 0xFFFFFF

  final val DefaultBrightness = 15

  /** Ticks per blink phase: 20 ticks on, 20 ticks off. */
  final val BlinkPhaseTicks = 20

  /**
   * Scales an RGB color by the effective light level so the rendered texture
   * gets darker as the brightness goes down, and nearly black when off.
   */
  def shade(rgb: Int, level: Int): Int = {
    val factor = if (level <= 0) 0.12 else 0.35 + 0.65 * (level min 15) / 15.0
    val r = (((rgb >> 16) & 0xFF) * factor).toInt
    val g = (((rgb >> 8) & 0xFF) * factor).toInt
    val b = ((rgb & 0xFF) * factor).toInt
    (r << 16) | (g << 8) | b
  }
}
