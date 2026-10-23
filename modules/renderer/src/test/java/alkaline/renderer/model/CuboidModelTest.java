package alkaline.renderer.model;

import alkaline.renderer.math.Mat4;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CuboidModelTest {
    @Test
    void bakesSixQuadsPerBoxWithPartIndices() {
        CuboidModel model = PlayerModels.player(false);
        BakedModel baked = ModelMesher.bake(model);
        assertEquals(12 * 6 * 4 * ModelMesher.VERTEX_BYTES, baked.vertexBytes());
        assertEquals(12 * 6 * 6, baked.indexCount());
        ByteBuffer data = baked.data();
        // The hat (part 6) starts after six base parts' 24 vertices each.
        assertEquals(6, data.getInt(6 * 24 * ModelMesher.VERTEX_BYTES + 24));
    }

    @Test
    void frontFaceUsesTheVanillaUnwrap() {
        CuboidModel model = CuboidModel.builder("cube", 64, 64)
                .part("head", null, 0, 0, 0, new ModelBox(0, 0, -4, -8, -4, 8, 8, 8, 0f, false))
                .build();
        ByteBuffer data = ModelMesher.bake(model).data();
        // Quad 4 is the z0 (front) face: u from 8..16, v from 8..16 over a 64-texel texture.
        int first = 4 * 4 * ModelMesher.VERTEX_BYTES;
        float minU = 1f;
        float maxU = 0f;
        for (int vertex = 0; vertex < 4; vertex++) {
            float u = data.getFloat(first + vertex * ModelMesher.VERTEX_BYTES + 12);
            minU = Math.min(minU, u);
            maxU = Math.max(maxU, u);
            assertEquals(-4f / 16f, data.getFloat(first + vertex * ModelMesher.VERTEX_BYTES + 8), 1e-6);
        }
        assertEquals(8f / 64f, minU, 1e-6);
        assertEquals(16f / 64f, maxU, 1e-6);
        // Front normal points toward -Z.
        assertEquals(-127, data.get(first + 22));
    }

    @Test
    void childPartsInheritParentTransforms() {
        CuboidModel model = CuboidModel.builder("chain", 16, 16)
                .part("root", null, 16, 0, 0)
                .part("child", "root", 0, 16, 0)
                .build();
        ModelPose pose = new ModelPose(model);
        pose.rotation(0, 0f, 0f, (float) (Math.PI / 2));
        float[] out = new float[2 * PoseMath.FLOATS_PER_PART];
        PoseMath.resolve(pose, Mat4.identity(new float[16]), out);
        // Root at x=1 block, rotated 90 degrees about Z; the child's +1 block Y pivot maps to -1 block X.
        assertEquals(0f, out[12 + 3], 1e-6);
        assertEquals(0f, out[12 + 7], 1e-6);
        assertArrayEquals(new float[]{0f, -1f, 0f}, new float[]{out[12], out[13], out[14]}, 1e-6f);
    }

    @Test
    void hiddenParentsHideChildren() {
        CuboidModel model = PlayerModels.player(true);
        ModelPose pose = new ModelPose(model);
        pose.visible(model.partIndex(PlayerModels.HEAD), false);
        float[] out = new float[model.partCount() * PoseMath.FLOATS_PER_PART];
        PoseMath.resolve(pose, Mat4.identity(new float[16]), out);
        int hat = model.partIndex(PlayerModels.HAT) * PoseMath.FLOATS_PER_PART;
        for (int index = hat; index < hat + PoseMath.FLOATS_PER_PART; index++) {
            assertEquals(0f, out[index]);
        }
    }

    @Test
    void partsMustBeDeclaredParentFirst() {
        assertThrows(IllegalArgumentException.class,
                () -> CuboidModel.builder("bad", 16, 16).part("child", "missing", 0, 0, 0));
    }
}
