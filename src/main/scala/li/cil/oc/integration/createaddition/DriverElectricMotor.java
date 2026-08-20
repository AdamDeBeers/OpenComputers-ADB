package li.cil.oc.integration.createaddition;

import com.mrh0.createaddition.blocks.electric_motor.ElectricMotorBlockEntity;
import com.mrh0.createaddition.index.CABlocks;
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

// Kinetic/electrical control surface, mirroring createaddition's own CC:Tweaked peripheral
// (ElectricMotorPeripheral in the createaddition jar, type id "electric_motor"). Raw FE
// storage introspection (getEnergyStored/getMaxEnergyStored/canExtract/canReceive) is
// deliberately not duplicated here: ElectricMotorBlockEntity registers the standard NeoForge
// EnergyStorage capability, which OC's own DriverEnergyStorage already exposes generically
// as a separate "energy_device" component on this same block - adding it again here would
// just shadow that.
public final class DriverElectricMotor extends DriverSidedBlockEntity {
    public static final DriverElectricMotor INSTANCE = new DriverElectricMotor();

    private DriverElectricMotor() {
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
            super(tile, "cca_electric_motor");
        }

        @Override
        public String preferredName() {
            return "cca_electric_motor";
        }

        @Override
        public int priority() {
            return 0;
        }

        @Callback(doc = "function():number -- Get the motor's set speed, in RPM. This is the configured target speed, regardless of whether the motor currently has enough power to actually spin (see getGeneratedSpeed).")
        public Object[] getRPM(final Context context, final Arguments args) {
            return new Object[]{blockEntity.getRPM()};
        }

        @Callback(doc = "function(rpm:number):boolean -- Set the motor's target speed, in RPM.")
        public Object[] setRPM(final Context context, final Arguments args) {
            return new Object[]{blockEntity.setRPM((float) args.checkDouble(0))};
        }

        @Callback(doc = "function():boolean -- Stop the motor (equivalent to setRPM(0)).")
        public Object[] stop(final Context context, final Arguments args) {
            return new Object[]{blockEntity.setRPM(0.0F)};
        }

        @Callback(doc = "function():number -- Get the speed actually being applied to the kinetic network right now, in RPM, signed for the block's facing. 0 if the motor is stalled (not enough stored energy) or redstone-locked, even if a non-zero speed is set.")
        public Object[] getGeneratedSpeed(final Context context, final Arguments args) {
            return new Object[]{blockEntity.getGeneratedSpeed()};
        }

        @Callback(doc = "function():number -- Get the kinetic stress capacity this motor adds to the network while active.")
        public Object[] getStressCapacity(final Context context, final Arguments args) {
            return new Object[]{blockEntity.getGeneratedStress()};
        }

        @Callback(doc = "function():number -- Get the current energy consumption at the set speed, in FE/t.")
        public Object[] getEnergyConsumption(final Context context, final Arguments args) {
            return new Object[]{blockEntity.getEnergyConsumption()};
        }

        @Callback(doc = "function():number -- Get the maximum energy input rate, in FE/t.")
        public Object[] getMaxInsert(final Context context, final Arguments args) {
            return new Object[]{com.mrh0.createaddition.config.CommonConfig.ELECTRIC_MOTOR_MAX_INPUT.get()};
        }

        @Callback(doc = "function():number -- Get the maximum energy extraction rate, in FE/t. Always 0: the motor only consumes energy, it never outputs any.")
        public Object[] getMaxExtract(final Context context, final Arguments args) {
            return new Object[]{0};
        }

        @Callback(doc = "function():boolean -- Whether a redstone signal is currently locking the motor (disabling it).")
        public Object[] isRedstoneLocked(final Context context, final Arguments args) {
            return new Object[]{blockEntity.isPoweredState()};
        }

        @Callback(doc = "function(degrees:number[, rpm:number]):number -- Get the time in seconds it would take to rotate by the given angle at the given (or current) speed. If rpm is given, also sets the motor's speed (signed to match the rotation direction), same as the equivalent ComputerCraft peripheral method.")
        public Object[] rotate(final Context context, final Arguments args) {
            final double degrees = args.checkDouble(0);
            final double rpm = args.count() > 1 ? args.checkDouble(1) : blockEntity.getRPM();
            if (args.count() > 1) {
                blockEntity.setRPM((float) (degrees < 0.0 ? -rpm : rpm));
            }
            return new Object[]{ElectricMotorBlockEntity.getDurationAngle((float) degrees, 0.0F, (float) rpm) / 20.0F};
        }

        @Callback(doc = "function(distance:number[, rpm:number]):number -- Get the time in seconds it would take to move the given distance (in blocks) at the given (or current) speed, for a contraption driven by this motor. If rpm is given, also sets the motor's speed (signed to match the direction), same as the equivalent ComputerCraft peripheral method.")
        public Object[] translate(final Context context, final Arguments args) {
            final double distance = args.checkDouble(0);
            final double rpm = args.count() > 1 ? args.checkDouble(1) : blockEntity.getRPM();
            if (args.count() > 1) {
                blockEntity.setRPM((float) (distance < 0.0 ? -rpm : rpm));
            }
            return new Object[]{ElectricMotorBlockEntity.getDurationDistance((float) distance, 0.0F, (float) rpm) / 20.0F};
        }
    }

    public static final class Provider implements EnvironmentProvider {
        public static final Provider INSTANCE = new Provider();

        private Provider() {
        }

        @Override
        public Class<?> getEnvironment(final ItemStack stack) {
            return stack.getItem() == CABlocks.ELECTRIC_MOTOR.get().asItem() ? Environment.class : null;
        }
    }
}
