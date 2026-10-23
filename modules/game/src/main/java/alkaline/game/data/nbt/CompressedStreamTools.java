package alkaline.game.data.nbt;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class CompressedStreamTools
{
    public static NBTTagCompound readCompressed(InputStream is) throws IOException
    {
        try (DataInputStream dataInput = new DataInputStream(new BufferedInputStream(
                new BoundedInputStream(new GZIPInputStream(is), NBTSizeTracker.DEFAULT_MAX_SIZE))))
        {
            NBTTagCompound compound = read(dataInput, new NBTSizeTracker(NBTSizeTracker.DEFAULT_MAX_SIZE));
            byte[] drainBuffer = new byte[8192];
            while (dataInput.read(drainBuffer) != -1)
            {
                // Drain through the GZIP trailer so integrity errors are reported.
            }
            return compound;
        }
    }

    private static final class BoundedInputStream extends FilterInputStream
    {
        private final long maxBytes;
        private long readBytes;

        private BoundedInputStream(InputStream input, long maxBytes)
        {
            super(input);
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException
        {
            if (this.readBytes == this.maxBytes)
            {
                int extra = super.read();
                if (extra != -1)
                {
                    throw new IOException("Compressed NBT input exceeds " + this.maxBytes + " bytes");
                }
                return -1;
            }
            int value = super.read();
            if (value != -1)
            {
                ++this.readBytes;
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException
        {
            if (length == 0)
            {
                return 0;
            }
            if (this.readBytes == this.maxBytes)
            {
                int extra = super.read();
                if (extra != -1)
                {
                    throw new IOException("Compressed NBT input exceeds " + this.maxBytes + " bytes");
                }
                return -1;
            }
            int permittedLength = (int)Math.min(length, this.maxBytes - this.readBytes);
            int count = super.read(buffer, offset, permittedLength);
            if (count > 0)
            {
                this.readBytes += count;
            }
            return count;
        }
    }

    public static void writeCompressed(NBTTagCompound p_74799_0_, OutputStream outputStream) throws IOException
    {

        try (DataOutputStream dataoutputstream = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(outputStream)))) {
            write(p_74799_0_, dataoutputstream);
        }
    }

    public static void safeWrite(NBTTagCompound p_74793_0_, File p_74793_1_) throws IOException
    {
        Path target = p_74793_1_.toPath().toAbsolutePath();
        Path parent = target.getParent();
        if (parent == null || !Files.isDirectory(parent))
        {
            throw new IOException("Target directory does not exist for " + target);
        }
        if (Files.exists(target) && !Files.isRegularFile(target))
        {
            throw new IOException("Target is not a regular file: " + target);
        }
        Path temporary = Files.createTempFile(parent, "nbt-" + target.getFileName() + "-", ".tmp");
        Path backup = null;
        boolean backupContainsTarget = false;

        try
        {
            write(p_74793_0_, temporary.toFile());
            if (Files.exists(target))
            {
                backup = Files.createTempFile(parent, "nbt-backup-", ".tmp");
                Files.delete(backup);
                try
                {
                    Files.copy(target, backup);
                    backupContainsTarget = true;
                }
                catch (IOException backupFailure)
                {
                    Files.deleteIfExists(backup);
                    backup = null;
                    throw backupFailure;
                }
                Files.delete(target);
            }
            try
            {
                Files.move(temporary, target);
            }
            catch (IOException replacementFailure)
            {
                if (backupContainsTarget)
                {
                    try
                    {
                        Files.copy(backup, target, StandardCopyOption.REPLACE_EXISTING);
                        Files.deleteIfExists(backup);
                        backup = null;
                        backupContainsTarget = false;
                    }
                    catch (IOException restoreFailure)
                    {
                        replacementFailure.addSuppressed(restoreFailure);
                    }
                }
                throw replacementFailure;
            }
            if (backupContainsTarget)
            {
                try
                {
                    Files.deleteIfExists(backup);
                    backup = null;
                    backupContainsTarget = false;
                }
                catch (IOException cleanupFailure)
                {
                    // Keep the completed replacement and retain the old save as a backup.
                }
            }
        }
        catch (IOException | RuntimeException | Error failure)
        {
            try
            {
                Files.deleteIfExists(temporary);
                if (backupContainsTarget)
                {
                    Files.move(backup, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            catch (IOException cleanupFailure)
            {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    public static void write(NBTTagCompound p_74795_0_, File p_74795_1_) throws IOException
    {

        try (DataOutputStream dataoutputstream = new DataOutputStream(new FileOutputStream(p_74795_1_))) {
            write(p_74795_0_, dataoutputstream);
        }
    }

    public static NBTTagCompound read(File p_74797_0_) throws IOException
    {
        if (!p_74797_0_.exists())
        {
            return null;
        }
        else
        {
            DataInputStream datainputstream = new DataInputStream(new FileInputStream(p_74797_0_));
            NBTTagCompound nbttagcompound;

            try
            {
                nbttagcompound = read(datainputstream, new NBTSizeTracker(NBTSizeTracker.DEFAULT_MAX_SIZE));
            }
            finally
            {
                datainputstream.close();
            }

            return nbttagcompound;
        }
    }

    public static NBTTagCompound read(DataInputStream inputStream) throws IOException
    {
        return read(inputStream, new NBTSizeTracker(NBTSizeTracker.DEFAULT_MAX_SIZE));
    }

    public static NBTTagCompound read(DataInput p_152456_0_, NBTSizeTracker p_152456_1_) throws IOException
    {
        try
        {
            NBTBase nbtbase = func_152455_a(p_152456_0_, p_152456_1_);

            if (nbtbase instanceof NBTTagCompound)
            {
                return (NBTTagCompound)nbtbase;
            }
            else
            {
                throw new IOException("Root tag must be a named compound tag");
            }
        }
        catch (NBTSizeTracker.NBTSizeLimitException exception)
        {
            throw new IOException(exception.getMessage(), exception);
        }
    }

    public static void write(NBTTagCompound p_74800_0_, DataOutput p_74800_1_) throws IOException
    {
        writeTag(p_74800_0_, p_74800_1_);
    }

    private static void writeTag(NBTBase p_150663_0_, DataOutput p_150663_1_) throws IOException
    {
        p_150663_1_.writeByte(p_150663_0_.getId());

        if (p_150663_0_.getId() != 0)
        {
            p_150663_1_.writeUTF("");
            p_150663_0_.write(p_150663_1_);
        }
    }

    private static NBTBase func_152455_a(DataInput p_152455_0_, NBTSizeTracker p_152455_2_) throws IOException
    {
        byte b0 = p_152455_0_.readByte();

        if (b0 == 0)
        {
            return new NBTTagEnd();
        }
        else
        {
            p_152455_2_.read(8L);
            String name = p_152455_2_.readString(p_152455_0_, 288L);
            NBTBase nbtbase = NBTBase.createNewByType(b0);

            if (nbtbase == null)
            {
                throw new IOException("Unknown NBT tag type " + b0 + " at root");
            }

            try
            {
                nbtbase.read(p_152455_0_, 0, p_152455_2_);
                return nbtbase;
            }
            catch (IOException ioexception)
            {
                throw new IOException("Failed to read root NBT tag '" + name + "' (type " + b0 + ")", ioexception);
            }
        }
    }
}
