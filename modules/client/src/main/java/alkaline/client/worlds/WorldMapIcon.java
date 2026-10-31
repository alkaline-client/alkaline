package alkaline.client.worlds;

import alkaline.game.data.nbt.CompressedStreamTools;
import alkaline.game.data.nbt.NBTTagCompound;
import alkaline.game.data.nbt.NBTTagList;
import alkaline.game.world.MapColors;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * A picture for a save that has none. Minecraft 1.8.9 never writes a world's {@code icon.png} (later versions take
 * a screenshot), so most 1.8 saves would show a placeholder. Instead, the 64x64 blocks around the player (or the
 * spawn) are drawn the way a 1.8.9 map item draws them, at scale 0 (ItemMap.updateMapData): the top block's map
 * color, shaded by the height step from the block to the north, and water shaded by its depth. Only the Anvil
 * region files of the Overworld are read, and only the chunks the picture covers; nothing is written to the save.
 */
final class WorldMapIcon {
    private static final int SIZE = SavedWorld.ICON_SIZE;
    private static final int WATER = 12;
    private static final byte[] COLORS = loadColors();

    private WorldMapIcon() {
    }

    /** The picture as straight-alpha ARGB, or null when none of its chunks exist. */
    static int[] render(Path folder, NBTTagCompound data) {
        int centerX = data.getInteger("SpawnX");
        int centerZ = data.getInteger("SpawnZ");
        NBTTagCompound player = data.getCompoundTag("Player");
        NBTTagList position = player.getTagList("Pos", 6);
        if (position.tagCount() == 3 && player.getInteger("Dimension") == 0) {
            centerX = (int) Math.floor(position.getDoubleAt(0));
            centerZ = (int) Math.floor(position.getDoubleAt(2));
        }
        Chunks chunks = new Chunks(folder.resolve("region"));
        int left = centerX - SIZE / 2;
        int top = centerZ - SIZE / 2;
        int[] pixels = new int[SIZE * SIZE];
        boolean any = false;
        for (int column = 0; column < SIZE; column++) {
            int x = left + column;
            double north = 0.0;
            // Row -1 only seeds the north neighbour's height, as the map's first row does in vanilla.
            for (int row = -1; row < SIZE; row++) {
                int z = top + row;
                Chunk chunk = chunks.at(x >> 4, z >> 4);
                if (chunk == null) {
                    continue;
                }
                int index = (z & 15) << 4 | (x & 15);
                double height = chunk.height[index];
                int color = chunk.color[index];
                int parity = x + z & 1;
                double step = (height - north) * 4.0 / 5.0 + (parity - 0.5) * 0.4;
                int level = step > 0.6 ? 2 : step < -0.6 ? 0 : 1;
                if (color == WATER) {
                    double depth = chunk.depth[index] * 0.1 + parity * 0.2;
                    level = depth < 0.5 ? 2 : depth > 0.9 ? 0 : 1;
                }
                north = height;
                if (row >= 0 && color != 0) {
                    pixels[row * SIZE + column] = MapColors.argb((byte) (color * 4 + level), 0);
                    any = true;
                }
            }
        }
        return any ? pixels : null;
    }

    /** The top block of every column of one chunk: its height, its map color and the water's depth. */
    private record Chunk(int[] height, byte[] color, byte[] depth) {
        static Chunk read(NBTTagCompound level) {
            byte[][] blocks = new byte[16][];
            byte[][] add = new byte[16][];
            byte[][] meta = new byte[16][];
            NBTTagList sections = level.getTagList("Sections", 10);
            for (int i = 0; i < sections.tagCount(); i++) {
                NBTTagCompound section = sections.getCompoundTagAt(i);
                int y = section.getByte("Y");
                byte[] ids = section.getByteArray("Blocks");
                if (y < 0 || y > 15 || ids.length != 4096) {
                    continue;
                }
                blocks[y] = ids;
                meta[y] = section.getByteArray("Data");
                add[y] = section.getByteArray("Add");
            }
            int[] heightMap = level.getIntArray("HeightMap");
            int[] height = new int[256];
            byte[] color = new byte[256];
            byte[] depth = new byte[256];
            for (int index = 0; index < 256; index++) {
                int x = index & 15;
                int z = index >> 4;
                // Chunk.getHeightValue + 1, then down past blocks with no map color (MapColor.airColor).
                int y = heightMap.length == 256 ? heightMap[index] + 1 : 256;
                int state = 0;
                if (y > 1) {
                    do {
                        state = state(blocks, add, meta, x, --y, z);
                    } while (COLORS[state] == 0 && y > 0);
                    if (y > 0 && liquid(state)) {
                        int below = y - 1;
                        int count = 0;
                        int under;
                        do {
                            under = state(blocks, add, meta, x, below--, z);
                            count++;
                        } while (below > 0 && liquid(under));
                        depth[index] = (byte) Math.min(count, 127);
                    }
                }
                height[index] = y;
                color[index] = COLORS[state];
            }
            return new Chunk(height, color, depth);
        }

