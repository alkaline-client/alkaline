package alkaline.compare;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import javax.imageio.ImageIO;
import javax.imageio.stream.FileImageInputStream;
import javax.imageio.stream.ImageInputStream;

final class PngImages {
    private static final byte[] SIGNATURE = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
    private static final int MAX_DIMENSION = 16_384;
    private static final long MAX_PIXELS = 16_777_216;
    private static final long MAX_FILE_BYTES = 128L * 1024 * 1024;
    private static final long MAX_ESTIMATED_PEAK_BYTES = 256L * 1024 * 1024;
    private static final long ESTIMATED_BYTES_PER_PIXEL = 24;
    private static final long ESTIMATED_ROW_SCRATCH_BYTES = 64;
    private static final long FIXED_MEMORY_RESERVE_BYTES = 1024L * 1024;

    private PngImages() {
    }

    static ValidatedPng open(Path path) throws IOException {
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            throw new IOException("Capture is not a readable regular file: " + path);
        }
        FileImageInputStream stream = new FileImageInputStream(path.toFile());
        try {
            long fileSize = stream.length();
            PngHeader header = validate(stream, fileSize);
            stream.seek(0);
            return new ValidatedPng(path, stream, header.width(), header.height());
        } catch (IOException | RuntimeException | Error failure) {
            try {
                stream.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    static void validateDimensions(int width, int height) throws IOException {
        if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION) {
            throw new IOException("PNG dimensions must be positive and no larger than 16,384 on either side.");
        }
        long pixels = (long) width * height;
        if (pixels > MAX_PIXELS) {
            throw new IOException("PNG dimensions must contain no more than 16,777,216 pixels.");
        }
        long estimatedPeak = FIXED_MEMORY_RESERVE_BYTES + pixels * ESTIMATED_BYTES_PER_PIXEL
                + (long) width * ESTIMATED_ROW_SCRATCH_BYTES;
        if (estimatedPeak > MAX_ESTIMATED_PEAK_BYTES) {
            throw new IOException("PNG dimensions exceed the 256 MiB estimated peak-memory budget.");
        }
    }

    private static PngHeader validate(ImageInputStream input, long fileSize) throws IOException {
        if (fileSize < 0 || fileSize > MAX_FILE_BYTES) {
            throw new IOException("PNG exceeds the 128 MiB input limit.");
        }
        if (!Arrays.equals(readBytes(input, 8), SIGNATURE)) {
            throw new IOException("Input must be a PNG capture.");
        }

        long consumed = 8;
        boolean headerSeen = false;
        boolean paletteSeen = false;
        boolean transparencySeen = false;
        boolean imageDataSeen = false;
        boolean imageDataEnded = false;
        int paletteEntries = 0;
        boolean gammaSeen = false;
        boolean chromaticitiesSeen = false;
        boolean srgbSeen = false;
        boolean iccProfileSeen = false;
        boolean significantBitsSeen = false;
        boolean physicalDimensionsSeen = false;
        boolean backgroundSeen = false;
        boolean histogramSeen = false;
        boolean timestampSeen = false;
        PngHeader header = null;
        PngDataVerifier dataVerifier = null;
        byte[] buffer = new byte[8192];

        try (InflaterOwner inflaterOwner = new InflaterOwner()) {
            while (true) {
                if (fileSize - consumed < 12) {
                    throw new IOException("Incomplete PNG: missing chunk or IEND.");
                }
                long length = Integer.toUnsignedLong(input.readInt());
                byte[] typeBytes = readBytes(input, 4);
                String type = new String(typeBytes, java.nio.charset.StandardCharsets.US_ASCII);
                if (length > Integer.MAX_VALUE || length > fileSize - consumed - 12) {
                    throw new IOException("Incomplete or oversized PNG chunk: " + type);
                }
                validateChunkType(typeBytes, type);
                if (!headerSeen && !type.equals("IHDR")) {
                    throw new IOException("PNG must start with IHDR.");
                }
                if (headerSeen && type.equals("IHDR")) {
                    throw new IOException("PNG must contain exactly one IHDR chunk.");
                }
                if (type.equals("acTL") || type.equals("fcTL") || type.equals("fdAT")) {
                    throw new IOException("Animated PNG is not a single-frame capture.");
                }
                boolean isIdat = type.equals("IDAT");
                if (imageDataSeen && !isIdat) {
                    imageDataEnded = true;
                }
                if (isIdat && imageDataEnded) {
                    throw new IOException("PNG IDAT chunks must be consecutive.");
                }
                if (!type.equals("IHDR") && !type.equals("PLTE") && !type.equals("IDAT")
                        && !type.equals("IEND") && !isKnownAncillaryChunk(typeBytes)) {
                    throw new IOException("Unknown critical PNG chunk: " + type);
                }

                CRC32 checksum = new CRC32();
                checksum.update(typeBytes);
                if (type.equals("IHDR")) {
                    if (headerSeen || length != 13) {
                        throw new IOException("Invalid PNG header.");
                    }
                    byte[] data = readChunkBytes(input, length, checksum);
                    ByteBufferView values = new ByteBufferView(data);
                    int width = values.readInt();
                    int height = values.readInt();
                    int bitDepth = values.readUnsignedByte();
                    int colorType = values.readUnsignedByte();
                    int compressionMethod = values.readUnsignedByte();
                    int filterMethod = values.readUnsignedByte();
                    int interlaceMethod = values.readUnsignedByte();
                    validateDimensions(width, height);
                    validateFormat(bitDepth, colorType, compressionMethod, filterMethod, interlaceMethod);
                    header = new PngHeader(width, height, bitDepth, colorType, interlaceMethod);
                    dataVerifier = new PngDataVerifier(inflaterOwner.inflater(), header);
                    headerSeen = true;
                } else if (type.equals("PLTE")) {
                    if (transparencySeen || paletteSeen || imageDataSeen || header == null || header.colorType() == 0
                            || header.colorType() == 4 || length < 3 || length > 768 || length % 3 != 0) {
                        throw new IOException("Invalid or misplaced PNG PLTE chunk.");
                    }
                    paletteEntries = (int) length / 3;
                    if (header.colorType() == 3 && paletteEntries > (1 << header.bitDepth())) {
                        throw new IOException("PNG palette contains more entries than its bit depth allows.");
                    }
                    byte[] palette = readChunkBytes(input, length, checksum);
                    paletteSeen = true;
                } else if (type.equals("iCCP")) {
                    if (iccProfileSeen || imageDataSeen || paletteSeen || gammaSeen || chromaticitiesSeen
                            || srgbSeen || length < 4 || length > 1_048_576) {
                        throw new IOException("Invalid or misplaced PNG iCCP chunk.");
                    }
                    byte[] profile = readChunkBytes(input, length, checksum);
                    int keywordEnd = 0;
                    while (keywordEnd < profile.length && profile[keywordEnd] != 0) {
                        keywordEnd++;
                    }
                    if (keywordEnd < 1 || keywordEnd > 79 || keywordEnd + 2 >= profile.length
                            || profile[keywordEnd + 1] != 0) {
                        throw new IOException("PNG iCCP metadata contains an invalid profile header.");
                    }
                    long profileSize = completeZlibLength(profile, keywordEnd + 2);
                    if (profileSize < 132 || profileSize > 4L * 1024 * 1024) {
                        throw new IOException("PNG iCCP profile size is outside the supported profile bounds.");
                    }
                    iccProfileSeen = true;
                } else if (type.equals("gAMA")) {
                    if (gammaSeen || imageDataSeen || srgbSeen || iccProfileSeen || length != 4) {
                        throw new IOException("Invalid or misplaced PNG gAMA chunk.");
                    }
                    byte[] data = readChunkBytes(input, length, checksum);
                    if (readUnsignedInt(data, 0) == 0) {
                        throw new IOException("PNG gAMA value must be nonzero.");
                    }
                    gammaSeen = true;
                } else if (type.equals("cHRM")) {
                    if (chromaticitiesSeen || imageDataSeen || gammaSeen || srgbSeen || iccProfileSeen
                            || length != 32) {
                        throw new IOException("Invalid or misplaced PNG cHRM chunk.");
                    }
                    byte[] data = readChunkBytes(input, length, checksum);
                    for (int offset = 0; offset < data.length; offset += 4) {
                        if (readUnsignedInt(data, offset) > 100_000) {
                            throw new IOException("PNG cHRM coordinate exceeds its legal range.");
                        }
                    }
                    chromaticitiesSeen = true;
                } else if (type.equals("sRGB")) {
                    if (srgbSeen || iccProfileSeen || gammaSeen || imageDataSeen || length != 1) {
                        throw new IOException("Invalid or misplaced PNG sRGB chunk.");
                    }
                    byte[] data = readChunkBytes(input, length, checksum);
                    if (Byte.toUnsignedInt(data[0]) > 3) {
                        throw new IOException("Invalid PNG sRGB rendering intent.");
                    }
                    srgbSeen = true;
                } else if (type.equals("sBIT")) {
                    int expectedLength = header == null ? -1 : switch (header.colorType()) {
                        case 0 -> 1;
                        case 2 -> 3;
                        case 3 -> 3;
                        case 4 -> 2;
                        case 6 -> 4;
                        default -> -1;
                    };
                    if (significantBitsSeen || imageDataSeen || expectedLength < 0 || length != expectedLength) {
                        throw new IOException("Invalid or misplaced PNG sBIT chunk.");
                    }
                    byte[] data = readChunkBytes(input, length, checksum);
                    int maximumBits = header.bitDepth() == 8 ? 8 : 8;
                    for (byte value : data) {
                        int significantBits = Byte.toUnsignedInt(value);
                        if (significantBits == 0 || significantBits > maximumBits) {
                            throw new IOException("PNG sBIT value is outside the supported bit-depth range.");
                        }
                    }
                    significantBitsSeen = true;
                } else if (type.equals("pHYs")) {
                    if (physicalDimensionsSeen || imageDataSeen || length != 9) {
                        throw new IOException("Invalid or misplaced PNG pHYs chunk.");
                    }
                    byte[] data = readChunkBytes(input, length, checksum);
                    if (Byte.toUnsignedInt(data[8]) > 1) {
                        throw new IOException("Invalid PNG pHYs unit specifier.");
                    }
                    physicalDimensionsSeen = true;
                } else if (type.equals("bKGD")) {
                    int expectedLength = header == null ? -1 : switch (header.colorType()) {
                        case 0, 4 -> 2;
                        case 2, 6 -> 6;
                        case 3 -> 1;
                        default -> -1;
                    };
                    if (backgroundSeen || imageDataSeen || expectedLength < 0 || length != expectedLength) {
                        throw new IOException("Invalid or misplaced PNG bKGD chunk.");
                    }
                    byte[] data = readChunkBytes(input, length, checksum);
                    if (header.colorType() == 3 && (!paletteSeen || Byte.toUnsignedInt(data[0]) >= paletteEntries)) {
                        throw new IOException("PNG bKGD references a missing palette entry.");
                    }
                    backgroundSeen = true;
                } else if (type.equals("hIST")) {
                    if (!paletteSeen || !transparencySeen || header == null || header.colorType() != 3
                            || imageDataSeen || length != paletteEntries * 2L) {
                        throw new IOException("Invalid or misplaced PNG hIST chunk.");
                    }
                    readChunkBytes(input, length, checksum);
                    histogramSeen = true;
                } else if (type.equals("tIME")) {
                    if (timestampSeen || imageDataSeen || length != 7) {
                        throw new IOException("Invalid or misplaced PNG tIME chunk.");
                    }
                    byte[] data = readChunkBytes(input, length, checksum);
                    int month = Byte.toUnsignedInt(data[2]);
                    int day = Byte.toUnsignedInt(data[3]);
                    int hour = Byte.toUnsignedInt(data[4]);
                    int minute = Byte.toUnsignedInt(data[5]);
                    int second = Byte.toUnsignedInt(data[6]);
                    if (month < 1 || month > 12 || day < 1 || day > 31
                            || hour > 23 || minute > 59 || second > 60) {
                        throw new IOException("Invalid PNG tIME value.");
                    }
                    timestampSeen = true;
                } else if (type.equals("tRNS")) {
                    if (transparencySeen || imageDataSeen || header == null || header.colorType() == 4
                            || header.colorType() == 6) {
                        throw new IOException("Invalid or misplaced PNG tRNS chunk.");
                    }
                    if (length > 768) {
                        throw new IOException("PNG transparency chunk is too large.");
                    }
                    byte[] transparency = readChunkBytes(input, length, checksum);
                    validateTransparency(header, paletteSeen, paletteEntries, transparency);
                    transparencySeen = true;
                } else if (type.equals("sPLT")) {
                    if (imageDataSeen || length < 9) {
                        throw new IOException("Invalid or misplaced PNG sPLT chunk.");
                    }
                    byte[] data = readChunkBytes(input, length, checksum);
                    int keywordEnd = 0;
                    while (keywordEnd < data.length && data[keywordEnd] != 0) {
                        keywordEnd++;
                    }
                    if (keywordEnd < 1 || keywordEnd > 79 || keywordEnd + 2 >= data.length) {
                        throw new IOException("Invalid PNG suggested palette name.");
                    }
                    int sampleDepth = Byte.toUnsignedInt(data[keywordEnd + 1]);
                    int entryBytes = sampleDepth == 8 ? 6 : sampleDepth == 16 ? 10 : 0;
                    int entryDataLength = data.length - keywordEnd - 2;
                    if (entryBytes == 0 || entryDataLength < entryBytes || entryDataLength % entryBytes != 0) {
                        throw new IOException("Invalid PNG suggested palette entries.");
                    }
                } else if (type.equals("eXIf")) {
                    if (imageDataSeen || length > 1_048_576) {
                        throw new IOException("Invalid or misplaced PNG eXIf chunk.");
                    }
                    readAndDiscard(input, length, checksum, buffer);
                } else if (type.equals("IDAT")) {
                    if (header == null || (header.colorType() == 3 && !paletteSeen)) {
                        throw new IOException("PNG image data is missing its required header or palette.");
                    }
                    readImageData(input, length, checksum, buffer, dataVerifier);
                    imageDataSeen = true;
                } else if (type.equals("IEND")) {
                    if (length != 0 || !imageDataSeen || (header.colorType() == 3 && !paletteSeen)) {
                        throw new IOException("Invalid PNG end or missing image data.");
                    }
                    if (checksum.getValue() != Integer.toUnsignedLong(input.readInt())) {
                        throw new IOException("Invalid PNG checksum: " + type);
                    }
                    consumed += 12;
                    if (consumed != fileSize) {
                        throw new IOException("PNG contains data after IEND.");
                    }
                    dataVerifier.finish();
                    return header;
                } else {
                    readAndDiscard(input, length, checksum, buffer);
                }

                if (checksum.getValue() != Integer.toUnsignedLong(input.readInt())) {
                    throw new IOException("Invalid PNG checksum: " + type);
                }
                consumed += length + 12;
            }
        }
    }

    private static void validateChunkType(byte[] typeBytes, String type) throws IOException {
        for (byte value : typeBytes) {
            int character = Byte.toUnsignedInt(value);
            if (!((character >= 'A' && character <= 'Z') || (character >= 'a' && character <= 'z'))) {
                throw new IOException("Invalid PNG chunk type: " + type);
            }
        }
        if ((typeBytes[2] & 0x20) != 0) {
            throw new IOException("PNG chunk type has an invalid reserved bit: " + type);
        }
    }

    private static boolean isKnownAncillaryChunk(byte[] typeBytes) {
        return (typeBytes[0] & 0x20) != 0;
    }

    private static void validateFormat(int bitDepth, int colorType, int compressionMethod,
                                       int filterMethod, int interlaceMethod) throws IOException {
        if (bitDepth > 8) {
            throw new IOException("Use 8-bit captures; higher bit depths would lose precision.");
        }
        boolean validDepth = switch (colorType) {
            case 0, 3 -> bitDepth == 1 || bitDepth == 2 || bitDepth == 4 || bitDepth == 8;
            case 2, 4, 6 -> bitDepth == 8;
            default -> false;
        };
        if (!validDepth) {
            throw new IOException("PNG has an unsupported color type or bit-depth combination.");
        }
        if (compressionMethod != 0 || filterMethod != 0 || (interlaceMethod != 0 && interlaceMethod != 1)) {
            throw new IOException("PNG uses an unsupported compression, filter, or interlace method.");
        }
    }

    private static void validateTransparency(PngHeader header, boolean paletteSeen, int paletteEntries,
                                             byte[] transparency) throws IOException {
        switch (header.colorType()) {
            case 0 -> {
                if (transparency.length != 2 || unsignedShort(transparency, 0) >= (1 << header.bitDepth())) {
                    throw new IOException("Invalid grayscale PNG transparency chunk.");
                }
            }
            case 2 -> {
                if (transparency.length != 6) {
                    throw new IOException("Invalid truecolor PNG transparency chunk.");
                }
                int maximum = (1 << header.bitDepth()) - 1;
                for (int offset = 0; offset < transparency.length; offset += 2) {
                    if (unsignedShort(transparency, offset) > maximum) {
                        throw new IOException("Truecolor PNG transparency value exceeds its bit depth.");
                    }
                }
            }
            case 3 -> {
                if (!paletteSeen || transparency.length == 0 || transparency.length > paletteEntries) {
                    throw new IOException("Invalid indexed PNG transparency chunk.");
                }
            }
            default -> throw new IOException("PNG color type does not permit a tRNS chunk.");
        }
    }

    private static long completeZlibLength(byte[] compressed, int offset) throws IOException {
        Inflater inflater = new Inflater();
        byte[] decoded = new byte[1024];
        long total = 0;
        try {
            inflater.setInput(compressed, offset, compressed.length - offset);
            while (!inflater.finished()) {
                int count;
                try {
                    count = inflater.inflate(decoded);
                } catch (DataFormatException failure) {
                    throw new IOException("PNG iCCP profile contains invalid compressed data.", failure);
                }
                total += count;
                if (total > 4L * 1024 * 1024) {
                    throw new IOException("PNG iCCP profile expands beyond the 4 MiB profile limit.");
                }
                if (count == 0) {
                    if (inflater.needsDictionary() || inflater.needsInput()) {
                        return -1;
                    }
                    throw new IOException("PNG iCCP profile decompression made no progress.");
                }
            }
            return inflater.getRemaining() == 0 ? total : -1;
        } finally {
            inflater.end();
        }
    }

    private static long readUnsignedInt(byte[] data, int offset) {
        return (Integer.toUnsignedLong(Byte.toUnsignedInt(data[offset]) << 24
                | Byte.toUnsignedInt(data[offset + 1]) << 16
                | Byte.toUnsignedInt(data[offset + 2]) << 8
                | Byte.toUnsignedInt(data[offset + 3])));
    }

    private static int unsignedShort(byte[] data, int offset) {
        return (Byte.toUnsignedInt(data[offset]) << 8) | Byte.toUnsignedInt(data[offset + 1]);
    }

    private static void readImageData(ImageInputStream input, long length, CRC32 checksum, byte[] buffer,
                                      PngDataVerifier verifier) throws IOException {
        long remaining = length;
        while (remaining > 0) {
            int count = (int) Math.min(remaining, buffer.length);
            input.readFully(buffer, 0, count);
            checksum.update(buffer, 0, count);
            verifier.accept(buffer, count);
            remaining -= count;
        }
    }

    private static byte[] readChunkBytes(ImageInputStream input, long length, CRC32 checksum) throws IOException {
        byte[] data = new byte[(int) length];
        input.readFully(data);
        checksum.update(data);
        return data;
    }

    private static void readAndDiscard(ImageInputStream input, long length, CRC32 checksum, byte[] buffer)
            throws IOException {
        long remaining = length;
        while (remaining > 0) {
            int count = (int) Math.min(remaining, buffer.length);
            input.readFully(buffer, 0, count);
            checksum.update(buffer, 0, count);
            remaining -= count;
        }
    }

    private static byte[] readBytes(ImageInputStream input, int count) throws IOException {
        byte[] data = new byte[count];
        input.readFully(data);
        return data;
    }

    private static final class ByteBufferView {
        private final byte[] data;
        private int offset;

        private ByteBufferView(byte[] data) {
            this.data = data;
        }

        int readInt() {
            int value = (Byte.toUnsignedInt(data[offset]) << 24)
                    | (Byte.toUnsignedInt(data[offset + 1]) << 16)
                    | (Byte.toUnsignedInt(data[offset + 2]) << 8)
                    | Byte.toUnsignedInt(data[offset + 3]);
            offset += 4;
            return value;
        }

        int readUnsignedByte() {
            return Byte.toUnsignedInt(data[offset++]);
        }
    }

    private record PngHeader(int width, int height, int bitDepth, int colorType, int interlaceMethod) {
        int channels() {
            return switch (colorType) {
                case 0, 3 -> 1;
                case 2 -> 3;
                case 4 -> 2;
                case 6 -> 4;
                default -> throw new IllegalStateException("Unsupported PNG color type.");
            };
        }
    }

    private static final class InflaterOwner implements AutoCloseable {
        private final Inflater inflater = new Inflater();

        Inflater inflater() {
            return inflater;
        }

        @Override
        public void close() {
            inflater.end();
        }
    }

    private static final class PngDataVerifier {
        private final Inflater inflater;
        private final ScanlineVerifier scanlines;
        private final byte[] decoded = new byte[8192];
        private boolean finished;

        private PngDataVerifier(Inflater inflater, PngHeader header) {
            this.inflater = inflater;
            this.scanlines = new ScanlineVerifier(header);
        }

        void accept(byte[] compressed, int length) throws IOException {
            if (length == 0) {
                return;
            }
            if (finished) {
                throw new IOException("PNG contains compressed data after the zlib stream.");
            }
            inflater.setInput(compressed, 0, length);
            while (true) {
                int count;
                try {
                    count = inflater.inflate(decoded);
                } catch (DataFormatException failure) {
                    throw new IOException("PNG image data contains an invalid zlib stream.", failure);
                }
                if (count > 0) {
                    scanlines.accept(decoded, count);
                }
                if (inflater.finished()) {
                    finished = true;
                    if (inflater.getRemaining() != 0) {
                        throw new IOException("PNG contains trailing bytes after its zlib stream.");
                    }
                    return;
                }
                if (inflater.needsDictionary()) {
                    throw new IOException("PNG zlib stream requires an unsupported dictionary.");
                }
                if (inflater.needsInput()) {
                    return;
                }
                if (count == 0) {
                    throw new IOException("PNG zlib stream made no progress.");
                }
            }
        }

        void finish() throws IOException {
            if (!finished) {
                throw new IOException("PNG zlib stream is incomplete.");
            }
            scanlines.finish();
        }
    }

    private static final class ScanlineVerifier {
        private static final int[] ADAM7_X_START = {0, 4, 0, 2, 0, 1, 0};
        private static final int[] ADAM7_Y_START = {0, 0, 4, 0, 2, 0, 1};
        private static final int[] ADAM7_X_STEP = {8, 8, 4, 4, 2, 2, 1};
        private static final int[] ADAM7_Y_STEP = {8, 8, 8, 4, 4, 2, 2};

        private final PngHeader header;
        private final int passCount;
        private int passIndex;
        private long rowsRemaining;
        private long bytesRemainingInRow;
        private long bytesPerRow;
        private boolean expectingFilter;
        private boolean complete;

        private ScanlineVerifier(PngHeader header) {
            this.header = header;
            this.passCount = header.interlaceMethod() == 0 ? 1 : 7;
            advancePass();
        }

        void accept(byte[] data, int length) throws IOException {
            int offset = 0;
            while (offset < length) {
                if (complete) {
                    throw new IOException("PNG zlib stream contains excess scanline data.");
                }
                if (expectingFilter) {
                    int filter = Byte.toUnsignedInt(data[offset++]);
                    if (filter > 4) {
                        throw new IOException("PNG scanline uses an invalid filter type.");
                    }
                    expectingFilter = false;
                    bytesRemainingInRow--;
                    if (offset == length && bytesRemainingInRow == 0) {
                        advanceRow();
                    }
                    continue;
                }
                int count = (int) Math.min(length - offset, bytesRemainingInRow);
                offset += count;
                bytesRemainingInRow -= count;
                if (bytesRemainingInRow == 0) {
                    advanceRow();
                }
            }
        }

        void finish() throws IOException {
            if (!complete) {
                throw new IOException("PNG zlib stream does not contain complete scanline data.");
            }
        }

        private void advanceRow() {
            rowsRemaining--;
            if (rowsRemaining > 0) {
                beginRow();
            } else {
                advancePass();
            }
        }

        private void beginRow() {
            bytesRemainingInRow = bytesPerRow + 1;
            expectingFilter = true;
        }

        private void advancePass() {
            while (passIndex < passCount) {
                int pass = passIndex++;
                int xStart = passCount == 1 ? 0 : ADAM7_X_START[pass];
                int yStart = passCount == 1 ? 0 : ADAM7_Y_START[pass];
                int xStep = passCount == 1 ? 1 : ADAM7_X_STEP[pass];
                int yStep = passCount == 1 ? 1 : ADAM7_Y_STEP[pass];
                long passWidth = passSize(header.width(), xStart, xStep);
                long passHeight = passSize(header.height(), yStart, yStep);
                if (passWidth == 0 || passHeight == 0) {
                    continue;
                }
                bytesPerRow = (passWidth * header.channels() * header.bitDepth() + 7) / 8;
                rowsRemaining = passHeight;
                beginRow();
                return;
            }
            complete = true;
            rowsRemaining = 0;
            bytesRemainingInRow = 0;
        }

        private static long passSize(int extent, int start, int step) {
            return extent <= start ? 0 : (extent - start + (long) step - 1) / step;
        }
    }

    static final class ValidatedPng implements AutoCloseable {
        private final Path path;
        private final ImageInputStream stream;
        private final int width;
        private final int height;

        private ValidatedPng(Path path, ImageInputStream stream, int width, int height) {
            this.path = path;
            this.stream = stream;
            this.width = width;
            this.height = height;
        }

        int width() {
            return width;
        }

        int height() {
            return height;
        }

        BufferedImage read() throws IOException {
            var readers = ImageIO.getImageReadersByFormatName("PNG");
            if (!readers.hasNext()) {
                throw new IOException("PNG reader unavailable.");
            }
            var reader = readers.next();
            try {
                reader.addIIOReadWarningListener((source, warning) -> {
                    throw new IllegalArgumentException("PNG decoder warning: " + warning);
                });
                reader.setInput(stream, false, true);
                BufferedImage image = reader.read(0);
                if (image == null) {
                    throw new IOException("PNG contains no image: " + path);
                }
                if (image.getWidth() != width || image.getHeight() != height) {
                    throw new IOException("PNG decoder dimensions differ from its validated header: " + path);
                }
                return image;
            } catch (IllegalArgumentException failure) {
                throw new IOException("Invalid PNG: " + path + ": " + failure.getMessage(), failure);
            } finally {
                reader.dispose();
            }
        }

        @Override
        public void close() throws IOException {
            stream.close();
        }
    }
}
