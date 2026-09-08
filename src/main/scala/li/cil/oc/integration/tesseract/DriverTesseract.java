package li.cil.oc.integration.tesseract;

import com.supermartijn642.tesseract.EnumChannelType;
import com.supermartijn642.tesseract.Tesseract;
import com.supermartijn642.tesseract.TesseractBlockEntity;
import com.supermartijn642.tesseract.manager.Channel;
import com.supermartijn642.tesseract.manager.TesseractChannelManager;
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

import java.util.Locale;

// Channels are per-type ("items"/"fluid"/"energy"), so every callback that touches a
// channel takes the type as a string rather than exposing the raw EnumChannelType/int
// pair Tesseract itself uses internally - friendlier from Lua, and it keeps the driver
// from having to hand out an enum userdata just to pick a channel. A channel id of -1
// (Tesseract's own "none set" sentinel) is surfaced as a plain nil name rather than an
// error, since not having picked a channel yet for a given type is the normal, expected
// state right after placing the block.
public final class DriverTesseract extends DriverSidedBlockEntity {
    public static final DriverTesseract INSTANCE = new DriverTesseract();

    private DriverTesseract() {
    }

    @Override
    public Class<?> getBlockEntityClass() {
        return TesseractBlockEntity.class;
    }

    @Override
    public ManagedEnvironment createEnvironment(final Level world, final BlockPos pos, final Direction side) {
        return new Environment((TesseractBlockEntity) world.getBlockEntity(pos));
    }

    public static final class Environment extends ManagedBlockEntityEnvironment<TesseractBlockEntity> implements NamedBlock {
        public Environment(final TesseractBlockEntity tile) {
            super(tile, "tesseract");
        }

        @Override
        public String preferredName() {
            return "tesseract";
        }

        @Override
        public int priority() {
            return 0;
        }

        @Callback(doc = "function(channelType:string):number -- Get the id of the channel currently set for the given channel type (\"items\", \"fluid\" or \"energy\"), or -1 if none is set.")
        public Object[] getChannelId(final Context context, final Arguments args) {
            final EnumChannelType type = parseChannelType(args.checkString(0));
            return new Object[]{blockEntity.getChannelId(type)};
        }

        @Callback(doc = "function(channelType:string):string -- Get the name of the channel currently set for the given channel type, or nil if none is set.")
        public Object[] getChannelName(final Context context, final Arguments args) {
            final EnumChannelType type = parseChannelType(args.checkString(0));
            final int id = blockEntity.getChannelId(type);
            if (id == -1) {
                return new Object[]{null};
            }
            final Channel channel = TesseractChannelManager.getInstance(blockEntity.getLevel()).getChannelById(type, id);
            return new Object[]{channel != null ? channel.name : null};
        }

        @Callback(doc = "function(channelType:string):string -- Get the transfer direction for the given channel type: \"SEND\", \"RECEIVE\" or \"BOTH\".")
        public Object[] getTransferState(final Context context, final Arguments args) {
            final EnumChannelType type = parseChannelType(args.checkString(0));
            return new Object[]{blockEntity.getTransferState(type).name()};
        }

        @Callback(doc = "function(channelType:string) -- Cycle the transfer direction for the given channel type to its next state.")
        public Object[] cycleTransferState(final Context context, final Arguments args) {
            final EnumChannelType type = parseChannelType(args.checkString(0));
            blockEntity.cycleTransferState(type);
            return null;
        }

        @Callback(doc = "function():string -- Get the redstone control mode: \"DISABLED\", \"HIGH\" or \"LOW\".")
        public Object[] getRedstoneState(final Context context, final Arguments args) {
            return new Object[]{blockEntity.getRedstoneState().name()};
        }

        @Callback(doc = "function() -- Cycle the redstone control mode to its next state.")
        public Object[] cycleRedstoneState(final Context context, final Arguments args) {
            blockEntity.cycleRedstoneState();
            return null;
        }

        @Callback(doc = "function(channelType:string):boolean -- Whether the given channel type is currently allowed to send.")
        public Object[] canSend(final Context context, final Arguments args) {
            final EnumChannelType type = parseChannelType(args.checkString(0));
            return new Object[]{blockEntity.canSend(type)};
        }

        @Callback(doc = "function(channelType:string):boolean -- Whether the given channel type is currently allowed to receive.")
        public Object[] canReceive(final Context context, final Arguments args) {
            final EnumChannelType type = parseChannelType(args.checkString(0));
            return new Object[]{blockEntity.canReceive(type)};
        }

        @Callback(doc = "function():boolean -- Whether this tesseract is currently blocked by its redstone control mode.")
        public Object[] isBlockedByRedstone(final Context context, final Arguments args) {
            return new Object[]{blockEntity.isBlockedByRedstone()};
        }

        private static EnumChannelType parseChannelType(final String channelType) {
            return switch (channelType.toLowerCase(Locale.ROOT)) {
                case "items" -> EnumChannelType.ITEMS;
                case "fluid" -> EnumChannelType.FLUID;
                case "energy" -> EnumChannelType.ENERGY;
                default -> throw new IllegalArgumentException("invalid channel type '" + channelType + "' (expected 'items', 'fluid' or 'energy')");
            };
        }
    }

    public static final class Provider implements EnvironmentProvider {
        public static final Provider INSTANCE = new Provider();

        private Provider() {
        }

        @Override
        public Class<?> getEnvironment(final ItemStack stack) {
            return stack.getItem() == Tesseract.tesseract.asItem() ? Environment.class : null;
        }
    }
}
