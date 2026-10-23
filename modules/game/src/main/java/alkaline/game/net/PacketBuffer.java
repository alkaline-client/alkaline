package alkaline.game.net;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

/**
 * A growable byte buffer with Minecraft protocol 47's field encodings (big-endian numbers, VarInts, UTF-8
 * strings with a VarInt length, packed block positions). One instance either reads a received packet or builds
 * one to send. Reads past the end throw {@link IOException}, so a malformed packet cannot crash the client.
 */
public final class PacketBuffer {
    /** Strings longer than this many UTF-16 units are rejected, as vanilla rejects them (32767). */
    public static final int MAX_STRING = 32767;

    private byte[] data;
    private int position;
    private int limit;

    /** A buffer for building a packet. */
    public PacketBuffer() {
        data = new byte[64];
    }

    /** A buffer reading {@code length} bytes of {@code data} from {@code offset}. */
    public PacketBuffer(byte[] data, int offset, int length) {
        this.data = data;
        this.position = offset;
        this.limit = offset + length;
    }

    public static PacketBuffer wrap(byte[] data) {
        return new PacketBuffer(data, 0, data.length);
    }

    // ---- reading -------------------------------------------------------------------------------------------

    public int remaining() {
        return limit - position;
    }

    private void need(int bytes) throws IOException {
        if (bytes < 0 || position + bytes > limit) {
            throw new IOException("Packet ended early (needed " + bytes + " more bytes, " + remaining() + " left).");
        }
    }

    public int readUnsignedByte() throws IOException {
        need(1);
        return data[position++] & 0xFF;
    }

    public byte readByte() throws IOException {
        need(1);
        return data[position++];
    }

    public boolean readBoolean() throws IOException {
        return readByte() != 0;
    }

    public short readShort() throws IOException {
        need(2);
        short value = (short) ((data[position] & 0xFF) << 8 | data[position + 1] & 0xFF);
        position += 2;
        return value;
    }

    public int readUnsignedShort() throws IOException {
        return readShort() & 0xFFFF;
    }

    public int readInt() throws IOException {
        need(4);
        int value = (data[position] & 0xFF) << 24 | (data[position + 1] & 0xFF) << 16
                | (data[position + 2] & 0xFF) << 8 | data[position + 3] & 0xFF;
        position += 4;
        return value;
    }

    public long readLong() throws IOException {
        return (long) readInt() << 32 | readInt() & 0xFFFFFFFFL;
    }

    public float readFloat() throws IOException {
        return Float.intBitsToFloat(readInt());
    }

    public double readDouble() throws IOException {
        return Double.longBitsToDouble(readLong());
    }

