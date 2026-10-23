package alkaline.compare;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScreenSpaceCheckTest {
    @TempDir
    Path directory;

    @Test
    void matchingCaptureProducesNeutralImageAndPassStatus() throws IOException {
        Path openGl = png("opengl.png", image(0x804080C0, 0x00000000));
        Path vulkan = png("vulkan.png", image(0x804080C0, 0x00000000));
        Path output = directory.resolve("result.png");
        var stdout = new ByteArrayOutputStream();

        int status = run(openGl, vulkan, output, stdout, new ByteArrayOutputStream());

        assertEquals(ScreenSpaceCheck.MATCH, status);
        String text = stdout.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertTrue(text.contains("Dimensions: 2 x 1\nDifferent pixels: 0 / 2\n"));
        assertTrue(text.contains("Result image: " + output.toAbsolutePath()));
        assertTrue(text.contains("PASS: captures match exactly."));
        BufferedImage result = ImageIO.read(output.toFile());
        assertEquals(0xFF404040, result.getRGB(0, 0));
        assertEquals(0xFF404040, result.getRGB(1, 0));
    }

    @Test
    void detectsRgbAndAlphaDifferencesAtTheExactPixel() throws IOException {
        Path openGl = png("opengl.png", image(0x804080C0, 0x00000000));
        Path vulkan = png("vulkan.png", image(0x804080C1, 0x01000000));
        Path output = directory.resolve("difference.png");
        var stdout = new ByteArrayOutputStream();

        int status = run(openGl, vulkan, output, stdout, new ByteArrayOutputStream());

        assertEquals(ScreenSpaceCheck.DIFFERENT, status);
        assertTrue(stdout.toString(StandardCharsets.UTF_8).contains("Different pixels: 2 / 2"));
        assertEquals(0xFFFF20C0, ImageIO.read(output.toFile()).getRGB(0, 0));
        assertEquals(0xFFFF20C0, ImageIO.read(output.toFile()).getRGB(1, 0));
    }

    @Test
    void dimensionMismatchProducesNoResultImage() throws IOException {
        Path openGl = png("opengl.png", image(0xFFFFFFFF));
        Path vulkan = png("vulkan.png", image(0xFFFFFFFF, 0xFFFFFFFF));
        Path output = directory.resolve("result.png");
        var stderr = new ByteArrayOutputStream();

        int status = run(openGl, vulkan, output, new ByteArrayOutputStream(), stderr);

        assertEquals(ScreenSpaceCheck.INVALID_INPUT, status);
        assertFalse(Files.exists(output));
        assertTrue(stderr.toString(StandardCharsets.UTF_8).contains("Capture dimensions differ"));
    }

    @Test
    void invalidPngProducesInputErrorWithoutOutput() throws IOException {
        Path openGl = directory.resolve("opengl.png");
        Files.writeString(openGl, "not a png");
        Path vulkan = png("vulkan.png", image(0xFFFFFFFF));
        Path output = directory.resolve("result.png");

        int status = run(openGl, vulkan, output, new ByteArrayOutputStream(), new ByteArrayOutputStream());

        assertEquals(ScreenSpaceCheck.INVALID_INPUT, status);
        assertFalse(Files.exists(output));
    }

    @Test
    void refusesToOverwriteExistingResult() throws IOException {
        Path openGl = png("opengl.png", image(0xFFFFFFFF));
        Path vulkan = png("vulkan.png", image(0xFFFFFFFF));
        Path output = png("result.png", image(0xFF123456));

        int status = run(openGl, vulkan, output, new ByteArrayOutputStream(), new ByteArrayOutputStream());

        assertEquals(ScreenSpaceCheck.INVALID_INPUT, status);
        assertEquals(0xFF123456, ImageIO.read(output.toFile()).getRGB(0, 0));
    }

    @Test
    void refusesAnOutputPathThatAliasesAnInput() throws IOException {
        Path openGl = png("opengl.png", image(0xFFFFFFFF));
        Path vulkan = png("vulkan.png", image(0xFFFFFFFF));
        Path alias = directory.resolve("subdirectory").resolve("..").resolve(openGl.getFileName());

        int status = run(openGl, vulkan, alias, new ByteArrayOutputStream(), new ByteArrayOutputStream());

        assertEquals(ScreenSpaceCheck.INVALID_INPUT, status);
        assertEquals(0xFFFFFFFF, ImageIO.read(openGl.toFile()).getRGB(0, 0));
    }

    @Test
    void locatesASingleDifferenceOnTheCorrectRowIncludingHiddenRgb() throws IOException {
        var expected = new BufferedImage(3, 2, BufferedImage.TYPE_INT_ARGB);
        var actual = new BufferedImage(3, 2, BufferedImage.TYPE_INT_ARGB);
        actual.setRGB(1, 1, 0x00000001);
        Path openGl = png("opengl.png", expected);
        Path vulkan = png("vulkan.png", actual);
        Path output = directory.resolve("mask.png");
        assertEquals(ScreenSpaceCheck.DIFFERENT,
                run(openGl, vulkan, output, new ByteArrayOutputStream(), new ByteArrayOutputStream()));
        BufferedImage result = ImageIO.read(output.toFile());
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 3; x++) {
                assertEquals(x == 1 && y == 1 ? 0xFFFF20C0 : 0xFF404040, result.getRGB(x, y));
            }
        }
    }

    @Test
    void preservesIndexedTransparencyWhenComparingPixels() throws IOException {
        byte[] palette = new byte[]{0x40, 0x20, 0x10, 1, 2, 3};
        Path openGl = directory.resolve("opengl.png");
        Path vulkan = directory.resolve("vulkan.png");
        Files.write(openGl, indexedPng(2, 1, palette, new byte[]{(byte) 0x80, 0}));
        Files.write(vulkan, indexedPng(2, 1, palette, new byte[]{0x7f, 0}));
        Path output = directory.resolve("indexed-mask.png");

        int status = run(openGl, vulkan, output, new ByteArrayOutputStream(), new ByteArrayOutputStream());

        assertEquals(ScreenSpaceCheck.DIFFERENT, status);
        assertEquals(0x80402010, ImageIO.read(openGl.toFile()).getRGB(0, 0));
        assertEquals(0xFFFF20C0, ImageIO.read(output.toFile()).getRGB(0, 0));
        assertEquals(0xFF404040, ImageIO.read(output.toFile()).getRGB(1, 0));
    }

    @Test
    void skipsAncillaryCompressedMetadataWhenReadingPixels() throws IOException {
        byte[] png = singlePixelPng(new byte[]{0, 1, 2, 3, (byte) 0xFF});
        byte[] profile = new byte[132];
        profile[3] = (byte) 132;
        System.arraycopy("acsp".getBytes(StandardCharsets.US_ASCII), 0, profile, 36, 4);
        byte[] compressedProfile = deflate(profile);
        byte[] profileChunk = new byte[3 + compressedProfile.length];
        profileChunk[0] = 'p';
        profileChunk[1] = 0;
        profileChunk[2] = 0;
        System.arraycopy(compressedProfile, 0, profileChunk, 3, compressedProfile.length);
        byte[] withMetadata = insertChunk(png, findChunk(png, "IDAT"), "iCCP", profileChunk);
        Path openGl = directory.resolve("metadata.png");
        Path vulkan = png("normal.png", image(0xFF010203));
        Files.write(openGl, withMetadata);
        Path output = directory.resolve("mask.png");

        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int status = run(openGl, vulkan, output, new ByteArrayOutputStream(), stderr);
        assertEquals(ScreenSpaceCheck.MATCH, status, stderr.toString(StandardCharsets.UTF_8));
        assertEquals(0xFF404040, ImageIO.read(output.toFile()).getRGB(0, 0));
    }

    @Test
    void rejectsDimensionLimitsThatWouldCreateVeryWideOrTallImages() throws IOException {
        Path tooWide = directory.resolve("wide.png");
        Files.write(tooWide, pngHeader(16_385, 1, 8, 6, 0));
        Path normal = png("normal.png", image(0xFF010203));
        Path output = directory.resolve("mask.png");

        assertEquals(ScreenSpaceCheck.INVALID_INPUT,
                run(tooWide, normal, output, new ByteArrayOutputStream(), new ByteArrayOutputStream()));
        assertFalse(Files.exists(output));

        Path tooTall = directory.resolve("tall.png");
        Files.write(tooTall, pngHeader(1, 16_385, 8, 6, 0));
        assertEquals(ScreenSpaceCheck.INVALID_INPUT,
                run(tooTall, normal, output, new ByteArrayOutputStream(), new ByteArrayOutputStream()));
        assertFalse(Files.exists(output));
    }

    @Test
    void rejectsDimensionsOverEstimatedPeakMemoryBudget() throws IOException {
        Path oversized = directory.resolve("memory-budget.png");
        Files.write(oversized, pngHeader(4096, 4096, 8, 6, 0));
        Path normal = png("normal.png", image(0xFF010203));

        assertEquals(ScreenSpaceCheck.INVALID_INPUT,
                run(oversized, normal, directory.resolve("mask.png"), new ByteArrayOutputStream(),
                        new ByteArrayOutputStream()));
    }

    @Test
    void rejectsIncompleteZlibAndScanlineDataEvenWithValidChunkChecksums() throws IOException {
        Path normal = png("normal.png", image(0xFF010203));
        Path output = directory.resolve("mask.png");
        byte[] complete = singlePixelPng(new byte[]{0, 1, 2, 3});
        byte[] missingZlibTrailer = complete.clone();
        int idat = findChunk(missingZlibTrailer, "IDAT");
        int dataLength = readInt(missingZlibTrailer, idat);
        byte[] shortened = new byte[missingZlibTrailer.length - 1];
        System.arraycopy(missingZlibTrailer, 0, shortened, 0, idat + 4);
        System.arraycopy(missingZlibTrailer, idat + 8, shortened, idat + 8, dataLength - 1);
        System.arraycopy(missingZlibTrailer, idat + 8 + dataLength, shortened,
                idat + 8 + dataLength - 1, missingZlibTrailer.length - (idat + 8 + dataLength));
        writeInt(shortened, idat, dataLength - 1);
        rewriteChunkCrc(shortened, idat);
        Path incompleteZlib = directory.resolve("incomplete-zlib.png");
        Files.write(incompleteZlib, shortened);

        assertEquals(ScreenSpaceCheck.INVALID_INPUT,
                run(incompleteZlib, normal, output, new ByteArrayOutputStream(), new ByteArrayOutputStream()));
        assertFalse(Files.exists(output));

        Path incompleteRows = directory.resolve("incomplete-rows.png");
        Files.write(incompleteRows, singlePixelPng(new byte[]{0, 1, 2}));
        assertEquals(ScreenSpaceCheck.INVALID_INPUT,
                run(incompleteRows, normal, output, new ByteArrayOutputStream(), new ByteArrayOutputStream()));
        assertFalse(Files.exists(output));
    }

    @Test
    void rejectsInvalidCriticalChunkAndMisorderedPaletteChunks() throws IOException {
        byte[] uncompressed = new byte[]{0, 1, 2, 3};
        byte[] unknown = insertChunk(singlePixelPng(uncompressed), 33, "ABCD", new byte[0]);
        byte[] misorderedPalette = indexedPngPaletteAfterImageData();
        Path invalid = directory.resolve("invalid-structure.png");
        Path normal = png("normal.png", image(0xFF010203));
        Path output = directory.resolve("mask.png");
        Files.write(invalid, unknown);
        assertEquals(ScreenSpaceCheck.INVALID_INPUT,
                run(invalid, normal, output, new ByteArrayOutputStream(), new ByteArrayOutputStream()));
        Files.write(invalid, misorderedPalette);
        assertEquals(ScreenSpaceCheck.INVALID_INPUT,
                run(invalid, normal, output, new ByteArrayOutputStream(), new ByteArrayOutputStream()));
        assertFalse(Files.exists(output));
    }

    @Test
    void rejectsIdatChunksSeparatedByAnotherChunk() throws IOException {
        Path invalid = directory.resolve("separated-idat.png");
        Files.write(invalid, separateIdatWithAncillary(singlePixelPng(new byte[]{0, 1, 2, 3})));
        Path normal = png("normal.png", image(0xFF010203));
        Path output = directory.resolve("mask.png");

        assertEquals(ScreenSpaceCheck.INVALID_INPUT,
                run(invalid, normal, output, new ByteArrayOutputStream(), new ByteArrayOutputStream()));
        assertFalse(Files.exists(output));
    }

    @Test
    void keepsAnAlreadyOpenedInputStableWhenItsPathIsReplaced() throws IOException {
        Path original = png("original.png", image(0xFF102030));
        Path replacement = png("replacement.png", image(0xFFFF0000));
        PngImages.ValidatedPng opened = PngImages.open(original);
        try {
            Files.delete(original);
            Files.copy(replacement, original);
            assertEquals(0xFF102030, opened.read().getRGB(0, 0));
        } finally {
            opened.close();
        }
    }

    @Test
    void failedOutputEncodingDoesNotPublishOrDeleteAnUnownedResult() throws IOException {
        Path openGl = png("opengl.png", image(0xFF102030));
        Path vulkan = png("vulkan.png", image(0xFF102030));
        Path output = directory.resolve("result.png");
        assertThrows(IOException.class, () -> ScreenSpaceCheck.writeNewPng(null, output));
        assertFalse(Files.exists(output));
        assertEquals(ScreenSpaceCheck.MATCH,
                run(openGl, vulkan, output, new ByteArrayOutputStream(), new ByteArrayOutputStream()));
    }

    @Test
    void rejectsTruncatedPngEvenWhenPixelsAreDecodable() throws IOException {
        Path openGl = png("opengl.png", image(0xFF010203));
        byte[] bytes = Files.readAllBytes(openGl);
        Files.write(openGl, java.util.Arrays.copyOf(bytes, bytes.length - 12));
        Path vulkan = png("vulkan.png", image(0xFF010203));
        Path output = directory.resolve("result.png");
        assertEquals(ScreenSpaceCheck.INVALID_INPUT,
                run(openGl, vulkan, output, new ByteArrayOutputStream(), new ByteArrayOutputStream()));
        assertFalse(Files.exists(output));
    }

    @Test
    void rejectsCorruptChecksumAndNonPngImages() throws IOException {
        Path openGl = png("opengl.png", image(0xFF010203));
        byte[] bytes = Files.readAllBytes(openGl);
        bytes[29] ^= 1;
        Files.write(openGl, bytes);
        Path vulkan = png("vulkan.png", image(0xFF010203));
        Path output = directory.resolve("result.png");
        assertEquals(ScreenSpaceCheck.INVALID_INPUT,
                run(openGl, vulkan, output, new ByteArrayOutputStream(), new ByteArrayOutputStream()));
        assertTrue(ImageIO.write(image(0xFF010203), "GIF", openGl.toFile()));
        assertEquals(ScreenSpaceCheck.INVALID_INPUT,
                run(openGl, vulkan, output, new ByteArrayOutputStream(), new ByteArrayOutputStream()));
        assertFalse(Files.exists(output));
    }

    @Test
    void rejectsSixteenBitImagesInsteadOfDroppingPrecision() throws IOException {
        var image = new BufferedImage(1, 1, BufferedImage.TYPE_USHORT_GRAY);
        Path openGl = png("opengl.png", image);
        Path vulkan = png("vulkan.png", image);
        Path output = directory.resolve("result.png");
        assertEquals(ScreenSpaceCheck.INVALID_INPUT,
                run(openGl, vulkan, output, new ByteArrayOutputStream(), new ByteArrayOutputStream()));
        assertFalse(Files.exists(output));
    }

    @Test
    void rejectsMissingInputsMissingOutputDirectoryAndSelfComparison() throws IOException {
        Path openGl = png("opengl.png", image(0xFF010203));
        Path vulkan = png("vulkan.png", image(0xFF010203));
        Path output = directory.resolve("result.png");
        assertEquals(ScreenSpaceCheck.INVALID_INPUT, run(directory.resolve("missing.png"), vulkan,
                output, new ByteArrayOutputStream(), new ByteArrayOutputStream()));
        assertEquals(ScreenSpaceCheck.INVALID_INPUT, run(openGl, vulkan,
                directory.resolve("missing/result.png"), new ByteArrayOutputStream(), new ByteArrayOutputStream()));
        assertEquals(ScreenSpaceCheck.INVALID_INPUT, run(openGl, openGl,
                output, new ByteArrayOutputStream(), new ByteArrayOutputStream()));
        assertFalse(Files.exists(output));
    }

    @Test
    void invalidArgumentsReturnInputError() {
        var stderr = new ByteArrayOutputStream();
        assertEquals(ScreenSpaceCheck.INVALID_INPUT, ScreenSpaceCheck.run(new String[0],
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(stderr)));
        assertTrue(stderr.toString(StandardCharsets.UTF_8).contains("Usage:"));
    }

    private static byte[] pngHeader(int width, int height, int bitDepth, int colorType, int interlace)
            throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes)) {
            output.write(new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10});
            var header = new ByteArrayOutputStream();
            try (var data = new DataOutputStream(header)) {
                data.writeInt(width);
                data.writeInt(height);
                data.writeByte(bitDepth);
                data.writeByte(colorType);
                data.writeByte(0);
                data.writeByte(0);
                data.writeByte(interlace);
            }
            writeChunk(output, "IHDR", header.toByteArray());
        }
        return bytes.toByteArray();
    }

    private static byte[] singlePixelPng(byte[] filteredRgba) throws IOException {
        var bytes = new ByteArrayOutputStream();
        bytes.write(pngHeader(1, 1, 8, 6, 0));
        bytes.write(chunk("IDAT", deflate(filteredRgba)));
        bytes.write(chunk("IEND", new byte[0]));
        return bytes.toByteArray();
    }

    private static byte[] indexedPngPaletteAfterImageData() throws IOException {
        var bytes = new ByteArrayOutputStream();
        bytes.write(pngHeader(1, 1, 8, 3, 0));
        bytes.write(chunk("IDAT", deflate(new byte[]{0, 0})));
        bytes.write(chunk("PLTE", new byte[]{0, 0, 0}));
        bytes.write(chunk("IEND", new byte[0]));
        return bytes.toByteArray();
    }

    private static byte[] indexedPng(int width, int height, byte[] palette, byte[] alpha) throws IOException {
        var bytes = new ByteArrayOutputStream();
        bytes.write(pngHeader(width, height, 8, 3, 0));
        bytes.write(chunk("PLTE", palette));
        bytes.write(chunk("tRNS", alpha));
        bytes.write(chunk("IDAT", deflate(new byte[]{0, 0, 1})));
        bytes.write(chunk("IEND", new byte[0]));
        return bytes.toByteArray();
    }

    private static byte[] deflate(byte[] bytes) throws IOException {
        var compressed = new ByteArrayOutputStream();
        try (var deflater = new DeflaterOutputStream(compressed)) {
            deflater.write(bytes);
        }
        return compressed.toByteArray();
    }

    private static byte[] chunk(String type, byte[] data) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes)) {
            writeChunk(output, type, data);
        }
        return bytes.toByteArray();
    }

    private static void writeChunk(DataOutputStream output, String type, byte[] data) throws IOException {
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        output.writeInt(data.length);
        output.write(typeBytes);
        output.write(data);
        output.writeInt((int) crc.getValue());
    }

    private static int findChunk(byte[] png, String soughtType) {
        int offset = 8;
        while (offset + 12 <= png.length) {
            int length = readInt(png, offset);
            String type = new String(png, offset + 4, 4, StandardCharsets.US_ASCII);
            if (type.equals(soughtType)) {
                return offset;
            }
            offset += 12 + length;
        }
        throw new IllegalArgumentException("Chunk not found: " + soughtType);
    }

    private static int readInt(byte[] bytes, int offset) {
        return (Byte.toUnsignedInt(bytes[offset]) << 24) | (Byte.toUnsignedInt(bytes[offset + 1]) << 16)
                | (Byte.toUnsignedInt(bytes[offset + 2]) << 8) | Byte.toUnsignedInt(bytes[offset + 3]);
    }

    private static void writeInt(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) (value >>> 24);
        bytes[offset + 1] = (byte) (value >>> 16);
        bytes[offset + 2] = (byte) (value >>> 8);
        bytes[offset + 3] = (byte) value;
    }

    private static void rewriteChunkCrc(byte[] png, int offset) {
        int length = readInt(png, offset);
        CRC32 crc = new CRC32();
        crc.update(png, offset + 4, 4 + length);
        writeInt(png, offset + 8 + length, (int) crc.getValue());
    }

    private static byte[] insertChunk(byte[] png, int offset, String type, byte[] data) throws IOException {
        byte[] added = chunk(type, data);
        byte[] result = new byte[png.length + added.length];
        System.arraycopy(png, 0, result, 0, offset);
        System.arraycopy(added, 0, result, offset, added.length);
        System.arraycopy(png, offset, result, offset + added.length, png.length - offset);
        return result;
    }

    private static byte[] separateIdatWithAncillary(byte[] png) throws IOException {
        int offset = findChunk(png, "IDAT");
        int length = readInt(png, offset);
        int firstLength = Math.max(1, length / 2);
        byte[] compressed = Arrays.copyOfRange(png, offset + 8, offset + 8 + length);
        var result = new ByteArrayOutputStream();
        result.write(png, 0, offset);
        result.write(chunk("IDAT", Arrays.copyOfRange(compressed, 0, firstLength)));
        result.write(chunk("tEXt", new byte[]{'x', 0, 'y'}));
        result.write(chunk("IDAT", Arrays.copyOfRange(compressed, firstLength, length)));
        result.write(png, offset + 12 + length, png.length - (offset + 12 + length));
        return result.toByteArray();
    }

    private Path png(String name, BufferedImage image) throws IOException {
        Path path = directory.resolve(name);
        assertTrue(ImageIO.write(image, "PNG", path.toFile()));
        return path;
    }

    private static BufferedImage image(int... pixels) {
        var image = new BufferedImage(pixels.length, 1, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < pixels.length; x++) {
            image.setRGB(x, 0, pixels[x]);
        }
        return image;
    }

    private static int run(Path openGl, Path vulkan, Path output, ByteArrayOutputStream stdout,
                           ByteArrayOutputStream stderr) {
        return ScreenSpaceCheck.run(new String[]{"--opengl", openGl.toString(), "--vulkan", vulkan.toString(),
                        "--output", output.toString()},
                new PrintStream(stdout), new PrintStream(stderr));
    }
}
