package alkaline.renderer.vulkan.resource;

import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16_UNORM;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32B32A32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32B32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R32_UINT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_SNORM;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;

/** Vertex attribute encodings accepted by {@link PipelineSpec}. */
public enum VertexFormat {
    FLOAT1(VK_FORMAT_R32_SFLOAT),
    FLOAT2(VK_FORMAT_R32G32_SFLOAT),
    FLOAT3(VK_FORMAT_R32G32B32_SFLOAT),
    FLOAT4(VK_FORMAT_R32G32B32A32_SFLOAT),
    UINT1(VK_FORMAT_R32_UINT),
    UINT2(org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32_UINT),
    UINT4(org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32B32A32_UINT),
    UNORM8X4(VK_FORMAT_R8G8B8A8_UNORM),
    SNORM8X4(VK_FORMAT_R8G8B8A8_SNORM),
    UNORM16X2(VK_FORMAT_R16G16_UNORM);

    private final int vulkanFormat;

    VertexFormat(int vulkanFormat) {
        this.vulkanFormat = vulkanFormat;
    }

    int vulkanFormat() {
        return vulkanFormat;
    }
}