    public int readVarInt() throws IOException {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int read = readUnsignedByte();
            value |= (read & 0x7F) << shift;
            if ((read & 0x80) == 0) {
                return value;
            }
        }
        throw new IOException("VarInt too long.");
    }

    public long readVarLong() throws IOException {
        long value = 0;
        for (int shift = 0; shift < 70; shift += 7) {
            int read = readUnsignedByte();
            value |= (long) (read & 0x7F) << shift;
            if ((read & 0x80) == 0) {
                return value;
            }
        }
        throw new IOException("VarLong too long.");
    }

    public String readString() throws IOException {
        int length = readVarInt();
        if (length < 0 || length > MAX_STRING * 4) {
            throw new IOException("String length " + length + " out of range.");
        }
        need(length);
        String value = new String(data, position, length, StandardCharsets.UTF_8);
        position += length;
        if (value.length() > MAX_STRING) {
            throw new IOException("String too long.");
        }
        return value;
    }

    public byte[] readBytes(int length) throws IOException {
        need(length);
        byte[] bytes = Arrays.copyOfRange(data, position, position + length);
        position += length;
        return bytes;
    }

    /** A byte array prefixed with its VarInt length. */
    public byte[] readByteArray(int maxLength) throws IOException {
        int length = readVarInt();
        if (length < 0 || length > maxLength) {
            throw new IOException("Byte array length " + length + " out of range.");
        }
        return readBytes(length);
    }

    /** Copies {@code length} bytes into {@code target} without allocating. */
    public void readInto(byte[] target, int offset, int length) throws IOException {
        need(length);
        System.arraycopy(data, position, target, offset, length);
        position += length;
    }

    public void skip(int bytes) throws IOException {
        need(bytes);
        position += bytes;
    }

    /** A block position packed as x (26 bits) << 38 | y (12) << 26 | z (26), signed. */
    public long readPosition() throws IOException {
        return readLong();
    }

    public static int positionX(long packed) {
        return (int) (packed >> 38);
    }

    public static int positionY(long packed) {
        return (int) (packed << 26 >> 52);
    }

    public static int positionZ(long packed) {
        return (int) (packed << 38 >> 38);
    }

    public UUID readUuid() throws IOException {
        return new UUID(readLong(), readLong());
    }

    /**
     * An NBT compound as item slots carry it (uncompressed, named root), or null for the single 0 byte that
     * means none. Limited to vanilla's 2 MiB accounting, as PacketBuffer.readNBTTagCompoundFromBuffer is.
     */
    public alkaline.game.data.nbt.NBTTagCompound readNbt() throws IOException {
        need(1);
        if (data[position] == 0) {
            position++;
            return null;
        }
        java.io.ByteArrayInputStream bytes = new java.io.ByteArrayInputStream(data, position, remaining());
        int before = bytes.available();
        alkaline.game.data.nbt.NBTTagCompound tag = alkaline.game.data.nbt.CompressedStreamTools.read(
                new java.io.DataInputStream(bytes), new alkaline.game.data.nbt.NBTSizeTracker(2_097_152L));
        position += before - bytes.available();
        return tag;
    }

    /** A slot: item id (-1 empty), count, damage and optional NBT; null when empty. */
    public alkaline.game.inventory.ItemStack readSlot() throws IOException {
        int id = readShort();
        if (id < 0) {
            return null;
        }
        int count = readByte();
        int damage = readShort();
        return new alkaline.game.inventory.ItemStack(id, count, damage, readNbt());
    }

    // ---- writing -------------------------------------------------------------------------------------------

    private void ensure(int bytes) {
        if (limit + bytes > data.length) {
            data = Arrays.copyOf(data, Math.max(data.length * 2, limit + bytes));
        }
    }

    public PacketBuffer writeByte(int value) {
        ensure(1);
        data[limit++] = (byte) value;
        return this;
    }

    public PacketBuffer writeBoolean(boolean value) {
        return writeByte(value ? 1 : 0);
    }

    public PacketBuffer writeShort(int value) {
        ensure(2);
        data[limit++] = (byte) (value >> 8);
        data[limit++] = (byte) value;
        return this;
    }

    public PacketBuffer writeInt(int value) {
        ensure(4);
        data[limit++] = (byte) (value >> 24);
        data[limit++] = (byte) (value >> 16);
        data[limit++] = (byte) (value >> 8);
        data[limit++] = (byte) value;
        return this;
    }

    public PacketBuffer writeLong(long value) {
        writeInt((int) (value >> 32));
        return writeInt((int) value);
    }

    public PacketBuffer writeFloat(float value) {
        return writeInt(Float.floatToIntBits(value));
    }

    public PacketBuffer writeDouble(double value) {
        return writeLong(Double.doubleToLongBits(value));
    }

    public PacketBuffer writeVarInt(int value) {
        while ((value & ~0x7F) != 0) {
            writeByte(value & 0x7F | 0x80);
            value >>>= 7;
        }
        return writeByte(value);
    }

    public PacketBuffer writeString(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(bytes.length);
        return writeBytes(bytes);
    }

    public PacketBuffer writeBytes(byte[] bytes) {
        ensure(bytes.length);
        System.arraycopy(bytes, 0, data, limit, bytes.length);
        limit += bytes.length;
        return this;
    }

    public PacketBuffer writeByteArray(byte[] bytes) {
        writeVarInt(bytes.length);
        return writeBytes(bytes);
    }

    /** An item slot as PacketBuffer.writeItemStackToBuffer: id (-1 for empty), count, damage, then the tag or TAG_End. */
    public PacketBuffer writeSlot(alkaline.game.inventory.ItemStack item) {
        if (item == null) {
            return writeShort(-1);
        }
        writeShort(item.id()).writeByte(item.count()).writeShort(item.damage());
        if (item.tag() == null) {
            return writeByte(0);
        }
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try {
            alkaline.game.data.nbt.CompressedStreamTools.write(item.tag(), new java.io.DataOutputStream(bytes));
        } catch (IOException impossible) {
            throw new java.io.UncheckedIOException(impossible);
        }
        return writeBytes(bytes.toByteArray());
    }

    public PacketBuffer writeUuid(UUID uuid) {
        return writeLong(uuid.getMostSignificantBits()).writeLong(uuid.getLeastSignificantBits());
    }

    public PacketBuffer writePosition(int x, int y, int z) {
        return writeLong(((long) x & 0x3FFFFFF) << 38 | ((long) y & 0xFFF) << 26 | (long) z & 0x3FFFFFF);
    }

    /** The written bytes (for a built packet). */
    public byte[] toByteArray() {
        return Arrays.copyOf(data, limit);
    }

    public int size() {
        return limit;
    }

    public static int varIntSize(int value) {
        int bytes = 1;
        while ((value & ~0x7F) != 0) {
            bytes++;
            value >>>= 7;
        }
        return bytes;
    }
}
