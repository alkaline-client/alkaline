package alkaline.renderer.world.vanilla;

import alkaline.config.Json;
import alkaline.renderer.asset.AssetSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Loads 1.8.9 blockstate and block model JSON from the game's assets and bakes models into quads the way
 * vanilla's ModelBakery and FaceBakery do: vertex order per face, element rotation (with rescale), the variant's
 * x/y rotation and UV lock, face UV defaults and rotation, the quad's lighting face from its vertices, and the
 * reordering of unrotated quads to their face's canonical vertex order. Positions are in blocks (0..1 for a full
 * cube); texture coordinates are in sprite units (0..1 across the sprite), without vanilla's 0.1% inward pull.
 *
 * <p>Not thread-safe; load everything before meshing starts.
 */
public final class BlockModels189 {
    /** Face codes the mesher uses: 0 -x (west), 1 +x (east), 2 -y (down), 3 +y (up), 4 -z (north), 5 +z (south). */
    public static final int WEST = 0;
    public static final int EAST = 1;
    public static final int DOWN = 2;
    public static final int UP = 3;
    public static final int NORTH = 4;
    public static final int SOUTH = 5;

    /**
     * One baked quad. {@code positions} holds four xyz corners and {@code uvs} four uv pairs, in vanilla's
     * stored vertex order. {@code cullFace} is a face code or -1; {@code face} is the lighting face vanilla
     * derives from the vertices; {@code shade} is the baked directional brightness byte (255 when unshaded).
     */
    public record Quad(float[] positions, float[] uvs, String texture, int tintIndex, int cullFace, int face,
                       int shade, boolean partRotated) {
    }

    /** A baked model: quads in vanilla's draw order (cull-face lists by facing, then the rest). */
    public record Model(List<Quad> quads, boolean ambientOcclusion) {
    }

    /** One weighted alternative of a variant. */
    public record Choice(Model model, int weight) {
    }

