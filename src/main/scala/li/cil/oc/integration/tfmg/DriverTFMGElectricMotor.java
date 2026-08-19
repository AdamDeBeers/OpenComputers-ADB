package li.cil.oc.integration.tfmg;

import com.drmangotea.tfmg.content.electricity.utilities.electric_motor.ElectricMotorBlockEntity;
import com.drmangotea.tfmg.registry.TFMGBlocks;
import li.cil.oc.api.driver.EnvironmentProvider;
import li.cil.oc.api.driver.NamedBlock;
import li.cil.oc.api.machine.Arguments;
import li.cil.oc.api.machine.Callback;
import li.cil.oc.api.machine.Context;
import li.cil.oc.api.network.ManagedEnvironment;
import li.cil.oc.api.prefab.DriverSidedBlockEntity;
import li.cil.oc.integration.ManagedBlockEntityEnvironment;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

// Read-only, unlike Create: Crafts & Additions' electric motor: TFMG's motor has no FE buffer
// and no settable RPM at all. It sits directly on the TFMG electrical network as a fixed
// resistive load (resistance() is a config constant) and its generated kinetic speed is
// purely a function of that network's voltage (getGeneratedSpeed(): min(255, voltage * 0.8),
// zeroed out while the network is undersupplied). The only other input, rotation direction,
// is a player-only scroll interaction (ScrollOptionBehaviour<RotationDirection>) with no
// public getter or setter on the block entity - but its sign is already folded into
// getGeneratedSpeed()'s return value, so nothing is lost by not exposing it separately.
public final class DriverTFMGElectricMotor extends DriverSidedBlockEntity {
    public static final DriverTFMGElectricMotor INSTANCE = new DriverTFMGElectricMotor();

    private DriverTFMGElectricMotor() {
    }

    @Override
    public Class<?> getBlockEntityClass() {
        return ElectricMotorBlockEntity.class;
    }

    @Override
    public ManagedEnvironment createEnvironment(final Level world, final BlockPos pos, final Direction side) {
        return new Environment((ElectricMotorBlockEntity) world.getBlockEntity(pos));
    }

    public static final class Environment extends ManagedBlockEntityEnvironment<ElectricMotorBlockEntity> implements NamedBlock {
        public Environment(final ElectricMotorBlockEntity tile) {
            super(tile, "tfmg_electric_motor");
        }

        @Override
        public String preferredName() {
            return "tfmg_electric_motor";
        }

        @Override
        public int priority() {
            return 0;
        }

        @Callback(doc = "function():number -- Get the electrical network's voltage at this motor.")
        public Object[] getNetworkVoltage(final Context context, final Arguments args) {
            return new Object[]{blockEntity.getData().getVoltage()};
        }

        @Callback(doc = "function():number -- Get the current drawn by this motor.")
        public Object[] getCurrent(final Context context, final Arguments args) {
            return new Object[]{blockEntity.getCurrent()};
        }

        @Callback(doc = "function():number -- Get this motor's rated maximum current. 0 means no cap is enforced.")
        public Object[] getMaxCurrent(final Context context, final Arguments args) {
            return new Object[]{blockEntity.getMaxCurrent()};
        }

        @Callback(doc = "function():number -- Get this motor's rated maximum voltage, above which it starts taking overvoltage damage.")
        public Object[] getMaxVoltage(final Context context, final Arguments args) {
            return new Object[]{blockEntity.getMaxVoltage()};
        }

        @Callback(doc = "function():number -- Get this motor's power draw (voltage * current).")
        public Object[] getPowerUsage(final Context context, final Arguments args) {
            return new Object[]{blockEntity.getPowerUsage()};
        }

        @Callback(doc = "function():number -- Get this motor's fixed internal resistance, as seen by the electrical network.")
        public Object[] getResistance(final Context context, final Arguments args) {
            return new Object[]{blockEntity.resistance()};
        }

        @Callback(doc = "function():boolean -- Whether this motor's network currently supplies enough power for it to work.")
        public Object[] canWork(final Context context, final Arguments args) {
            return new Object[]{blockEntity.canWork()};
        }

        @Callback(doc = "function():boolean -- Whether this motor's network is undersupplied (demand exceeds generation).")
        public Object[] isNetworkUndersupplied(final Context context, final Arguments args) {
            return new Object[]{blockEntity.networkUndersupplied()};
        }

        @Callback(doc = "function():number -- Get the speed this motor generates from the network's voltage, in RPM, signed for its configured rotation direction. 0 while the network is undersupplied, regardless of voltage.")
        public Object[] getGeneratedSpeed(final Context context, final Arguments args) {
            return new Object[]{blockEntity.getGeneratedSpeed()};
        }

        @Callback(doc = "function():number -- Get the speed actually being applied to the kinetic network right now, in RPM. 0 if the kinetic network is overstressed (see isOverStressed) or ticking is frozen, even if getGeneratedSpeed() is non-zero.")
        public Object[] getSpeed(final Context context, final Arguments args) {
            return new Object[]{blockEntity.getSpeed()};
        }

        @Callback(doc = "function():boolean -- Whether the kinetic network this motor drives is currently overstressed.")
        public Object[] isOverStressed(final Context context, final Arguments args) {
            return new Object[]{blockEntity.isOverStressed()};
        }

        @Callback(doc = "function():number -- Get the kinetic stress capacity this motor adds to the network at its current speed.")
        public Object[] getAddedStressCapacity(final Context context, final Arguments args) {
            return new Object[]{blockEntity.calculateAddedStressCapacity()};
        }
    }

    public static final class Provider implements EnvironmentProvider {
        public static final Provider INSTANCE = new Provider();

        private Provider() {
        }

        @Override
        public Class<?> getEnvironment(final ItemStack stack) {
            return stack.getItem() == TFMGBlocks.ELECTRIC_MOTOR.get().asItem() ? Environment.class : null;
        }
    }
}
