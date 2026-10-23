package alkaline.renderer.vulkan.resource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;

import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.vkCreateShaderModule;

/** Loads packaged, build-verified SPIR-V shader resources (base64 with leading comment lines). */
final class VulkanShaders {
    static final String ROOT = "/alkaline/renderer/vulkan/shaders/";

    private VulkanShaders() {
    }

    static long createModule(VkDevice device, MemoryStack stack, String shaderName) {
        String path = ROOT + shaderName + ".spv.base64";
        byte[] encoded;
        try (InputStream input = VulkanShaders.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalStateException("Missing owned Vulkan shader resource " + path);
            }
            encoded = input.readAllBytes();
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to read owned Vulkan shader resource " + path, failure);
        }
        StringBuilder payload = new StringBuilder(encoded.length);
        for (String line : new String(encoded, StandardCharsets.US_ASCII).split("\\R")) {
            if (!line.stripLeading().startsWith("//")) {
                payload.append(line);
            }
        }
        byte[] decoded = Base64.getDecoder().decode(payload.toString());
        if (decoded.length == 0 || decoded.length % Integer.BYTES != 0) {
            throw new IllegalStateException("Packaged SPIR-V resource has an invalid byte length: " + path);
        }
        // Heap, not the thread's 64 KiB memory stack: larger shaders would overflow it.
        ByteBuffer code = org.lwjgl.system.MemoryUtil.memAlloc(decoded.length);
        try {
            code.put(decoded).flip();
            VkShaderModuleCreateInfo info = VkShaderModuleCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO).pCode(code);
            LongBuffer address = stack.mallocLong(1);
            int result = vkCreateShaderModule(device, info, null, address);
            if (result != VK_SUCCESS) {
                throw new IllegalStateException("vkCreateShaderModule " + shaderName + " failed with VkResult " + result);
            }
            return address.get(0);
        } finally {
            org.lwjgl.system.MemoryUtil.memFree(code);
        }
    }
}
