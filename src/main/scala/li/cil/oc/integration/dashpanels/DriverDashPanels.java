package li.cil.oc.integration.dashpanels;

import li.cil.oc.api.driver.EnvironmentProvider;
import li.cil.oc.api.driver.NamedBlock;
import li.cil.oc.api.machine.Arguments;
import li.cil.oc.api.machine.Callback;
import li.cil.oc.api.machine.Context;
import li.cil.oc.api.network.ManagedEnvironment;
import li.cil.oc.api.prefab.DriverSidedBlockEntity;
import li.cil.oc.integration.ManagedBlockEntityEnvironment;
import moth.boxxed.panels.api.module.Module;
import moth.boxxed.panels.api.network.ModulesNetwork;
import moth.boxxed.panels.api.network.ModulesNetworkMember;
import moth.boxxed.panels.api.registry.ModulesRegistry;
import moth.boxxed.panels.compat.computercraft.IModuleLuaObject;
import moth.boxxed.panels.content.cable.CableBlockEntity;
import moth.boxxed.panels.content.panel.ceiling.CeilingPanelBlockEntity;
import moth.boxxed.panels.content.panel.normal.PanelBlockEntity;
import moth.boxxed.panels.content.panel.wall.WallPanelBlockEntity;
import moth.boxxed.panels.index.PanelBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// Drivers for DashPanels' four ComputerCraft-enabled block entities (control panel, wall
// panel, ceiling panel and control cable - the stripped cable is deliberately excluded, as
// DashPanels itself does not register a peripheral for it either). All four share identical
// network-member behaviour (DashPanels' own NetworkMemberPeripheral casts every one of them
// to the common ModulesNetworkMember interface and treats them the same), so a single shared
// Environment implementation is used, mirrored via a small per-block-type driver/provider
// pair.
//
// This is a direct, mod-loader-free port of DashPanels' own CC:Tweaked bridge
// (moth.boxxed.panels.compat.computercraft.peripherals.NetworkMemberPeripheral in the
// dashpanels jar). It deliberately avoids that class and DashPanels' CCPeripherals
// registration path entirely, since both hard-depend on dan200.computercraft classes that
// are absent when CC:Tweaked itself is not installed (as in this modpack) - loading either
// class would throw NoClassDefFoundError. Everything referenced here instead lives in
// DashPanels' own module API (moth.boxxed.panels.api.*) or in its
// compat.computercraft.IModuleLuaObject/IModuleArguments interfaces, which are plain
// DashPanels-authored abstractions with no ComputerCraft dependency of their own.
public final class DriverDashPanels {
    private DriverDashPanels() {
    }

    public static final class Environment extends ManagedBlockEntityEnvironment<ModulesNetworkMember> implements NamedBlock {
        public Environment(final ModulesNetworkMember blockEntity) {
            super(blockEntity, "dashpanels_control_panel");
        }

        @Override
        public String preferredName() {
            return "dashpanels_control_panel";
        }

        @Override
        public int priority() {
            return 0;
        }

        @Callback(doc = "function():string -- Get the id of the module network this block is part of, or nil if it isn't part of one yet.")
        public Object[] getNetwork(final Context context, final Arguments args) {
            return new Object[]{blockEntity.hasNetwork() ? blockEntity.network.toString() : null};
        }

        @Callback(doc = "function():table -- Get the type ids of every module type DashPanels has registered (e.g. \"switch\", \"knob\", \"joystick\", ...), regardless of whether one is placed anywhere.")
        public Object[] getAllModuleTypes(final Context context, final Arguments args) {
            final List<String> ret = new ArrayList<>();
            for (final ResourceLocation location : ModulesRegistry.MODULE_REGISTRY.keySet()) {
                ret.add(location.getPath());
            }
            return new Object[]{ret.toArray()};
        }

        @Callback(doc = "function():table -- Get userdata handles for every module on this block's network that exposes computer methods (modules with no computer methods, like labels, are omitted - same as on ComputerCraft).")
        public Object[] getModules(final Context context, final Arguments args) {
            final ModulesNetwork network = getCompiledNetwork();
            final List<ModuleValue> ret = new ArrayList<>();
            if (network != null) {
                for (final IModuleLuaObject module : network.getCompiledModules().asGenericLuaMap().values()) {
                    ret.add(new ModuleValue(module));
                }
            }
            return new Object[]{ret.toArray()};
        }

        @Callback(doc = "function(moduleName:string):userdata -- Get a userdata handle for the named module on this block's network.")
        public Object[] getModule(final Context context, final Arguments args) throws Exception {
            final String name = args.checkString(0);
            final ModulesNetwork network = getCompiledNetwork();
            final Map<String, IModuleLuaObject> modules = network == null ? Map.of() : network.getCompiledModules().asGenericLuaMap();
            if (!modules.containsKey(name)) {
                throw new Exception("attached network does not contain module '" + name + "'");
            }
            return new Object[]{new ModuleValue(modules.get(name))};
        }

