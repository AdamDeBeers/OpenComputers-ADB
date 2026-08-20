package li.cil.oc.integration.appeng;

import appeng.api.networking.security.IActionHost;
import appeng.blockentity.networking.ControllerBlockEntity;
import li.cil.oc.api.Driver;
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

public final class DriverController extends DriverSidedBlockEntity {
    public static final DriverController INSTANCE = new DriverController();

    private DriverController() {
    }

    @Override
    public Class<?> getBlockEntityClass() {
        return ControllerBlockEntity.class;
    }

    @Override
    public ManagedEnvironment createEnvironment(final Level world, final BlockPos pos, final Direction side) {
        return new Environment((ControllerBlockEntity) world.getBlockEntity(pos));
    }

    public static final class Environment extends ManagedBlockEntityEnvironment<ControllerBlockEntity>
            implements NamedBlock, NetworkControl {
        public Environment(final ControllerBlockEntity tile) {
            super(tile, "me_controller");
        }

        @Override
        public String preferredName() {
            return "me_controller";
        }

        @Override
        public int priority() {
            return 5;
        }

        @Override
        public appeng.api.networking.IManagedGridNode gridNode() {
            return blockEntity.getMainNode();
        }

        @Override
        public IActionHost actionHost() {
            return blockEntity;
        }

        @Callback(doc = "function():number -- Get the current AE energy stored in the network.")
        public Object[] getEnergyStored(final Context context, final Arguments args) {
            return getStoredPower(context, args);
        }

        @Callback(doc = "function():number -- Get the maximum AE energy stored in the network.")
        public Object[] getMaxEnergyStored(final Context context, final Arguments args) {
            return getMaxStoredPower(context, args);
        }

        @Callback(doc = "function():boolean -- Whether the network can provide energy.")
        public Object[] canExtract(final Context context, final Arguments args) {
            return new Object[]{true};
        }

        @Callback(doc = "function():boolean -- Whether the network can receive energy.")
        public Object[] canReceive(final Context context, final Arguments args) {
            return new Object[]{true};
        }

        @Override
        @Callback(doc = "function():table -- Get a list of tables representing the available CPUs in the network.")
        public Object[] getCpus(final Context context, final Arguments args) {
            return NetworkControl.super.getCpus(context, args);
        }

        @Override
        @Callback(doc = "function([filter:table]):table -- Get a list of known item recipes. These can be used to issue crafting requests.")
        public Object[] getCraftables(final Context context, final Arguments args) {
            return NetworkControl.super.getCraftables(context, args);
        }

        @Override
        @Callback(doc = "function([filter:table]):table -- Get a list of the stored items in the network.")
        public Object[] getItemsInNetwork(final Context context, final Arguments args) {
            return NetworkControl.super.getItemsInNetwork(context, args);
        }

        @Override
        @Callback(doc = "function([filter:table, dbAddress:string, startSlot:number, count:number]):boolean -- Store matching items in the network in a database.")
        public Object[] store(final Context context, final Arguments args) {
            return NetworkControl.super.store(context, args);
        }

        @Override
        @Callback(doc = "function([filter:table]):table -- Get a list of the stored fluids in the network.")
        public Object[] getFluidsInNetwork(final Context context, final Arguments args) {
            return NetworkControl.super.getFluidsInNetwork(context, args);
        }

        @Override
        @Callback(doc = "function():number -- Get the average power injection into the network.")
        public Object[] getAvgPowerInjection(final Context context, final Arguments args) {
            return NetworkControl.super.getAvgPowerInjection(context, args);
        }

        @Override
        @Callback(doc = "function():number -- Get the average power usage of the network.")
        public Object[] getAvgPowerUsage(final Context context, final Arguments args) {
            return NetworkControl.super.getAvgPowerUsage(context, args);
        }

        @Override
        @Callback(doc = "function():number -- Get the idle power usage of the network.")
        public Object[] getIdlePowerUsage(final Context context, final Arguments args) {
            return NetworkControl.super.getIdlePowerUsage(context, args);
        }

        @Override
        @Callback(doc = "function():number -- Get the maximum stored power in the network.")
        public Object[] getMaxStoredPower(final Context context, final Arguments args) {
            return NetworkControl.super.getMaxStoredPower(context, args);
        }

        @Override
        @Callback(doc = "function():number -- Get the stored power in the network.")
        public Object[] getStoredPower(final Context context, final Arguments args) {
            return NetworkControl.super.getStoredPower(context, args);
        }

        @Override
        @Callback(doc = "function():boolean -- True if the AE network is considered online.")
        public Object[] isNetworkPowered(final Context context, final Arguments args) {
            return NetworkControl.super.isNetworkPowered(context, args);
        }

        @Override
        @Callback(direct = false, doc = "function():number -- Returns the energy demand on the AE network.")
        public Object[] getEnergyDemand(final Context context, final Arguments args) {
            return NetworkControl.super.getEnergyDemand(context, args);
        }
    }

    public static final class Provider implements EnvironmentProvider {
        public static final Provider INSTANCE = new Provider();

        private Provider() {
        }

        @Override
        public Class<?> getEnvironment(final ItemStack stack) {
            return AEUtil.isController(stack) ? Environment.class : null;
        }
    }
}
