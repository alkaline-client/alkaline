package alkaline.game.entity;

import alkaline.game.inventory.ItemStack;
import alkaline.game.net.PacketBuffer;
import java.io.IOException;
import java.util.Arrays;

/**
 * An entity's watched values (vanilla's DataWatcher) by index 0..31. Values keep the protocol's types: Byte,
 * Short, Integer, Float, String, {@link ItemStack} (or null), int[3] positions and float[3] rotations.
 */
public final class Metadata {
    private static final int SLOTS = 32;
    private final Object[] values = new Object[SLOTS];
    private final boolean[] present = new boolean[SLOTS];

    /** Reads entries until the 0x7F terminator (protocol 47 entity metadata). */
    public static Metadata read(PacketBuffer in) throws IOException {
        Metadata metadata = new Metadata();
        for (int guard = 0; guard < 256; guard++) {
            int header = in.readUnsignedByte();
            if (header == 0x7F) {
                return metadata;
            }
            int index = header & 0x1F;
            Object value = switch (header >> 5) {
                case 0 -> in.readByte();
                case 1 -> in.readShort();
                case 2 -> in.readInt();
                case 3 -> in.readFloat();
                case 4 -> in.readString();
                case 5 -> in.readSlot();
                case 6 -> new int[]{in.readInt(), in.readInt(), in.readInt()};
                case 7 -> new float[]{in.readFloat(), in.readFloat(), in.readFloat()};
                default -> throw new IOException("Unknown metadata type " + (header >> 5));
            };
            metadata.values[index] = value;
            metadata.present[index] = true;
        }
        throw new IOException("Entity metadata is not terminated.");
    }

    /** Copies every value {@code update} carries over this one (Entity Metadata packets send changes only). */
    public void merge(Metadata update) {
        for (int index = 0; index < SLOTS; index++) {
            if (update.present[index]) {
                values[index] = update.values[index];
                present[index] = true;
            }
        }
    }

    /** Sets a byte value locally (the client's own copy of the local player, for its flags). */
    public void setByte(int index, int value) {
        values[index] = (byte) value;
        present[index] = true;
    }

    public boolean has(int index) {
        return present[index];
    }

    public int getByte(int index, int fallback) {
        return values[index] instanceof Byte value ? value : fallback;
    }

    public int getShort(int index, int fallback) {
        return values[index] instanceof Short value ? value : fallback;
    }

    public int getInt(int index, int fallback) {
        return values[index] instanceof Integer value ? value : fallback;
    }

    public float getFloat(int index, float fallback) {
        return values[index] instanceof Float value ? value : fallback;
    }

    public String getString(int index, String fallback) {
        return values[index] instanceof String value ? value : fallback;
    }

    public ItemStack getItem(int index) {
        return values[index] instanceof ItemStack value ? value : null;
    }

    @Override
    public String toString() {
        StringBuilder text = new StringBuilder("Metadata{");
        for (int index = 0; index < SLOTS; index++) {
            if (present[index]) {
                Object value = values[index];
                text.append(index).append('=').append(value instanceof int[] ints ? Arrays.toString(ints)
                        : value instanceof float[] floats ? Arrays.toString(floats) : value).append(' ');
            }
        }
        return text.append('}').toString();
    }
}
