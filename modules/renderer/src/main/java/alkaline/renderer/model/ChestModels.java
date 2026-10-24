package alkaline.renderer.model;

import alkaline.renderer.math.Mat4;
import alkaline.renderer.world.vanilla.BlockModels189;
import java.util.ArrayList;
import java.util.List;

/**
 * ModelChest and ModelLargeChest: the lid (pivoting about its back edge), the knob that turns with it, and the
 * base, in model units with vanilla's y-down axes. Parts: {@link #LID}, {@link #KNOB}, {@link #BASE}. Items draw
 * the single chest closed, as TileEntityItemStackRenderer does, through {@link #itemQuads}.
 */
public final class ChestModels {
    public static final int LID = 0;
    public static final int KNOB = 1;
    public static final int BASE = 2;

    private ChestModels() {
    }

    public static CuboidModel chest(boolean large) {
        int width = large ? 30 : 14;
        return CuboidModel.builder(large ? "chest_large" : "chest", large ? 128 : 64, 64)
                .part("lid", null, 1f, 7f, 15f, new ModelBox(0, 0, 0f, -5f, -14f, width, 5, 14, 0f, false))
                .part("knob", null, large ? 16f : 8f, 7f, 15f, new ModelBox(0, 0, -1f, -2f, -15f, 2, 4, 1, 0f, false))
                .part("base", null, 1f, 6f, 1f, new ModelBox(0, 19, 0f, 0f, 0f, width, 10, 14, 0f, false))
                .build();
    }

    /** The texture under {@code textures/} a chest item draws with, or null for other items. */
    public static String itemTexture(String model) {
        return switch (model) {
            case "chest" -> "entity/chest/normal";
            case "trapped_chest" -> "entity/chest/trapped";
            case "ender_chest" -> "entity/chest/ender";
            default -> null;
        };
    }

    /**
     * A closed single chest as item model quads (the 0..1 block space item renderers place): RenderItem's
     * built-in path turns it half a turn about y, then TileEntityChestRenderer flips y and z.
     */
    public static List<BlockModels189.Quad> itemQuads(String texture) {
        return itemQuads(texture, true);
    }

    /**
     * The closed chest in block space. {@code turned} adds RenderItem's half turn about y that items get; a chest
     * drawn by ChestRenderer.renderChestBrightness (a minecart's load) skips it.
     */
    public static List<BlockModels189.Quad> itemQuads(String texture, boolean turned) {
        float[] item = Mat4.identity(new float[16]);
        if (turned) {
            Mat4.translate(item, 0.5f, 0.5f, 0.5f, item);
            Mat4.rotate(item, (float) Math.PI, 0f, 1f, 0f, item);
            Mat4.translate(item, -0.5f, -0.5f, -0.5f, item);
        }
        Mat4.translate(item, 0f, 1f, 1f, item);
        Mat4.scale(item, 1f, -1f, -1f, item);
        CuboidModel model = chest(false);
        List<BlockModels189.Quad> quads = new ArrayList<>();
        ModelMesher.faces(model, (part, positions, uvs, nx, ny, nz) -> {
            ModelPart owner = model.parts().get(part);
            float[] placed = new float[12];
            for (int corner = 0; corner < 4; corner++) {
                float x = positions[corner * 3] + owner.pivotX() / ModelMesher.UNITS_PER_BLOCK;
                float y = positions[corner * 3 + 1] + owner.pivotY() / ModelMesher.UNITS_PER_BLOCK;
                float z = positions[corner * 3 + 2] + owner.pivotZ() / ModelMesher.UNITS_PER_BLOCK;
                placed[corner * 3] = item[0] * x + item[4] * y + item[8] * z + item[12];
                placed[corner * 3 + 1] = item[1] * x + item[5] * y + item[9] * z + item[13];
                placed[corner * 3 + 2] = item[2] * x + item[6] * y + item[10] * z + item[14];
            }
            quads.add(new BlockModels189.Quad(placed, uvs.clone(), texture, -1, -1, 0, 0, false));
        });
        return List.copyOf(quads);
    }
}
