package alkaline.clientapp;

import alkaline.game.world.MapColors;
import alkaline.game.world.MapStore;
import alkaline.renderer.asset.AssetSource;
import alkaline.renderer.image.ImageDecoder;
import alkaline.renderer.image.ImageSampling;
import alkaline.renderer.image.RgbaImage;
import alkaline.renderer.vulkan.model.QuadRenderer;
import alkaline.renderer.vulkan.resource.TextureOptions;
import alkaline.renderer.vulkan.resource.VulkanRenderer;
import alkaline.renderer.vulkan.resource.VulkanTexture;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Filled maps: ItemRenderer.renderItemMap (in hand, on its background sheet) and RenderItemFrame (the bare map in a
 * frame), through MapItemRenderer's quads: the 128 by 128 pixel texture, then the player markers from
 * {@code map_icons.png}. A map's texture is rebuilt outside frames whenever its pixels changed; one not yet received
 * shows only its background, as a map vanilla has no data for does.
 *
 * <p>All geometry is in map pixels (0..128, y down); callers pass the matrix from that space to the view.
 */
final class MapView implements AutoCloseable {
    /** The item id of a filled map. */
    static final int FILLED_MAP = 358;
    /** MapColor 0's faint checker lets the background show; the quad is drawn without vanilla's alpha test. */
    private static final float NO_CUTOFF = 0f;

    private record Loaded(int version, VulkanTexture texture) {
    }

    private final VulkanRenderer renderer;
    private final AssetSource assets;
    private final Map<Integer, Loaded> textures = new HashMap<>();
    private final Set<Integer> wanted = new HashSet<>();
    private VulkanTexture background;
    private VulkanTexture icons;
    private boolean assetsLoaded;
    /** The pack's base colors for {@link MapColors#argb(byte, int, int[])}, or null; set stale when they change. */
    private int[] colors;
    private boolean stale;
    private final float[] corner = new float[3];

    MapView(VulkanRenderer renderer, AssetSource assets) {
        this.renderer = renderer;
        this.assets = assets;
    }

    /** The resource packs' map colors (null for vanilla's): every map is rebuilt before the next frame. */
    void colors(int[] custom) {
        colors = custom;
        stale = true;
    }

    /** Loads the sheets once and rebuilds the textures of the maps drawn since the last call; outside frames. */
    void beforeFrame(MapStore store) {
        if (stale) {
            stale = false;
            for (Loaded loaded : textures.values()) {
                loaded.texture().close();
            }
            textures.clear();
        }
        if (!assetsLoaded) {
            assetsLoaded = true;
            TextureOptions nearest = new TextureOptions(ImageSampling.NEAREST, false, false, 1);
            RgbaImage sheet = image("assets/minecraft/textures/map/map_background.png");
            background = sheet == null ? null : renderer.createTexture(sheet, nearest);
            RgbaImage markers = image("assets/minecraft/textures/map/map_icons.png");
            icons = markers == null ? null : renderer.createTexture(markers, nearest);
        }
        for (int id : wanted) {
            MapStore.Data data = store.get(id);
            Loaded loaded = textures.get(id);
            if (data != null && (loaded == null || loaded.version() != data.version())) {
                VulkanTexture texture = renderer.createTexture(texels(data, colors),
                        new TextureOptions(ImageSampling.NEAREST, false, false, 1));
                textures.put(id, new Loaded(data.version(), texture));
                if (loaded != null) {
                    loaded.texture().close();
                }
            }
        }
        wanted.clear();
    }

    /** MapItemRenderer.Instance.updateMapTexture: the pixel bytes as RGBA. */
    static RgbaImage texels(MapStore.Data data, int[] custom) {
        byte[] rgba = new byte[MapStore.Data.SIZE * MapStore.Data.SIZE * 4];
        for (int index = 0; index < MapStore.Data.SIZE * MapStore.Data.SIZE; index++) {
            int argb = MapColors.argb(data.colors()[index], index, custom);
            rgba[index * 4] = (byte) (argb >> 16);
            rgba[index * 4 + 1] = (byte) (argb >> 8);
            rgba[index * 4 + 2] = (byte) argb;
            rgba[index * 4 + 3] = (byte) (argb >>> 24);
        }
        return new RgbaImage(MapStore.Data.SIZE, MapStore.Data.SIZE, rgba);
    }

    private RgbaImage image(String path) {
        if (assets == null) {
            return null;
        }
        try (InputStream input = assets.open(path)) {
            return input == null ? null : ImageDecoder.decode(input);
        } catch (IOException | RuntimeException failure) {
            return null;
        }
    }

    /**
     * ItemRenderer.renderItemMap's sheet and map: the background (seven pixels around the map), then the map and
     * its markers. {@code matrix} maps map pixels to the view; {@code color} tints everything (the light and item
     * lighting already applied).
     */
    void drawHeld(QuadRenderer quads, MapStore store, int mapId, float[] matrix, int color) {
        if (background != null) {
            quads.start(QuadRenderer.Mode.ALPHA, background);
            quad(quads, matrix, -7f, 135f, 0f, 0f, 1f, 135f, 135f, 0f, 1f, 1f, 135f, -7f, 0f, 1f, 0f, -7f, -7f, 0f, 0f,
                    0f, color);
        }
        drawMap(quads, store, mapId, matrix, color, false);
    }

