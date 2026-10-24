package alkaline.game.world;

import alkaline.game.net.ServerSession;
import java.util.HashMap;
import java.util.Map;

/**
 * The maps the server has sent (Map Data packets), by map id: their 128 by 128 pixel bytes and decorations. Maps
 * start empty and fill as patches arrive. Game-thread state.
 */
public final class MapStore {
    /** One map. {@code icons} holds {@code type << 24 | x << 16 | z << 8 | rotation} (unsigned bytes). */
    public static final class Data {
        public static final int SIZE = 128;
        private final byte[] colors = new byte[SIZE * SIZE];
        private int[] icons = new int[0];
        private int version;

        /** The pixel bytes, row-major; do not modify. */
        public byte[] colors() {
            return colors;
        }

        public int[] icons() {
            return icons;
        }

        /** Bumped whenever the pixels change, so a texture re-uploads only then. */
        public int version() {
            return version;
        }
    }

    private final Map<Integer, Data> maps = new HashMap<>();

    /** The map with this id, or null before its first packet (the item then shows only its background). */
    public Data get(int id) {
        return maps.get(id);
    }

    public void clear() {
        maps.clear();
    }

    /** S34PacketMaps.setMapdataTo: the decorations are replaced, the pixel patch copied in. */
    public void apply(ServerSession.MapData packet) {
        Data data = maps.computeIfAbsent(packet.mapId(), id -> new Data());
        data.icons = packet.icons();
        if (packet.columns() > 0 && packet.colors() != null) {
            for (int column = 0; column < packet.columns(); column++) {
                for (int row = 0; row < packet.rows(); row++) {
                    int x = packet.x() + column;
                    int z = packet.z() + row;
                    int source = column + row * packet.columns();
                    if (x < Data.SIZE && z < Data.SIZE && source < packet.colors().length) {
                        data.colors[x + z * Data.SIZE] = packet.colors()[source];
                    }
                }
            }
        }
        data.version++;
    }
}