        @Callback(doc = "function(typeName:string):table -- Get userdata handles for every module of the given type (e.g. \"switch\") on this block's network.")
        public Object[] getModulesOfType(final Context context, final Arguments args) {
            final String typeName = args.checkString(0);
            final ModulesNetwork network = getCompiledNetwork();
            final List<ModuleValue> ret = new ArrayList<>();
            if (network != null) {
                for (final Map.Entry<String, IModuleLuaObject> entry : network.getCompiledModules().asGenericLuaMap().entrySet()) {
                    if (entry.getValue() instanceof Module module) {
                        final ResourceLocation location = ModulesRegistry.MODULE_REGISTRY.getKey(module.type);
                        if (location != null && location.getPath().equals(typeName)) {
                            ret.add(new ModuleValue(entry.getValue()));
                        }
                    }
                }
            }
            return new Object[]{ret.toArray()};
        }

        @Callback(doc = "function(x:number, y:number):userdata -- Get a userdata handle for the module anchored at the given panel-local position on this block's network, or nil if there is none.")
        public Object[] getModuleAt(final Context context, final Arguments args) {
            final int x = args.checkInteger(0);
            final int y = args.checkInteger(1);
            final ModulesNetwork network = getCompiledNetwork();
            if (network != null) {
                for (final Map.Entry<String, IModuleLuaObject> entry : network.getCompiledModules().asGenericLuaMap().entrySet()) {
                    if (entry.getValue() instanceof Module module && module.getPos().x == x && module.getPos().y == y) {
                        return new Object[]{new ModuleValue(entry.getValue())};
                    }
                }
            }
            return new Object[]{null};
        }

        private ModulesNetwork getCompiledNetwork() {
            final ModulesNetwork network = blockEntity.getOrCreate();
            if (network == null) {
                return null;
            }
            network.compileModules();
            return network;
        }
    }

    public static final class Panel extends DriverSidedBlockEntity {
        public static final Panel INSTANCE = new Panel();

        private Panel() {
        }

        @Override
        public Class<?> getBlockEntityClass() {
            return PanelBlockEntity.class;
        }

        @Override
        public ManagedEnvironment createEnvironment(final Level world, final BlockPos pos, final Direction side) {
            return new Environment((ModulesNetworkMember) world.getBlockEntity(pos));
        }

        public static final class Provider implements EnvironmentProvider {
            public static final Provider INSTANCE = new Provider();

            private Provider() {
            }

            @Override
            public Class<?> getEnvironment(final ItemStack stack) {
                return stack.getItem() == PanelBlocks.CONTROL_PANEL.get().asItem() ? Environment.class : null;
            }
        }
    }

    public static final class WallPanel extends DriverSidedBlockEntity {
        public static final WallPanel INSTANCE = new WallPanel();

        private WallPanel() {
        }

        @Override
        public Class<?> getBlockEntityClass() {
            return WallPanelBlockEntity.class;
        }

        @Override
        public ManagedEnvironment createEnvironment(final Level world, final BlockPos pos, final Direction side) {
            return new Environment((ModulesNetworkMember) world.getBlockEntity(pos));
        }

        public static final class Provider implements EnvironmentProvider {
            public static final Provider INSTANCE = new Provider();

            private Provider() {
            }

            @Override
            public Class<?> getEnvironment(final ItemStack stack) {
                return stack.getItem() == PanelBlocks.WALL_CONTROL_PANEL.get().asItem() ? Environment.class : null;
            }
        }
    }

    public static final class CeilingPanel extends DriverSidedBlockEntity {
        public static final CeilingPanel INSTANCE = new CeilingPanel();

        private CeilingPanel() {
        }

        @Override
        public Class<?> getBlockEntityClass() {
            return CeilingPanelBlockEntity.class;
        }

        @Override
        public ManagedEnvironment createEnvironment(final Level world, final BlockPos pos, final Direction side) {
            return new Environment((ModulesNetworkMember) world.getBlockEntity(pos));
        }

        public static final class Provider implements EnvironmentProvider {
            public static final Provider INSTANCE = new Provider();

            private Provider() {
            }

            @Override
            public Class<?> getEnvironment(final ItemStack stack) {
                return stack.getItem() == PanelBlocks.CEILING_CONTROL_PANEL.get().asItem() ? Environment.class : null;
            }
        }
    }

    public static final class Cable extends DriverSidedBlockEntity {
        public static final Cable INSTANCE = new Cable();

        private Cable() {
        }

        @Override
        public Class<?> getBlockEntityClass() {
            return CableBlockEntity.class;
        }

        @Override
        public ManagedEnvironment createEnvironment(final Level world, final BlockPos pos, final Direction side) {
            return new Environment((ModulesNetworkMember) world.getBlockEntity(pos));
        }

        public static final class Provider implements EnvironmentProvider {
            public static final Provider INSTANCE = new Provider();

            private Provider() {
            }

            @Override
            public Class<?> getEnvironment(final ItemStack stack) {
                return stack.getItem() == PanelBlocks.CABLE.get().asItem() ? Environment.class : null;
            }
        }
    }
}