        /** Block id << 4 | metadata, with ids past 255 (mods) read as air. */
        private static int state(byte[][] blocks, byte[][] add, byte[][] meta, int x, int y, int z) {
            if (y < 0 || y > 255 || blocks[y >> 4] == null) {
                return 0;
            }
            int section = y >> 4;
            int index = (y & 15) << 8 | z << 4 | x;
            int id = blocks[section][index] & 255;
            if (add[section].length == 2048 && nibble(add[section], index) != 0) {
                return 0;
            }
            int data = meta[section].length == 2048 ? nibble(meta[section], index) : 0;
            return id << 4 | data;
        }

        private static int nibble(byte[] array, int index) {
            int packed = array[index >> 1];
            return (index & 1) == 0 ? packed & 15 : packed >> 4 & 15;
        }

        /** Water and lava, the blocks whose material is liquid. */
        private static boolean liquid(int state) {
            int id = state >> 4;
            return id >= 8 && id <= 11;
        }
    }

    /** Chunks read on demand from the region files, each at most once. */
    private static final class Chunks {
        private final Path regions;
        private final Map<Long, Chunk> loaded = new HashMap<>();

        Chunks(Path regions) {
            this.regions = regions;
        }

        Chunk at(int chunkX, int chunkZ) {
            long key = (long) chunkX << 32 | (chunkZ & 0xFFFFFFFFL);
            if (loaded.containsKey(key)) {
                return loaded.get(key);
            }
            Chunk chunk;
            try {
                NBTTagCompound root = read(chunkX, chunkZ);
                chunk = root == null ? null : Chunk.read(root.getCompoundTag("Level"));
            } catch (IOException | RuntimeException unreadable) {
                chunk = null;
            }
            loaded.put(key, chunk);
            return chunk;
        }

        /** One chunk's NBT from {@code r.<x>.<z>.mca} (RegionFile): a sector table, then length-prefixed zlib data. */
        private NBTTagCompound read(int chunkX, int chunkZ) throws IOException {
            Path file = regions.resolve("r." + (chunkX >> 5) + "." + (chunkZ >> 5) + ".mca");
            if (!Files.isRegularFile(file)) {
                return null;
            }
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
                ByteBuffer entry = ByteBuffer.allocate(4);
                channel.read(entry, 4L * ((chunkX & 31) + (chunkZ & 31) * 32));
                int offset = entry.flip().getInt();
                int sector = offset >>> 8;
                if (sector < 2 || (offset & 255) == 0) {
                    return null;
                }
                ByteBuffer header = ByteBuffer.allocate(5);
                channel.read(header, sector * 4096L);
                header.flip();
                int length = header.getInt() - 1;
                int compression = header.get();
                if (length <= 0 || length > (offset & 255) * 4096) {
                    return null;
                }
                ByteBuffer body = ByteBuffer.allocate(length);
                channel.read(body, sector * 4096L + 5);
                InputStream raw = new ByteArrayInputStream(body.array(), 0, body.position());
                InputStream inflated = switch (compression) {
                    case 1 -> new GZIPInputStream(raw);
                    case 2 -> new InflaterInputStream(raw);
                    default -> null;
                };
                if (inflated == null) {
                    return null;
                }
                try (DataInputStream input = new DataInputStream(new BufferedInputStream(inflated))) {
                    return CompressedStreamTools.read(input);
                }
            }
        }
    }

    private static byte[] loadColors() {
        byte[] colors = new byte[256 * 16];
        try (InputStream resource = WorldMapIcon.class.getResourceAsStream("block-map-colors.csv")) {
            if (resource == null) {
                throw new IllegalStateException("block-map-colors.csv is missing");
            }
            BufferedReader lines = new BufferedReader(new InputStreamReader(resource, StandardCharsets.UTF_8));
            int id = 0;
            for (String line; (line = lines.readLine()) != null && id < 256; ) {
                if (line.startsWith("#") || line.isBlank()) {
                    continue;
                }
                String[] values = line.split(",");
                for (int meta = 0; meta < 16 && meta < values.length; meta++) {
                    colors[id << 4 | meta] = (byte) Integer.parseInt(values[meta].strip());
                }
                id++;
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return colors;
    }
}
