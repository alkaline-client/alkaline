package alkaline.renderer.vulkan.resource;

import alkaline.renderer.api.BufferDescriptor;
import alkaline.renderer.api.BufferUsage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VulkanBufferTest {
    @Test
    void rejectsUnsupportedLargeMappingsBeforeCallingVulkan() {
        assertThrows(IllegalArgumentException.class, () -> VulkanBuffer.validateSize((long) Integer.MAX_VALUE + 1));
    }

    @Test
    void factoryGuardRejectsDeviceLocalDescriptor() {
        BufferDescriptor descriptor = new BufferDescriptor(16L,
                EnumSet.of(BufferUsage.VERTEX), BufferDescriptor.MemoryAccess.DEVICE_LOCAL);
        assertThrows(IllegalArgumentException.class, () -> VulkanResourceFactory.requireHostVisible(descriptor));
    }

    @Test
    void emptyHostVisibleUploadIsANoopWithoutNativeHandles() {
        VulkanBuffer buffer = new VulkanBuffer(null, 0L, 0L, 16L, 16L, true, false, 1L);
        buffer.upload(0L, ByteBuffer.allocate(0));
    }

    @Test
    void deviceLocalMeshValidationRejectsMalformedVerticesAndIndicesWithoutVulkan() {
        assertThrows(IllegalArgumentException.class,
                () -> VulkanRenderer.validateIndexedMesh(new float[5], new int[] {0, 0, 0}));
        assertThrows(IllegalArgumentException.class,
                () -> VulkanRenderer.validateIndexedMesh(new float[6], new int[] {0, 0}));
        assertThrows(IllegalArgumentException.class,
                () -> VulkanRenderer.validateIndexedMesh(new float[6], new int[] {0, 0, 1}));
    }

    @Test
    void transformPackingWritesColumnMajorMatrixWithoutAllocatingPerDraw() {
        ByteBuffer destination = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder());
        float[] transform = {
                2, 0, 0, 0,
                0, 3, 0, 0,
                0, 0, 4, 0,
                5, 6, 7, 1
        };

        VulkanRenderer.writeObjectToClipTransform(destination, transform);

        for (int index = 0; index < transform.length; index++) {
            assertEquals(transform[index], destination.getFloat(index * Float.BYTES));
        }
        assertThrows(IllegalArgumentException.class,
                () -> VulkanRenderer.writeObjectToClipTransform(destination, new float[15]));
        transform[0] = Float.NaN;
        assertThrows(IllegalArgumentException.class,
                () -> VulkanRenderer.writeObjectToClipTransform(destination, transform));
    }

    @Test
    void clearColorValidationRejectsNanInfinityAndOutOfRangeValues() {
        assertThrows(IllegalArgumentException.class, () -> VulkanRenderer.validateClearColor(Float.NaN, 0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> VulkanRenderer.validateClearColor(0, Float.POSITIVE_INFINITY, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> VulkanRenderer.validateClearColor(0, 0, -0.1f, 1));
        assertThrows(IllegalArgumentException.class, () -> VulkanRenderer.validateClearColor(0, 0, 0, 1.1f));
    }

    @Test
    void clearColorValidationAcceptsNormalizedValues() {
        VulkanRenderer.validateClearColor(0.0f, 0.5f, 1.0f, 1.0f);
    }

    @Test
    void deviceLocalMeshValidationRejectsNonFiniteAttributes() {
        assertThrows(IllegalArgumentException.class,
                () -> VulkanRenderer.validateIndexedMesh(
                        new float[] {Float.NaN, 0, 0, 1, 1, 1}, new int[] {0, 0, 0}));
    }

    @Test
    void failedSwapchainRecreationPolicyRejectsFurtherRendererUse() {
        VulkanRenderer.requireUsableRenderer(false);
        assertThrows(IllegalStateException.class, () -> VulkanRenderer.requireUsableRenderer(true));
    }

    @Test
    void deviceLocalUploadAlignmentRejectsUnalignedOffsetsAndLengths() {
        VulkanRenderer.validateDeviceLocalUpload(0, 4);
        VulkanRenderer.validateDeviceLocalUpload(8, 12);
        assertThrows(IllegalArgumentException.class, () -> VulkanRenderer.validateDeviceLocalUpload(2, 4));
        assertThrows(IllegalArgumentException.class, () -> VulkanRenderer.validateDeviceLocalUpload(0, 3));
        assertThrows(IllegalArgumentException.class, () -> VulkanRenderer.validateDeviceLocalUpload(-4, 4));
        assertThrows(IllegalArgumentException.class, () -> VulkanRenderer.validateDeviceLocalUpload(0, -4));
    }

    @Test
    void texturedMeshValidationRejectsInvalidUvColorAndIndices() {
        float[] valid = {
                0, 0, 0, 0, 0, 1, 1, 1, 1,
                1, 0, 0, 1, 0, 1, 1, 1, 1,
                0, 1, 0, 0, 1, 1, 1, 1, 1
        };
        VulkanRenderer.validateTexturedMesh(valid, new int[] {0, 1, 2});
        float[] badUv = valid.clone();
        badUv[3] = 1.1f;
        assertThrows(IllegalArgumentException.class,
                () -> VulkanRenderer.validateTexturedMesh(badUv, new int[] {0, 1, 2}));
        float[] badAlpha = valid.clone();
        badAlpha[8] = -0.1f;
        assertThrows(IllegalArgumentException.class,
                () -> VulkanRenderer.validateTexturedMesh(badAlpha, new int[] {0, 1, 2}));
        assertThrows(IllegalArgumentException.class,
                () -> VulkanRenderer.validateTexturedMesh(valid, new int[] {0, 1, 3}));
        assertThrows(IllegalArgumentException.class,
                () -> VulkanRenderer.validateTexturedMesh(new float[8], new int[] {0, 0, 0}));
    }

    @Test
    void deviceLocalMeshValidationAcceptsOneObjectSpaceTriangle() {
        VulkanRenderer.validateIndexedMesh(
                new float[] {0, 0, 0, 1, 0, 0, 1, 0, 0, 0, 1, 0, 0, 1, 0, 0, 0, 1},
                new int[] {0, 1, 2});
    }
}
