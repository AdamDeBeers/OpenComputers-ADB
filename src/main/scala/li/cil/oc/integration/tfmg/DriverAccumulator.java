package li.cil.oc.integration.tfmg;

import com.drmangotea.tfmg.content.electricity.storage.AccumulatorBlockEntity;
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

// Read-only: AccumulatorBlockEntity has no public setter for its stored
// energy either (short of NBT/item capacity tricks used only when the block
// is placed from an item). Accumulators form a multiblock stack along their
// facing direction; only the "controller" part (the one nothing continues
// behind, see isController()) owns the real energy storage, length and
// derived capacity - the other parts mirror it via a shared energy
// capability but report zero for their own local fields, so
// callers should target the controller part for anything beyond
// getNetworkVoltage, which reflects this specific part's view of the network
// regardless of its role in the stack.
//
// Since TFMG 1.3 the accumulator is a plain FE storage: charging and
// discharging are driven by the FE transfer of attached cable connectors and
// hubs, and the block entity no longer exposes a charging rate, output voltage
// or charge direction. Those are therefore derived here from the change in
// stored energy, sampled over a fixed window of game ticks.
public final class DriverAccumulator extends DriverSidedBlockEntity {
    public static final DriverAccumulator INSTANCE = new DriverAccumulator();

    private DriverAccumulator() {
    }

    @Override
    public Class<?> getBlockEntityClass() {
        return AccumulatorBlockEntity.class;
    }

    @Override
    public ManagedEnvironment createEnvironment(final Level world, final BlockPos pos, final Direction side) {
        return new Environment((AccumulatorBlockEntity) world.getBlockEntity(pos));
    }

    public static final class Environment extends ManagedBlockEntityEnvironment<AccumulatorBlockEntity> implements NamedBlock {
        public Environment(final AccumulatorBlockEntity tile) {
            super(tile, "tfmg_accumulator");
        }

        @Override
        public String preferredName() {
            return "tfmg_accumulator";
        }

        @Override
        public int priority() {
            return 0;
        }

        // FE transfers into and out of the accumulator do not necessarily
        // happen every tick, so the delta is averaged over this many ticks.
        private static final long SAMPLE_WINDOW_TICKS = 20L;

        private long windowStartTick = Long.MIN_VALUE;
        private int windowStartEnergy;
        // Average energy change per tick over the last completed window, in FE/t.
        private double energyDelta;

        @Override
        public boolean canUpdate() {
            return true;
        }

        @Override
        public void update() {
            final Level level = blockEntity.getLevel();
            if (level == null || blockEntity.isRemoved()) {
                return;
            }

            final long now = level.getGameTime();
            final int energy = blockEntity.energy.getEnergyStored();
            if (windowStartTick == Long.MIN_VALUE || now < windowStartTick) {
                windowStartTick = now;
                windowStartEnergy = energy;
                return;
            }

            final long elapsed = now - windowStartTick;
            if (elapsed >= SAMPLE_WINDOW_TICKS) {
                energyDelta = (double) (energy - windowStartEnergy) / elapsed;
                windowStartTick = now;
                windowStartEnergy = energy;
            }
        }

        @Callback(doc = "function():boolean -- Whether this block is the controller of the accumulator multiblock stack. Stored energy, capacity and rate figures are only meaningful on the controller.")
        public Object[] isController(final Context context, final Arguments args) {
            return new Object[]{blockEntity.isController()};
        }

        @Callback(doc = "function():number -- Get the amount of energy currently stored, in FE. Only meaningful on the controller.")
        public Object[] getStoredEnergy(final Context context, final Arguments args) {
            return new Object[]{blockEntity.energy.getEnergyStored()};
        }

        @Callback(doc = "function():number -- Get the maximum amount of energy this accumulator stack can store, in FE. Only meaningful on the controller.")
        public Object[] getMaxCapacity(final Context context, final Arguments args) {
            return new Object[]{blockEntity.getMaxCapacity()};
        }

        @Callback(doc = "function():number -- Get the average change in stored energy, in FE/t, over the last second. Positive while charging, negative while discharging. Only meaningful on the controller.")
        public Object[] getEnergyDelta(final Context context, final Arguments args) {
            return new Object[]{energyDelta};
        }

        @Callback(doc = "function():number -- Get the current charging rate, in FE/t, averaged over the last second. 0 if not currently charging. Only meaningful on the controller.")
        public Object[] getChargingRate(final Context context, final Arguments args) {
            return new Object[]{Math.max(0.0, energyDelta)};
        }

        @Callback(doc = "function():boolean -- Whether the stored energy increased over the last second. Only meaningful on the controller.")
        public Object[] isCharging(final Context context, final Arguments args) {
            return new Object[]{energyDelta > 0};
        }

        @Callback(doc = "function():boolean -- Whether the stored energy decreased over the last second. Only meaningful on the controller.")
        public Object[] isDischarging(final Context context, final Arguments args) {
            return new Object[]{energyDelta < 0};
        }

        @Callback(doc = "function():number -- Get the electrical network's voltage at this block.")
        public Object[] getNetworkVoltage(final Context context, final Arguments args) {
            return new Object[]{blockEntity.getData().getVoltage()};
        }

        @Callback(doc = "function():number -- Get the number of accumulator blocks forming this multiblock stack. Only meaningful on the controller.")
        public Object[] getLength(final Context context, final Arguments args) {
            return new Object[]{blockEntity.length};
        }
    }

    public static final class Provider implements EnvironmentProvider {
        public static final Provider INSTANCE = new Provider();

        private Provider() {
        }

        @Override
        public Class<?> getEnvironment(final ItemStack stack) {
            return stack.getItem() == TFMGBlocks.ACCUMULATOR.get().asItem() ? Environment.class : null;
        }
    }
}
