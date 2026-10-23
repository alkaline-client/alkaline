package alkaline.game.data.nbt;

import java.io.ByteArrayInputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.IOException;

public class NBTSizeTracker
{
    public static final long DEFAULT_MAX_SIZE = 2_097_152L;
    public static final NBTSizeTracker INFINITE = new NBTSizeTracker(0L)
    {
        public void read(long bits)
        {
        }
    };
    private final long max;
    private long read;

    public NBTSizeTracker(long max)
    {
        if (max < 0L)
        {
            throw new IllegalArgumentException("NBT size limit cannot be negative");
        }
        this.max = max;
    }

    public void read(long bits)
    {
        if (bits < 0L)
        {
            throw new IllegalArgumentException("NBT size cannot be negative");
        }
        long bytes = bits / 8L + (bits % 8L == 0L ? 0L : 1L);

        if (bytes > this.max - this.read)
        {
            throw new NBTSizeLimitException(this.read + bytes, this.max);
        }
        this.read += bytes;
    }

    String readString(DataInput input, long allocationBits) throws IOException
    {
        int length = input.readUnsignedShort();
        try
        {
            this.read(allocationBits + 16L + 24L * length);
        }
        catch (NBTSizeLimitException exception)
        {
            throw new IOException(exception.getMessage(), exception);
        }
        byte[] encoded = new byte[length + 2];
        encoded[0] = (byte)(length >>> 8);
        encoded[1] = (byte)length;
        input.readFully(encoded, 2, length);

        try (DataInputStream stringInput = new DataInputStream(new ByteArrayInputStream(encoded)))
        {
            return stringInput.readUTF();
        }
    }

    public static final class NBTSizeLimitException extends RuntimeException
    {
        private static final long serialVersionUID = 1L;

        private NBTSizeLimitException(long requested, long max)
        {
            super("Tried to read NBT tag that was too big; tried to allocate: " + requested
                    + " bytes where max allowed: " + max);
        }
    }
}
