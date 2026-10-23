package alkaline.renderer.world;

import alkaline.renderer.asset.AssetSource;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * A minimal asset pack in vanilla's formats for mesher tests: cube, cube_all and cross parents, stone, grass
 * (with its side overlay), ice, oak leaves, tall grass and a poppy, plus generated 16x16 textures (opaque for
 * solid blocks, partly transparent for overlays, leaves and plants).
 */
final class TestBlockAssets implements AssetSource {
    private final Map<String, byte[]> files = new HashMap<>();

    TestBlockAssets() {
        model("cube", """
                {"elements": [{"from": [0, 0, 0], "to": [16, 16, 16], "faces": {
                  "down":  {"texture": "#down", "cullface": "down"},
                  "up":    {"texture": "#up", "cullface": "up"},
                  "north": {"texture": "#north", "cullface": "north"},
                  "south": {"texture": "#south", "cullface": "south"},
                  "west":  {"texture": "#west", "cullface": "west"},
                  "east":  {"texture": "#east", "cullface": "east"}}}]}""");
        model("cube_all", """
                {"parent": "block/cube", "textures": {"down": "#all", "up": "#all", "north": "#all",
                  "south": "#all", "west": "#all", "east": "#all"}}""");
        model("cube_tinted_all", """
                {"elements": [{"from": [0, 0, 0], "to": [16, 16, 16], "faces": {
                  "down":  {"texture": "#all", "tintindex": 0, "cullface": "down"},
                  "up":    {"texture": "#all", "tintindex": 0, "cullface": "up"},
                  "north": {"texture": "#all", "tintindex": 0, "cullface": "north"},
                  "south": {"texture": "#all", "tintindex": 0, "cullface": "south"},
                  "west":  {"texture": "#all", "tintindex": 0, "cullface": "west"},
                  "east":  {"texture": "#all", "tintindex": 0, "cullface": "east"}}}]}""");
        model("cross", """
                {"ambientocclusion": false, "elements": [
                  {"from": [0.8, 0, 8], "to": [15.2, 16, 8], "rotation": {"origin": [8, 8, 8], "axis": "y",
                   "angle": 45, "rescale": true}, "shade": false, "faces": {
                     "north": {"uv": [0, 0, 16, 16], "texture": "#cross"},
                     "south": {"uv": [0, 0, 16, 16], "texture": "#cross"}}},
                  {"from": [8, 0, 0.8], "to": [8, 16, 15.2], "rotation": {"origin": [8, 8, 8], "axis": "y",
                   "angle": 45, "rescale": true}, "shade": false, "faces": {
                     "west": {"uv": [0, 0, 16, 16], "texture": "#cross"},
                     "east": {"uv": [0, 0, 16, 16], "texture": "#cross"}}}]}""");
        model("tinted_cross", """
                {"ambientocclusion": false, "elements": [
                  {"from": [0.8, 0, 8], "to": [15.2, 16, 8], "rotation": {"origin": [8, 8, 8], "axis": "y",
                   "angle": 45, "rescale": true}, "shade": false, "faces": {
                     "north": {"uv": [0, 0, 16, 16], "texture": "#cross", "tintindex": 0},
                     "south": {"uv": [0, 0, 16, 16], "texture": "#cross", "tintindex": 0}}},
                  {"from": [8, 0, 0.8], "to": [8, 16, 15.2], "rotation": {"origin": [8, 8, 8], "axis": "y",
                   "angle": 45, "rescale": true}, "shade": false, "faces": {
                     "west": {"uv": [0, 0, 16, 16], "texture": "#cross", "tintindex": 0},
                     "east": {"uv": [0, 0, 16, 16], "texture": "#cross", "tintindex": 0}}}]}""");
        model("grass", """
                {"elements": [
                  {"from": [0, 0, 0], "to": [16, 16, 16], "faces": {
                    "down":  {"uv": [0, 0, 16, 16], "texture": "#bottom", "cullface": "down"},
                    "up":    {"uv": [0, 0, 16, 16], "texture": "#top", "cullface": "up", "tintindex": 0},
                    "north": {"uv": [0, 0, 16, 16], "texture": "#side", "cullface": "north"},
                    "south": {"uv": [0, 0, 16, 16], "texture": "#side", "cullface": "south"},
                    "west":  {"uv": [0, 0, 16, 16], "texture": "#side", "cullface": "west"},
                    "east":  {"uv": [0, 0, 16, 16], "texture": "#side", "cullface": "east"}}},
                  {"from": [0, 0, 0], "to": [16, 16, 16], "faces": {
                    "north": {"uv": [0, 0, 16, 16], "texture": "#overlay", "tintindex": 0, "cullface": "north"},
                    "south": {"uv": [0, 0, 16, 16], "texture": "#overlay", "tintindex": 0, "cullface": "south"},
                    "west":  {"uv": [0, 0, 16, 16], "texture": "#overlay", "tintindex": 0, "cullface": "west"},
                    "east":  {"uv": [0, 0, 16, 16], "texture": "#overlay", "tintindex": 0, "cullface": "east"}}}]}""");
        model("grass_normal", """
                {"parent": "block/grass", "textures": {"bottom": "blocks/dirt", "top": "blocks/grass_top",
                  "side": "blocks/grass_side", "overlay": "blocks/grass_side_overlay"}}""");
        model("stone", "{\"parent\": \"block/cube_all\", \"textures\": {\"all\": \"blocks/stone\"}}");
        model("ice", "{\"parent\": \"block/cube_all\", \"textures\": {\"all\": \"blocks/ice\"}}");
        model("oak_leaves", "{\"parent\": \"block/cube_tinted_all\", \"textures\": {\"all\": \"blocks/leaves_oak\"}}");
        model("tall_grass", "{\"parent\": \"block/tinted_cross\", \"textures\": {\"cross\": \"blocks/tallgrass\"}}");
        model("poppy", "{\"parent\": \"block/cross\", \"textures\": {\"cross\": \"blocks/flower_rose\"}}");
        state("stone", "{\"variants\": {\"normal\": {\"model\": \"stone\"}}}");
        state("ice", "{\"variants\": {\"normal\": {\"model\": \"ice\"}}}");
        state("oak_leaves", "{\"variants\": {\"normal\": {\"model\": \"oak_leaves\"}}}");
        state("tall_grass", "{\"variants\": {\"normal\": {\"model\": \"tall_grass\"}}}");
        state("poppy", "{\"variants\": {\"normal\": {\"model\": \"poppy\"}}}");
        state("grass", "{\"variants\": {\"snowy=false\": {\"model\": \"grass_normal\"}}}");
        texture("stone", 0xFF808080, false);
        texture("dirt", 0xFF7A5A3C, false);
        texture("grass_top", 0xFF9C9C9C, false);
        texture("grass_side", 0xFF7A5A3C, false);
        texture("grass_side_overlay", 0xFF9C9C9C, true);
        texture("ice", 0x9F7DADFF, false);
        texture("leaves_oak", 0xFF707070, true);
        texture("tallgrass", 0xFF909090, true);
        texture("flower_rose", 0xFFC02020, true);
        for (String liquid : new String[]{"water_still", "water_flow", "lava_still", "lava_flow"}) {
            texture(liquid, 0xB02040C0, false);
        }
    }

    private void model(String name, String json) {
        files.put("assets/minecraft/models/block/" + name + ".json", json.getBytes(StandardCharsets.UTF_8));
    }

    private void state(String name, String json) {
        files.put("assets/minecraft/blockstates/" + name + ".json", json.getBytes(StandardCharsets.UTF_8));
    }

    /** A 16x16 texture of one color; with holes, every other texel is transparent. */
    private void texture(String name, int argb, boolean holes) {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                image.setRGB(x, y, holes && ((x + y) & 1) == 1 ? 0 : argb);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", out);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        files.put("assets/minecraft/textures/blocks/" + name + ".png", out.toByteArray());
    }

    @Override
    public InputStream open(String assetPath) {
        byte[] bytes = files.get(assetPath);
        return bytes == null ? null : new ByteArrayInputStream(bytes);
    }

    @Override
    public String describe() {
        return "test block assets";
    }

    @Override
    public void close() {
    }
}
