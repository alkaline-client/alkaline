package alkaline.game.data.nbt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NBTTagRoundTripTest
{
    @TempDir
    Path tempDirectory;

    @Test
    void scalarTagsRoundTripWithLegacyValues()
            throws IOException
    {
        NBTTagCompound root = new NBTTagCompound();
        root.setByte("byte", (byte)-7);
        root.setShort("short", (short)1234);
        root.setInteger("int", 123456789);
        root.setLong("long", 123456789012345L);
        root.setFloat("float", -1.25F);
        root.setDouble("double", 9.75D);
        root.setString("string", "Alkaline \"NBT\"");
        root.setBoolean("boolean", true);
        root.setByteArray("bytes", new byte[] {0, -1, 7});
        root.setIntArray("ints", new int[] {Integer.MIN_VALUE, 0, Integer.MAX_VALUE});

        NBTTagCompound decoded = roundTrip(root);

        assertEquals(root, decoded);
        assertEquals((byte)-7, decoded.getByte("byte"));
        assertEquals((short)1234, decoded.getShort("short"));
        assertEquals(123456789, decoded.getInteger("int"));
        assertEquals(123456789012345L, decoded.getLong("long"));
        assertEquals(-1.25F, decoded.getFloat("float"));
        assertEquals(9.75D, decoded.getDouble("double"));
        assertEquals("Alkaline \"NBT\"", decoded.getString("string"));
        assertTrue(decoded.getBoolean("boolean"));
        assertArrayEquals(new byte[] {0, -1, 7}, decoded.getByteArray("bytes"));
        assertArrayEquals(new int[] {Integer.MIN_VALUE, 0, Integer.MAX_VALUE}, decoded.getIntArray("ints"));
    }

    @Test
    void nestedListAndCompoundRoundTrip()
            throws IOException
    {
        NBTTagCompound player = new NBTTagCompound();
        player.setString("name", "Ada");
        player.setInteger("level", 42);

        NBTTagList inventory = new NBTTagList();
        NBTTagCompound item = new NBTTagCompound();
        item.setString("id", "minecraft:stone");
        item.setByte("count", (byte)64);
        inventory.appendTag(item);

        NBTTagCompound root = new NBTTagCompound();
        root.setTag("player", player);
        root.setTag("inventory", inventory);

        NBTTagCompound decoded = roundTrip(root);
        assertEquals(root, decoded);
        assertEquals("Ada", decoded.getCompoundTag("player").getString("name"));
        assertEquals(42, decoded.getCompoundTag("player").getInteger("level"));
        assertEquals(1, decoded.getTagList("inventory", 10).tagCount());
        assertEquals("minecraft:stone", decoded.getTagList("inventory", 10)
                .getCompoundTagAt(0).getString("id"));
        assertEquals((byte)64, decoded.getTagList("inventory", 10)
                .getCompoundTagAt(0).getByte("count"));
    }

    @Test
    void gzipAndFilePersistenceRoundTrip()
            throws IOException
    {
        NBTTagCompound original = new NBTTagCompound();
        original.setString("world", "legacy");
        original.setInteger("seed", -987654321);

        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        CompressedStreamTools.writeCompressed(original, compressed);
        NBTTagCompound fromGzip = CompressedStreamTools.readCompressed(
                new ByteArrayInputStream(compressed.toByteArray()));
        assertEquals(original, fromGzip);

        Path file = tempDirectory.resolve("level.dat");
        CompressedStreamTools.safeWrite(original, file.toFile());
        assertEquals(original, CompressedStreamTools.read(file.toFile()));
        assertFalse(Path.of(file.toString() + "_tmp").toFile().exists());
        assertNull(CompressedStreamTools.read(tempDirectory.resolve("missing.dat").toFile()));

        NBTTagCompound replacement = new NBTTagCompound();
        replacement.setString("world", "replaced");
        CompressedStreamTools.safeWrite(replacement, file.toFile());
        assertEquals(replacement, CompressedStreamTools.read(file.toFile()));

        Path relativeTarget = Path.of("").toAbsolutePath().relativize(tempDirectory.resolve("relative.dat"));
        CompressedStreamTools.safeWrite(replacement, relativeTarget.toFile());
        assertEquals(replacement, CompressedStreamTools.read(relativeTarget.toFile()));
    }

    @Test
    void failedSafeWritePreservesExistingTarget() throws IOException
    {
        Path file = tempDirectory.resolve("preserved.dat");
        Files.write(file, new byte[] {1, 2, 3, 4});
        byte[] originalBytes = Files.readAllBytes(file);

        NBTTagCompound root = new NBTTagCompound();
        root.setTag("failing", new FailingChild());

        IOException writeFailure = assertThrows(IOException.class,
                () -> CompressedStreamTools.safeWrite(root, file.toFile()));
        assertEquals("injected write failure", writeFailure.getMessage());
        assertArrayEquals(originalBytes, Files.readAllBytes(file));
        try (var paths = Files.list(tempDirectory))
        {
            assertEquals(0L, paths.filter(path -> path.getFileName().toString().startsWith("nbt-preserved.dat-"))
                    .count());
        }
    }

    private static final class FailingChild extends NBTTagCompound
    {
        @Override
        void write(java.io.DataOutput output) throws IOException
        {
            throw new IOException("injected write failure");
        }
    }

    @Test
    void copyIsDeepAndCompoundMergeIsRecursive()
    {
        NBTTagCompound originalChild = new NBTTagCompound();
        originalChild.setInteger("kept", 1);
        NBTTagList originalList = new NBTTagList();
        originalList.appendTag(new NBTTagString("before"));

        NBTTagCompound original = new NBTTagCompound();
        original.setTag("child", originalChild);
        original.setTag("list", originalList);

        NBTTagCompound copy = (NBTTagCompound)original.copy();
        assertEquals(original, copy);
        assertNotSame(original.getCompoundTag("child"), copy.getCompoundTag("child"));
        assertNotSame(original.getTagList("list", 8).get(0), copy.getTagList("list", 8).get(0));
        copy.getCompoundTag("child").setInteger("kept", 2);
        assertEquals(1, original.getCompoundTag("child").getInteger("kept"));

        NBTTagCompound mergeChild = new NBTTagCompound();
        mergeChild.setInteger("added", 3);
        NBTTagCompound mergeSource = new NBTTagCompound();
        mergeSource.setTag("child", mergeChild);
        mergeSource.setString("replacement", "source");
        original.setString("replacement", "target");
        original.merge(mergeSource);

        assertEquals(1, original.getCompoundTag("child").getInteger("kept"));
        assertEquals(3, original.getCompoundTag("child").getInteger("added"));
        assertEquals("source", original.getString("replacement"));
        mergeChild.setInteger("added", 99);
        assertEquals(3, original.getCompoundTag("child").getInteger("added"));
    }

    @Test
    void boundedNbtReadsRejectOversizedLengthsBeforeAllocation() throws IOException
    {
        ByteArrayOutputStream oversizedArrayBytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(oversizedArrayBytes))
        {
            output.writeByte(10);
            output.writeUTF("");
            output.writeByte(7);
            output.writeUTF("bytes");
            output.writeInt(Integer.MAX_VALUE);
        }
        IOException oversizedArray = assertThrows(IOException.class, () -> CompressedStreamTools.read(
                new DataInputStream(new ByteArrayInputStream(oversizedArrayBytes.toByteArray()))));
        assertTrue(causeChainContains(oversizedArray, "too big"));

        ByteArrayOutputStream oversizedStringBytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(oversizedStringBytes))
        {
            output.writeByte(10);
            output.writeUTF("");
            output.writeByte(8);
            output.writeUTF("value");
            output.writeShort(0xFFFF);
        }
        IOException oversizedString = assertThrows(IOException.class, () -> CompressedStreamTools.read(
                new DataInputStream(new ByteArrayInputStream(oversizedStringBytes.toByteArray())),
                new NBTSizeTracker(200L)));
        assertTrue(causeChainContains(oversizedString, "too big"));

        ByteArrayOutputStream oversizedIntArrayBytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(oversizedIntArrayBytes))
        {
            output.writeByte(10);
            output.writeUTF("");
            output.writeByte(11);
            output.writeUTF("ints");
            output.writeInt(Integer.MAX_VALUE);
        }
        IOException oversizedIntArray = assertThrows(IOException.class, () -> CompressedStreamTools.read(
                new DataInputStream(new ByteArrayInputStream(oversizedIntArrayBytes.toByteArray()))));
        assertTrue(causeChainContains(oversizedIntArray, "too big"));

        ByteArrayOutputStream oversizedListBytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(oversizedListBytes))
        {
            output.writeByte(10);
            output.writeUTF("");
            output.writeByte(9);
            output.writeUTF("list");
            output.writeByte(1);
            output.writeInt(Integer.MAX_VALUE);
        }
        IOException oversizedList = assertThrows(IOException.class, () -> CompressedStreamTools.read(
                new DataInputStream(new ByteArrayInputStream(oversizedListBytes.toByteArray()))));
        assertTrue(causeChainContains(oversizedList, "too big"));

        assertThrows(IOException.class, () -> CompressedStreamTools.read(
                new DataInputStream(new ByteArrayInputStream(new byte[] {10, 0, 0})), new NBTSizeTracker(1L)));
    }

    @Test
    void compressedReadsCheckGzipTrailerAndBoundDecompressedBytes() throws IOException
    {
        NBTTagCompound root = new NBTTagCompound();
        root.setString("value", "complete");
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        CompressedStreamTools.writeCompressed(root, compressed);
        byte[] corruptTrailer = compressed.toByteArray();
        corruptTrailer[corruptTrailer.length - 8] ^= 1;
        assertThrows(IOException.class, () -> CompressedStreamTools.readCompressed(
                new ByteArrayInputStream(corruptTrailer)));

        NBTTagCompound large = new NBTTagCompound();
        large.setByteArray("payload", new byte[(int)NBTSizeTracker.DEFAULT_MAX_SIZE]);
        ByteArrayOutputStream largeCompressed = new ByteArrayOutputStream();
        CompressedStreamTools.writeCompressed(large, largeCompressed);
        assertThrows(IOException.class, () -> CompressedStreamTools.readCompressed(
                new ByteArrayInputStream(largeCompressed.toByteArray())));
    }

    @Test
    void compoundEndTagsAreRejectedAndFloatingEqualityMatchesHashes()
    {
        NBTTagCompound compound = new NBTTagCompound();
        assertThrows(IllegalArgumentException.class, () -> compound.setTag("end", new NBTTagEnd()));

        NBTTagFloat nanA = new NBTTagFloat(Float.intBitsToFloat(0x7fc00001));
        NBTTagFloat nanB = new NBTTagFloat(Float.intBitsToFloat(0x7fffffff));
        assertEquals(nanA, nanB);
        assertEquals(nanA.hashCode(), nanB.hashCode());
        assertFalse(new NBTTagFloat(0.0F).equals(new NBTTagFloat(-0.0F)));

        NBTTagDouble doubleNanA = new NBTTagDouble(Double.longBitsToDouble(0x7ff8000000000001L));
        NBTTagDouble doubleNanB = new NBTTagDouble(Double.longBitsToDouble(0x7fffffffffffffffL));
        assertEquals(doubleNanA, doubleNanB);
        assertEquals(doubleNanA.hashCode(), doubleNanB.hashCode());
        assertFalse(new NBTTagDouble(0.0D).equals(new NBTTagDouble(-0.0D)));
    }

    @Test
    void malformedRootAndListEncodingsFailWithIoExceptions()
            throws IOException
    {
        assertThrows(IOException.class, () -> CompressedStreamTools.read(
                new DataInputStream(new ByteArrayInputStream(new byte[] {99, 0, 0}))));

        ByteArrayOutputStream invalidListBytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(invalidListBytes))
        {
            output.writeByte(10);
            output.writeUTF("");
            output.writeByte(9);
            output.writeUTF("list");
            output.writeByte(0);
            output.writeInt(1);
        }
        IOException invalidList = assertThrows(IOException.class, () -> CompressedStreamTools.read(
                new DataInputStream(new ByteArrayInputStream(invalidListBytes.toByteArray()))));
        assertTrue(causeChainContains(invalidList, "Missing type"));

        ByteArrayOutputStream negativeArrayBytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(negativeArrayBytes))
        {
            output.writeByte(10);
            output.writeUTF("");
            output.writeByte(7);
            output.writeUTF("bytes");
            output.writeInt(-1);
        }
        IOException negativeArray = assertThrows(IOException.class, () -> CompressedStreamTools.read(
                new DataInputStream(new ByteArrayInputStream(negativeArrayBytes.toByteArray()))));
        assertTrue(causeChainContains(negativeArray, "Negative NBT byte-array length"));
    }

    private static boolean causeChainContains(Throwable throwable, String text)
    {
        for (Throwable current = throwable; current != null; current = current.getCause())
        {
            if (current.getMessage() != null && current.getMessage().contains(text))
            {
                return true;
            }
        }
        return false;
    }

    private static NBTTagCompound roundTrip(NBTTagCompound source)
            throws IOException
    {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes))
        {
            CompressedStreamTools.write(source, output);
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())))
        {
            return CompressedStreamTools.read(input);
        }
    }
}