    // Vanilla EnumFacing indices: down 0, up 1, north 2, south 3, west 4, east 5.
    private static final String[] FACE_NAMES = {"down", "up", "north", "south", "west", "east"};
    private static final int[] VANILLA_TO_CODE = {DOWN, UP, NORTH, SOUTH, WEST, EAST};
    private static final float[] BRIGHTNESS = {0.5F, 1.0F, 0.8F, 0.8F, 0.6F, 0.6F};
    // EnumFaceDirection vertex information: per face, per vertex, the indices (into the from/to array laid out
    // by vanilla face index: down=from.y, up=to.y, north=from.z, south=to.z, west=from.x, east=to.x) of x, y, z.
    private static final int D = 0;
    private static final int U = 1;
    private static final int N = 2;
    private static final int S = 3;
    private static final int W = 4;
    private static final int E = 5;
    private static final int[][][] VERTICES = {
            {{W, D, S}, {W, D, N}, {E, D, N}, {E, D, S}},
            {{W, U, N}, {W, U, S}, {E, U, S}, {E, U, N}},
            {{E, U, N}, {E, D, N}, {W, D, N}, {W, U, N}},
            {{W, U, S}, {W, D, S}, {E, D, S}, {E, U, S}},
            {{W, U, N}, {W, D, N}, {W, D, S}, {W, U, S}},
            {{E, U, S}, {E, D, S}, {E, D, N}, {E, U, N}},
    };
    private static final int[][] DIRECTION = {{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};
    private static final float SCALE_22_5 = 1.0F / (float) Math.cos(0.39269909262657166D) - 1.0F;
    private static final float SCALE_45 = 1.0F / (float) Math.cos(Math.PI / 4D) - 1.0F;

    private final AssetSource assets;
    private final Map<String, Map<?, ?>> blockstates = new HashMap<>();
    private final Map<String, Map<?, ?>> models = new HashMap<>();
    private final Map<String, Model> baked = new HashMap<>();
    private final Map<String, List<Choice>> variants = new HashMap<>();

    public BlockModels189(AssetSource assets) {
        this.assets = Objects.requireNonNull(assets, "assets");
    }

    /**
     * The alternatives for a location, sorted as vanilla's WeightedBakedModel sorts them (weight descending,
     * then fewer quads first); a single entry for plain variants. Empty when the file or variant is missing.
     */
    public List<Choice> variants(BlockStates189.Location location) {
        String key = location.file() + "#" + location.variant();
        List<Choice> cached = variants.get(key);
        if (cached != null) {
            return cached;
        }
        List<Choice> result = new ArrayList<>();
        Map<?, ?> state = blockstate(location.file());
        Object entry = state != null && state.get("variants") instanceof Map<?, ?> map ? map.get(location.variant()) : null;
        if (entry instanceof Map<?, ?> single) {
            Choice choice = choice(single);
            if (choice != null) {
                result.add(choice);
            }
        } else if (entry instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Choice choice = choice(map);
                    if (choice != null) {
                        result.add(choice);
                    }
                }
            }
            // List.sort is stable, like Collections.sort.
            result.sort((a, b) -> a.weight() != b.weight() ? Integer.compare(b.weight(), a.weight())
                    : Integer.compare(a.model().quads().size(), b.model().quads().size()));
        }
        List<Choice> immutable = List.copyOf(result);
        variants.put(key, immutable);
        return immutable;
    }

    /**
     * An item model with elements ({@code models/item/<name>.json} whose parents lead to a block model), baked
     * unrotated; null for generated (sprite) item models and missing files.
     */
    public Model item(String name) {
        return bake("item/" + name, 0, 0, false);
    }

    /**
     * A model file by full asset path ({@code assets/.../name.json}), as OptiFine's custom item models are kept
     * beside their properties; its {@code ./} textures sit beside it. Null without elements.
     */
    public Model itemFile(String path) {
        return bake(path, 0, 0, false);
    }

    /** WeightedBakedModel.getAlternativeModel with vanilla's position random as the seed. */
    public static int pick(List<Choice> choices, long positionRandom) {
        if (choices.size() <= 1) {
            return 0;
        }
        int total = 0;
        for (Choice choice : choices) {
            total += choice.weight();
        }
        int weight = Math.abs((int) positionRandom >> 16) % total;
        for (int index = 0; index < choices.size(); index++) {
            weight -= choices.get(index).weight();
            if (weight < 0) {
                return index;
            }
        }
        return choices.size() - 1;
    }

    private Choice choice(Map<?, ?> json) {
        if (!(json.get("model") instanceof String name)) {
            return null;
        }
        int x = json.get("x") instanceof Number number ? number.intValue() : 0;
        int y = json.get("y") instanceof Number number ? number.intValue() : 0;
        boolean uvlock = Boolean.TRUE.equals(json.get("uvlock"));
        int weight = json.get("weight") instanceof Number number ? number.intValue() : 1;
        Model model = bake("block/" + name, Math.floorMod(x, 360) / 90, Math.floorMod(y, 360) / 90, uvlock);
        return model == null ? null : new Choice(model, Math.max(1, weight));
    }

    // ---- JSON ------------------------------------------------------------------------------------------------

    private Map<?, ?> blockstate(String file) {
        return blockstates.computeIfAbsent(file, name -> read("assets/minecraft/blockstates/" + name + ".json"));
    }

    private Map<?, ?> model(String name) {
        if (name.startsWith("assets/")) {
            return models.computeIfAbsent(name, key -> besideFile(key, read(key)));
        }
        String path = name.startsWith("minecraft:") ? name.substring(10) : name;
        return models.computeIfAbsent(path, key -> read("assets/minecraft/models/" + key + ".json"));
    }

    /** A model read from a full path, its {@code ./} texture names made full asset paths beside the file. */
    private static Map<?, ?> besideFile(String path, Map<?, ?> json) {
        if (json == null || !(json.get("textures") instanceof Map<?, ?> textures)) {
            return json;
        }
        String directory = path.substring(0, path.lastIndexOf('/') + 1);
        Map<Object, Object> resolved = new HashMap<>();
        for (Map.Entry<?, ?> entry : textures.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String text && text.startsWith("./")) {
                value = directory + text.substring(2) + ".png";
            }
            resolved.put(entry.getKey(), value);
        }
        Map<Object, Object> copy = new HashMap<>(json);
        copy.put("textures", resolved);
        return copy;
    }

    private Map<?, ?> read(String path) {
        try (InputStream input = assets.open(path)) {
            if (input == null) {
                return null;
            }
            Object parsed = Json.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            return parsed instanceof Map<?, ?> map ? map : null;
        } catch (IOException | IllegalArgumentException missing) {
            return null;
        }
    }

    // ---- baking ----------------------------------------------------------------------------------------------

    private Model bake(String name, int quartersX, int quartersY, boolean uvlock) {
        String key = name + "/" + quartersX + "/" + quartersY + "/" + uvlock;
        if (baked.containsKey(key)) {
            return baked.get(key);
        }
        Map<String, String> textures = new HashMap<>();
        List<?> elements = null;
        boolean ambientOcclusion = true;
        String current = name;
        for (int depth = 0; depth < 16 && current != null; depth++) {
            Map<?, ?> json = model(current);
            if (json == null) {
                break;
            }
            if (json.get("textures") instanceof Map<?, ?> declared) {
                for (Map.Entry<?, ?> entry : declared.entrySet()) {
                    if (entry.getValue() instanceof String value) {
                        textures.putIfAbsent(String.valueOf(entry.getKey()), value);
                    }
                }
            }
            String parent = json.get("parent") instanceof String value ? value : null;
            if (parent == null) {
                // Elements and the occlusion flag come from the root of the chain.
                if (json.get("elements") instanceof List<?> list) {
                    elements = list;
                }
                if (json.get("ambientocclusion") instanceof Boolean flag) {
                    ambientOcclusion = flag;
                }
            }
            current = parent;
        }
        if (elements == null) {
            baked.put(key, null);
            return null;
        }
        List<List<Quad>> byCullFace = new ArrayList<>();
        for (int face = 0; face < 6; face++) {
            byCullFace.add(new ArrayList<>());
        }
        List<Quad> general = new ArrayList<>();
        for (Object element : elements) {
            if (element instanceof Map<?, ?> part) {
                bakeElement(part, textures, quartersX, quartersY, uvlock, byCullFace, general);
            }
        }
        List<Quad> quads = new ArrayList<>();
        // BlockModelRenderer draws EnumFacing order: down, up, north, south, west, east.
        for (int vanilla = 0; vanilla < 6; vanilla++) {
            quads.addAll(byCullFace.get(VANILLA_TO_CODE[vanilla]));
        }
        quads.addAll(general);
        Model model = new Model(List.copyOf(quads), ambientOcclusion);
        baked.put(key, model);
        return model;
    }

    private void bakeElement(Map<?, ?> part, Map<String, String> textures, int quartersX, int quartersY,
                             boolean uvlock, List<List<Quad>> byCullFace, List<Quad> general) {
        float[] from = vector(part.get("from"));
        float[] to = vector(part.get("to"));
        if (from == null || to == null || !(part.get("faces") instanceof Map<?, ?> faces)) {
            return;
        }
        boolean shade = !Boolean.FALSE.equals(part.get("shade"));
        Rotation rotation = rotation(part.get("rotation"));
        float[] bounds = new float[6];
        bounds[W] = from[0] / 16.0F;
        bounds[D] = from[1] / 16.0F;
        bounds[N] = from[2] / 16.0F;
        bounds[E] = to[0] / 16.0F;
        bounds[U] = to[1] / 16.0F;
        bounds[S] = to[2] / 16.0F;
        // Faces in EnumMap order (down, up, north, south, west, east), as vanilla's deserializer stores them.
        for (int facing = 0; facing < 6; facing++) {
            if (!(faces.get(FACE_NAMES[facing]) instanceof Map<?, ?> face)) {
                continue;
            }
            String textureRef = face.get("texture") instanceof String value ? value : null;
            if (textureRef == null) {
                continue;
            }
            float[] uv = vector4(face.get("uv"));
            if (uv == null) {
                uv = defaultUv(facing, from, to);
            }
            int uvRotation = face.get("rotation") instanceof Number number ? number.intValue() : 0;
            int tintIndex = face.get("tintindex") instanceof Number number ? number.intValue() : -1;
            int cullFace = face.get("cullface") instanceof String value ? faceIndex(value) : -1;
            Quad quad = bakeQuad(bounds, facing, uv, uvRotation, rotation, quartersX, quartersY, uvlock, shade,
                    resolve(textureRef, textures), tintIndex, cullFace < 0 ? -1 : rotateFace(cullFace, quartersX, quartersY));
            if (quad.cullFace() < 0) {
                general.add(quad);
            } else {
                byCullFace.get(quad.cullFace()).add(quad);
            }
        }
    }

    private record Rotation(float[] origin, int axis, float angle, boolean rescale) {
    }

    private static Rotation rotation(Object json) {
        if (!(json instanceof Map<?, ?> map)) {
            return null;
        }
        float[] origin = vector(map.get("origin"));
        String axis = map.get("axis") instanceof String value ? value : "y";
        float angle = map.get("angle") instanceof Number number ? number.floatValue() : 0;
        if (origin == null) {
            return null;
        }
        for (int index = 0; index < 3; index++) {
            origin[index] *= 0.0625F;
        }
        return new Rotation(origin, switch (axis) {
            case "x" -> 0;
            case "z" -> 2;
            default -> 1;
        }, angle, Boolean.TRUE.equals(map.get("rescale")));
    }

    /** BlockPart.getFaceUvs: the face's projection of the element when no uv is given. */
    private static float[] defaultUv(int facing, float[] from, float[] to) {
        return switch (facing) {
            case 0, 1 -> new float[]{from[0], from[2], to[0], to[2]};
            case 2, 3 -> new float[]{from[0], 16.0F - to[1], to[0], 16.0F - from[1]};
            default -> new float[]{from[2], 16.0F - to[1], to[2], 16.0F - from[1]};
        };
    }

    private Quad bakeQuad(float[] bounds, int facing, float[] uv, int uvRotation, Rotation part, int quartersX,
                          int quartersY, boolean uvlock, boolean shade, String texture, int tintIndex,
                          int cullFace) {
        float[] positions = new float[12];
        float[] uvs = new float[8];
        int rotatedFace = rotateVanillaFace(facing, quartersX, quartersY);
        int shadeByte = shade ? Math.max(0, Math.min(255, (int) (BRIGHTNESS[rotatedFace] * 255.0F))) : 255;
        float[] p = new float[3];
        for (int vertex = 0; vertex < 4; vertex++) {
            int[] info = VERTICES[facing][vertex];
            p[0] = bounds[info[0]];
            p[1] = bounds[info[1]];
            p[2] = bounds[info[2]];
            rotatePart(p, part);
            int store = vertex;
            if (quartersX != 0 || quartersY != 0) {
                rotateModel(p, quartersX, quartersY);
                store = rotateVertex(facing, vertex, quartersX, quartersY);
            }
            positions[store * 3] = p[0];
            positions[store * 3 + 1] = p[1];
            positions[store * 3 + 2] = p[2];
            uvs[store * 2] = faceU(uv, uvRotation, vertex) / 16.0F;
            uvs[store * 2 + 1] = faceV(uv, uvRotation, vertex) / 16.0F;
        }
        int lightingFace = facingFromVertices(positions);
        if (uvlock) {
            lockUv(positions, uvs, lightingFace, uvRotation);
        }
        if (part == null) {
            applyFacing(positions, uvs, lightingFace);
        }
        return new Quad(positions, uvs, texture, tintIndex, cullFace, VANILLA_TO_CODE[lightingFace], shadeByte,
                part != null);
    }

    /** BlockFaceUV.func_178348_a / func_178346_b: the uv corner a vertex takes after the face's uv rotation. */
    private static float faceU(float[] uv, int rotation, int vertex) {
        int corner = (vertex + rotation / 90) % 4;
        return corner == 0 || corner == 1 ? uv[0] : uv[2];
    }

    private static float faceV(float[] uv, int rotation, int vertex) {
        int corner = (vertex + rotation / 90) % 4;
        return corner == 0 || corner == 3 ? uv[1] : uv[3];
    }

    private static void rotatePart(float[] p, Rotation part) {
        if (part == null) {
            return;
        }
        double radians = part.angle() * 0.017453292F;
        float cos = (float) Math.cos(radians);
        float sin = (float) Math.sin(radians);
        float sx = 1;
        float sy = 1;
        float sz = 1;
        if (part.rescale()) {
            float scale = Math.abs(part.angle()) == 22.5F ? SCALE_22_5 : SCALE_45;
            sx = part.axis() == 0 ? 1 : 1 + scale;
            sy = part.axis() == 1 ? 1 : 1 + scale;
            sz = part.axis() == 2 ? 1 : 1 + scale;
        }
        float x = p[0] - part.origin()[0];
        float y = p[1] - part.origin()[1];
        float z = p[2] - part.origin()[2];
        float rx;
        float ry;
        float rz;
        switch (part.axis()) {
            case 0 -> {
                rx = x;
                ry = y * cos - z * sin;
                rz = y * sin + z * cos;
            }
            case 1 -> {
                rx = x * cos + z * sin;
                ry = y;
                rz = -x * sin + z * cos;
            }
            default -> {
                rx = x * cos - y * sin;
                ry = x * sin + y * cos;
                rz = z;
            }
        }
        p[0] = rx * sx + part.origin()[0];
        p[1] = ry * sy + part.origin()[1];
        p[2] = rz * sz + part.origin()[2];
    }

    /** ModelRotation's matrix: rotate by -x quarters about X, then -y quarters about Y, around the block center. */
    private static void rotateModel(float[] p, int quartersX, int quartersY) {
        float x = p[0] - 0.5F;
        float y = p[1] - 0.5F;
        float z = p[2] - 0.5F;
        for (int turn = 0; turn < quartersX; turn++) {
            // -90 degrees about X: y' = z, z' = -y.
            float ny = z;
            float nz = -y;
            y = ny;
            z = nz;
        }
        for (int turn = 0; turn < quartersY; turn++) {
            // -90 degrees about Y: x' = -z, z' = x.
            float nx = -z;
            float nz = x;
            x = nx;
            z = nz;
        }
        p[0] = x + 0.5F;
        p[1] = y + 0.5F;
        p[2] = z + 0.5F;
    }

    /** EnumFacing.rotateAround(X): north -> down -> south -> up -> north. */
    private static int rotateX(int facing) {
        return switch (facing) {
            case 2 -> 0;
            case 0 -> 3;
            case 3 -> 1;
            case 1 -> 2;
            default -> facing;
        };
    }

    /** EnumFacing.rotateAround(Y): north -> east -> south -> west -> north. */
    private static int rotateY(int facing) {
        return switch (facing) {
            case 2 -> 5;
            case 5 -> 3;
            case 3 -> 4;
            case 4 -> 2;
            default -> facing;
        };
    }

    /** ModelRotation.rotateFace on vanilla face indices. */
    private static int rotateVanillaFace(int facing, int quartersX, int quartersY) {
        int result = facing;
        for (int turn = 0; turn < quartersX; turn++) {
            result = rotateX(result);
        }
        if (result > 1) {
            for (int turn = 0; turn < quartersY; turn++) {
                result = rotateY(result);
            }
        }
        return result;
    }

    private static int rotateFace(int vanillaFace, int quartersX, int quartersY) {
        return VANILLA_TO_CODE[rotateVanillaFace(vanillaFace, quartersX, quartersY)];
    }

    /** ModelRotation.rotateVertex: where a vertex is stored after the variant rotation. */
    private static int rotateVertex(int facing, int vertex, int quartersX, int quartersY) {
        int index = vertex;
        if (facing == 4 || facing == 5) {
            index = (index + quartersX) % 4;
        }
        int rotated = facing;
        for (int turn = 0; turn < quartersX; turn++) {
            rotated = rotateX(rotated);
        }
        if (rotated == 0 || rotated == 1) {
            index = (index + quartersY) % 4;
        }
        return index;
    }

    /** FaceBakery.getFacingFromVertexData: the face whose direction best matches the quad's normal. */
    private static int facingFromVertices(float[] positions) {
        float ax = positions[0] - positions[3];
        float ay = positions[1] - positions[4];
        float az = positions[2] - positions[5];
        float bx = positions[6] - positions[3];
        float by = positions[7] - positions[4];
        float bz = positions[8] - positions[5];
        // cross(b, a)
        float nx = by * az - bz * ay;
        float ny = bz * ax - bx * az;
        float nz = bx * ay - by * ax;
        float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        nx /= length;
        ny /= length;
        nz /= length;
        int best = -1;
        float bestDot = 0.0F;
        for (int face = 0; face < 6; face++) {
            float dot = nx * DIRECTION[face][0] + ny * DIRECTION[face][1] + nz * DIRECTION[face][2];
            if (dot >= 0.0F && dot > bestDot) {
                bestDot = dot;
                best = face;
            }
        }
        return best < 0 ? 1 : best;
    }

    /** FaceBakery.lockUv: texture coordinates from the vertex position, written with the face's uv rotation. */
    private static void lockUv(float[] positions, float[] uvs, int facing, int uvRotation) {
        float[] locked = new float[8];
        for (int vertex = 0; vertex < 4; vertex++) {
            float x = positions[vertex * 3];
            float y = positions[vertex * 3 + 1];
            float z = positions[vertex * 3 + 2];
            if (x < -0.1F || x >= 1.1F) {
                x -= (float) Math.floor(x);
            }
            if (y < -0.1F || y >= 1.1F) {
                y -= (float) Math.floor(y);
            }
            if (z < -0.1F || z >= 1.1F) {
                z -= (float) Math.floor(z);
            }
            float u;
            float v;
            switch (facing) {
                case 0 -> {
                    u = x * 16.0F;
                    v = (1.0F - z) * 16.0F;
                }
                case 1 -> {
                    u = x * 16.0F;
                    v = z * 16.0F;
                }
                case 2 -> {
                    u = (1.0F - x) * 16.0F;
                    v = (1.0F - y) * 16.0F;
                }
                case 3 -> {
                    u = x * 16.0F;
                    v = (1.0F - y) * 16.0F;
                }
                case 4 -> {
                    u = z * 16.0F;
                    v = (1.0F - y) * 16.0F;
                }
                default -> {
                    u = (1.0F - z) * 16.0F;
                    v = (1.0F - y) * 16.0F;
                }
            }
            int target = (vertex + (4 - uvRotation / 90)) % 4;
            locked[target * 2] = u / 16.0F;
            locked[target * 2 + 1] = v / 16.0F;
        }
        // Vanilla writes into the same array as it reads positions from; positions are untouched, so writing
        // all four afterwards is equivalent.
        System.arraycopy(locked, 0, uvs, 0, 8);
    }

    /**
     * FaceBakery.applyFacing: snaps an unrotated quad to its bounding box in the lighting face's canonical vertex
     * order, carrying each corner's texture coordinates along.
     */
    private static void applyFacing(float[] positions, float[] uvs, int facing) {
        float[] box = {999.0F, 999.0F, 999.0F, -999.0F, -999.0F, -999.0F};
        for (int vertex = 0; vertex < 4; vertex++) {
            for (int axis = 0; axis < 3; axis++) {
                float value = positions[vertex * 3 + axis];
                box[axis] = Math.min(box[axis], value);
                box[axis + 3] = Math.max(box[axis + 3], value);
            }
        }
        float[] bounds = new float[6];
        bounds[W] = box[0];
        bounds[D] = box[1];
        bounds[N] = box[2];
        bounds[E] = box[3];
        bounds[U] = box[4];
        bounds[S] = box[5];
        float[] oldPositions = positions.clone();
        float[] oldUvs = uvs.clone();
        for (int vertex = 0; vertex < 4; vertex++) {
            int[] info = VERTICES[facing][vertex];
            float x = bounds[info[0]];
            float y = bounds[info[1]];
            float z = bounds[info[2]];
            positions[vertex * 3] = x;
            positions[vertex * 3 + 1] = y;
            positions[vertex * 3 + 2] = z;
            for (int source = 0; source < 4; source++) {
                if (near(x, oldPositions[source * 3]) && near(y, oldPositions[source * 3 + 1])
                        && near(z, oldPositions[source * 3 + 2])) {
                    uvs[vertex * 2] = oldUvs[source * 2];
                    uvs[vertex * 2 + 1] = oldUvs[source * 2 + 1];
                }
            }
        }
    }

    /** MathHelper.epsilonEquals. */
    private static boolean near(float a, float b) {
        return Math.abs(b - a) < 1.0E-5F;
    }

    private static int faceIndex(String name) {
        for (int index = 0; index < 6; index++) {
            if (FACE_NAMES[index].equals(name)) {
                return index;
            }
        }
        return name.equals("bottom") ? 0 : -1;
    }

    /** ModelBlock.resolveTextureName: follows '#' references; unresolved names become "missingno". */
    private static String resolve(String reference, Map<String, String> textures) {
        String name = reference;
        for (int depth = 0; depth < 16 && name.startsWith("#"); depth++) {
            name = textures.get(name.substring(1));
            if (name == null) {
                return "missingno";
            }
        }
        if (name.startsWith("#")) {
            return "missingno";
        }
        return name.startsWith("minecraft:") ? name.substring(10) : name;
    }

    private static float[] vector(Object json) {
        if (!(json instanceof List<?> list) || list.size() != 3) {
            return null;
        }
        float[] result = new float[3];
        for (int index = 0; index < 3; index++) {
            if (!(list.get(index) instanceof Number number)) {
                return null;
            }
            result[index] = number.floatValue();
        }
        return result;
    }

    private static float[] vector4(Object json) {
        if (!(json instanceof List<?> list) || list.size() != 4) {
            return null;
        }
        float[] result = new float[4];
        for (int index = 0; index < 4; index++) {
            if (!(list.get(index) instanceof Number number)) {
                return null;
            }
            result[index] = number.floatValue();
        }
        return result;
    }
}