    /** RenderItemFrame's map: no background, only the holder's own marker. */
    void drawFramed(QuadRenderer quads, MapStore store, int mapId, float[] matrix, int color) {
        drawMap(quads, store, mapId, matrix, color, true);
    }

    private void drawMap(QuadRenderer quads, MapStore store, int mapId, float[] matrix, int color,
                         boolean markersOnly) {
        wanted.add(mapId);
        MapStore.Data data = store.get(mapId);
        Loaded loaded = textures.get(mapId);
        if (data == null || loaded == null) {
            return;
        }
        quads.start(QuadRenderer.Mode.ALPHA, loaded.texture(), NO_CUTOFF);
        quad(quads, matrix, 0f, 128f, -0.01f, 0f, 1f, 128f, 128f, -0.01f, 1f, 1f, 128f, 0f, -0.01f, 1f, 0f, 0f, 0f,
                -0.01f, 0f, 0f, color);
        if (icons == null) {
            return;
        }
        quads.start(QuadRenderer.Mode.ALPHA, icons);
        int order = 0;
        for (int icon : data.icons()) {
            int type = icon >>> 24 & 255;
            if (!markersOnly || type == 1) {
                marker(quads, matrix, type, (byte) (icon >> 16), (byte) (icon >> 8), icon & 15, order, color);
                order++;
            }
        }
        quads.flush();
    }

    /**
     * One marker: moved to its map position, turned by sixteenths of a turn, scaled four pixels per half-size, and
     * nudged back by (-0.125, 0.125) in its own units; markers stack toward the viewer.
     */
    private void marker(QuadRenderer quads, float[] matrix, int type, byte x, byte z, int rotation, int order,
                        int color) {
        float centerX = x / 2.0F + 64.0F;
        float centerY = z / 2.0F + 64.0F;
        double angle = Math.toRadians(rotation * 360 / 16.0F);
        float cos = (float) Math.cos(angle);
        float sin = (float) Math.sin(angle);
        float depth = -0.02F + order * -0.001F * 3.0F;
        float u0 = type % 4 / 4.0F;
        float v0 = type / 4 / 4.0F;
        float u1 = (type % 4 + 1) / 4.0F;
        float v1 = (type / 4 + 1) / 4.0F;
        float[] unit = {-1f, 1f, u0, v0, 1f, 1f, u1, v0, 1f, -1f, u1, v1, -1f, -1f, u0, v1};
        float[] out = new float[20];
        for (int vertex = 0; vertex < 4; vertex++) {
            float localX = (unit[vertex * 4] - 0.125F) * 4.0F;
            float localY = (unit[vertex * 4 + 1] + 0.125F) * 4.0F;
            out[vertex * 5] = centerX + localX * cos - localY * sin;
            out[vertex * 5 + 1] = centerY + localX * sin + localY * cos;
            out[vertex * 5 + 2] = depth;
            out[vertex * 5 + 3] = unit[vertex * 4 + 2];
            out[vertex * 5 + 4] = unit[vertex * 4 + 3];
        }
        quad(quads, matrix, out[0], out[1], out[2], out[3], out[4], out[5], out[6], out[7], out[8], out[9], out[10],
                out[11], out[12], out[13], out[14], out[15], out[16], out[17], out[18], out[19], color);
    }

    private void quad(QuadRenderer quads, float[] m, float x0, float y0, float z0, float u0, float v0, float x1,
                      float y1, float z1, float u1, float v1, float x2, float y2, float z2, float u2, float v2,
                      float x3, float y3, float z3, float u3, float v3, int color) {
        quads.quad(m[0] * x0 + m[4] * y0 + m[8] * z0 + m[12], m[1] * x0 + m[5] * y0 + m[9] * z0 + m[13],
                m[2] * x0 + m[6] * y0 + m[10] * z0 + m[14], u0, v0,
                m[0] * x1 + m[4] * y1 + m[8] * z1 + m[12], m[1] * x1 + m[5] * y1 + m[9] * z1 + m[13],
                m[2] * x1 + m[6] * y1 + m[10] * z1 + m[14], u1, v1,
                m[0] * x2 + m[4] * y2 + m[8] * z2 + m[12], m[1] * x2 + m[5] * y2 + m[9] * z2 + m[13],
                m[2] * x2 + m[6] * y2 + m[10] * z2 + m[14], u2, v2,
                m[0] * x3 + m[4] * y3 + m[8] * z3 + m[12], m[1] * x3 + m[5] * y3 + m[9] * z3 + m[13],
                m[2] * x3 + m[6] * y3 + m[10] * z3 + m[14], u3, v3, color);
    }

    @Override
    public void close() {
        for (Loaded loaded : textures.values()) {
            loaded.texture().close();
        }
        textures.clear();
        if (background != null) {
            background.close();
        }
        if (icons != null) {
            icons.close();
        }
    }
}
