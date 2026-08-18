package li.cil.oc.integration.dashpanels;

import li.cil.oc.api.machine.Arguments;
import li.cil.oc.api.machine.Context;
import li.cil.oc.api.network.ManagedPeripheral;
import li.cil.oc.api.prefab.AbstractValue;
import moth.boxxed.panels.compat.computercraft.IModuleArguments;
import moth.boxxed.panels.compat.computercraft.IModuleLuaObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

// Userdata handle for a single DashPanels module (switch, knob, joystick, ...),
// as returned by DriverDashPanels' getModule/getModules/getModulesOfType/getModuleAt.
// Mirrors DashPanels' own ComputerCraft bridge (NetworkMemberPeripheral.fromModuleLuaObject
// in the dashpanels jar), which turns an IModuleLuaObject into a dynamic Lua object: the set
// of methods is whatever the module registers via IModuleLuaObject.getMethods() plus the
// shared defaults from IModuleLuaObject.getDefaultMethods() (getType/getName/getX/getY/
// getSizeX/getSizeY/getMethods) - rebuilt fresh on every call, same as upstream, so this
// automatically covers every module type DashPanels has today and any it adds later without
// requiring changes here. Not persistable, same as ComputerCraft's own dynamic Lua objects:
// the live module reference cannot survive a computer state save/load.
public final class ModuleValue extends AbstractValue implements ManagedPeripheral {
    private final IModuleLuaObject module;

    // For loading when values were saved in a computer state.
    public ModuleValue() {
        module = null;
    }

    public ModuleValue(final IModuleLuaObject module) {
        this.module = module;
    }

    private Map<String, IModuleLuaObject.ReturnMethod<?>> buildMethods() {
        final Map<String, IModuleLuaObject.ReturnMethod<?>> methods = new LinkedHashMap<>();
        final List<String> names = new ArrayList<>();
        final BiConsumer<String, IModuleLuaObject.ReturnMethod<?>> consumer = (name, method) -> {
            methods.put(name, method);
            names.add(name);
        };
        module.getMethods(consumer);
        module.getDefaultMethods(names, consumer);
        return methods;
    }

    @Override
    public String[] methods() {
        if (module == null) {
            return new String[0]; // Loaded userdata, missing context.
        }
        return buildMethods().keySet().toArray(new String[0]);
    }

    @Override
    public Object[] invoke(final String method, final Context context, final Arguments args) throws Exception {
        if (module == null) {
            return new Object[]{null, "DashPanels module handles cannot be persisted"};
        }
        final IModuleLuaObject.ReturnMethod<?> returnMethod = buildMethods().get(method);
        if (returnMethod == null) {
            throw new NoSuchMethodException(method);
        }
        return wrapResult(returnMethod.get(new OCModuleArguments(args)));
    }

    private static Object[] wrapResult(final Object result) {
        if (result instanceof List<?> list) {
            return new Object[]{list.toArray()};
        }
        return new Object[]{result};
    }

    // Adapts OC's Arguments to the IModuleArguments interface DashPanels' module methods
    // expect, so module getMethods() implementations (written against the dashpanels
    // ComputerCraft compat layer) work unmodified against OC callback arguments.
    private static final class OCModuleArguments implements IModuleArguments {
        private final Object[] values;
        private final int offset;

        OCModuleArguments(final Arguments args) {
            // Arguments.toArray() converts Lua strings to String automatically, matching
            // what DashPanels' own IArguments-backed implementation does for ComputerCraft.
            this(args.toArray(), 0);
        }

        private OCModuleArguments(final Object[] values, final int offset) {
            this.values = values;
            this.offset = offset;
        }

        @Override
        public int count() {
            return Math.max(0, values.length - offset);
        }

        @Override
        public Object get(final int index) {
            final int i = index + offset;
            return i >= 0 && i < values.length ? values[i] : null;
        }

        @Override
        public String getType(final int index) {
            final Object value = get(index);
            if (value == null) {
                return "nil";
            } else if (value instanceof Boolean) {
                return "boolean";
            } else if (value instanceof Number) {
                return "number";
            } else if (value instanceof String) {
                return "string";
            } else if (value instanceof Map) {
                return "table";
            } else {
                return "userdata";
            }
        }

        @Override
        public IModuleArguments drop(final int count) {
            return new OCModuleArguments(values, offset + count);
        }
    }
}
