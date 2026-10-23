package alkaline.renderer.vulkan.resource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState;
import org.lwjgl.vulkan.VkPipelineColorBlendStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDynamicStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineInputAssemblyStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineMultisampleStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRasterizationStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRenderingCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPipelineVertexInputStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineViewportStateCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkVertexInputAttributeDescription;
import org.lwjgl.vulkan.VkVertexInputBindingDescription;

import static org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_ONE;
import static org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_SRC_ALPHA;
import static org.lwjgl.vulkan.VK10.VK_BLEND_OP_ADD;
import static org.lwjgl.vulkan.VK10.VK_COLOR_COMPONENT_A_BIT;
import static org.lwjgl.vulkan.VK10.VK_COLOR_COMPONENT_B_BIT;
import static org.lwjgl.vulkan.VK10.VK_COLOR_COMPONENT_G_BIT;
import static org.lwjgl.vulkan.VK10.VK_COLOR_COMPONENT_R_BIT;
import static org.lwjgl.vulkan.VK10.VK_CULL_MODE_NONE;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32B32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32B32A32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FRONT_FACE_COUNTER_CLOCKWISE;
import static org.lwjgl.vulkan.VK10.VK_LOGIC_OP_COPY;
import static org.lwjgl.vulkan.VK10.VK_POLYGON_MODE_FILL;
import static org.lwjgl.vulkan.VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
import static org.lwjgl.vulkan.VK10.VK_SAMPLE_COUNT_1_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_FRAGMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_VERTEX_BIT;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.vkCreateDescriptorSetLayout;
import static org.lwjgl.vulkan.VK10.vkCreateGraphicsPipelines;
import static org.lwjgl.vulkan.VK10.vkCreatePipelineLayout;
import static org.lwjgl.vulkan.VK10.vkCreateShaderModule;
import static org.lwjgl.vulkan.VK10.vkDestroyDescriptorSetLayout;
import static org.lwjgl.vulkan.VK10.vkDestroyPipeline;
import static org.lwjgl.vulkan.VK10.vkDestroyPipelineLayout;
import static org.lwjgl.vulkan.VK10.vkDestroyShaderModule;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO;

/** Owns the color-only and sampled-texture pipelines for the current swapchain format. */
final class VulkanGraphicsPipeline implements AutoCloseable {
    private static final int TRANSFORM_BYTES = 16 * Float.BYTES;
    private static final int COLOR_MASK = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT
            | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT;

    private final VkDevice device;
    private long layout;
    private long pipeline;
    private long textureDescriptorSetLayout;
    private long texturedLayout;
    private long texturedPipeline;

    VulkanGraphicsPipeline(VkDevice device) {
        this.device = Objects.requireNonNull(device, "device");
    }

    private int depthFormat;

    void create(int format, int depthAttachmentFormat) {
        depthFormat = depthAttachmentFormat;
        if (layout == 0) {
            createLayouts();
        }
        createPipelines(format);
    }

    void recreate(int format, int depthAttachmentFormat) {
        depthFormat = depthAttachmentFormat;
        destroyPipelines();
        createPipelines(format);
    }

    long pipelineLayout() {
        if (layout == 0) {
            throw new IllegalStateException("Graphics pipeline layout is not available.");
        }
        return layout;
    }

    long pipeline() {
        if (pipeline == 0) {
            throw new IllegalStateException("Graphics pipeline is not available.");
        }
        return pipeline;
    }

    long texturedPipelineLayout() {
        if (texturedLayout == 0) {
            throw new IllegalStateException("Textured pipeline layout is not available.");
        }
        return texturedLayout;
    }

    long texturedPipeline() {
        if (texturedPipeline == 0) {
            throw new IllegalStateException("Textured graphics pipeline is not available.");
        }
        return texturedPipeline;
    }

    long textureDescriptorSetLayout() {
        if (textureDescriptorSetLayout == 0) {
            throw new IllegalStateException("Texture descriptor-set layout is not available.");
        }
        return textureDescriptorSetLayout;
    }

    @Override
    public void close() {
        destroyPipelines();
        if (texturedLayout != 0) {
            vkDestroyPipelineLayout(device, texturedLayout, null);
            texturedLayout = 0;
        }
        if (layout != 0) {
            vkDestroyPipelineLayout(device, layout, null);
            layout = 0;
        }
        if (textureDescriptorSetLayout != 0) {
            vkDestroyDescriptorSetLayout(device, textureDescriptorSetLayout, null);
            textureDescriptorSetLayout = 0;
        }
    }

    private void createLayouts() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPushConstantRange.Buffer push = VkPushConstantRange.calloc(1, stack)
                    .stageFlags(VK_SHADER_STAGE_VERTEX_BIT).offset(0).size(TRANSFORM_BYTES);
            VkPipelineLayoutCreateInfo basicInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO).pPushConstantRanges(push);
            LongBuffer address = stack.mallocLong(1);
            requireSuccess(vkCreatePipelineLayout(device, basicInfo, null, address), "vkCreatePipelineLayout basic");
            layout = address.get(0);

            VkDescriptorSetLayoutBinding.Buffer binding = VkDescriptorSetLayoutBinding.calloc(1, stack)
                    .binding(0).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
            VkDescriptorSetLayoutCreateInfo setInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO).pBindings(binding);
            requireSuccess(vkCreateDescriptorSetLayout(device, setInfo, null, address), "vkCreateDescriptorSetLayout texture");
            textureDescriptorSetLayout = address.get(0);

            VkPipelineLayoutCreateInfo textureInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                    .pSetLayouts(stack.longs(textureDescriptorSetLayout)).pPushConstantRanges(push);
            requireSuccess(vkCreatePipelineLayout(device, textureInfo, null, address), "vkCreatePipelineLayout texture");
            texturedLayout = address.get(0);
        }
    }

    private void createPipelines(int format) {
        pipeline = createPipeline(format, false);
        try {
            texturedPipeline = createPipeline(format, true);
        } catch (RuntimeException | Error failure) {
            vkDestroyPipeline(device, pipeline, null);
            pipeline = 0;
            throw failure;
        }
    }

    private long createPipeline(int format, boolean textured) {
        long vertexModule = 0;
        long fragmentModule = 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            String prefix = textured ? "textured" : "basic";
            vertexModule = createShaderModule(stack, "/alkaline/renderer/vulkan/shaders/" + prefix + ".vert.spv.base64");
            fragmentModule = createShaderModule(stack, "/alkaline/renderer/vulkan/shaders/" + prefix + ".frag.spv.base64");
            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
            stages.get(0).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK_SHADER_STAGE_VERTEX_BIT).module(vertexModule).pName(stack.UTF8("main"));
            stages.get(1).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fragmentModule).pName(stack.UTF8("main"));

            int stride = (textured ? 9 : 6) * Float.BYTES;
            VkVertexInputBindingDescription.Buffer bindings = VkVertexInputBindingDescription.calloc(1, stack)
                    .binding(0).stride(stride).inputRate(org.lwjgl.vulkan.VK10.VK_VERTEX_INPUT_RATE_VERTEX);
            VkVertexInputAttributeDescription.Buffer attributes = VkVertexInputAttributeDescription.calloc(textured ? 3 : 2, stack);
            attributes.get(0).location(0).binding(0).format(VK_FORMAT_R32G32B32_SFLOAT).offset(0);
            if (textured) {
                attributes.get(1).location(1).binding(0).format(VK_FORMAT_R32G32_SFLOAT).offset(3 * Float.BYTES);
                attributes.get(2).location(2).binding(0).format(VK_FORMAT_R32G32B32A32_SFLOAT).offset(5 * Float.BYTES);
            } else {
                attributes.get(1).location(1).binding(0).format(VK_FORMAT_R32G32B32_SFLOAT).offset(3 * Float.BYTES);
            }
            VkPipelineVertexInputStateCreateInfo vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO)
                    .pVertexBindingDescriptions(bindings).pVertexAttributeDescriptions(attributes);
            VkPipelineInputAssemblyStateCreateInfo inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO)
                    .topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST).primitiveRestartEnable(false);
            VkPipelineViewportStateCreateInfo viewport = VkPipelineViewportStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO).viewportCount(1).scissorCount(1);
            VkPipelineRasterizationStateCreateInfo rasterization = VkPipelineRasterizationStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO)
                    .depthClampEnable(false).rasterizerDiscardEnable(false).polygonMode(VK_POLYGON_MODE_FILL)
                    .cullMode(VK_CULL_MODE_NONE).frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE)
                    .depthBiasEnable(false).lineWidth(1.0f);
            VkPipelineMultisampleStateCreateInfo multisampling = VkPipelineMultisampleStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO)
                    .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT).sampleShadingEnable(false);
            VkPipelineColorBlendAttachmentState.Buffer blendAttachment = VkPipelineColorBlendAttachmentState.calloc(1, stack)
                    .blendEnable(textured).colorWriteMask(COLOR_MASK);
            if (textured) {
                blendAttachment.srcColorBlendFactor(VK_BLEND_FACTOR_SRC_ALPHA)
                        .dstColorBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA).colorBlendOp(VK_BLEND_OP_ADD)
                        .srcAlphaBlendFactor(VK_BLEND_FACTOR_ONE)
                        .dstAlphaBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA).alphaBlendOp(VK_BLEND_OP_ADD);
            }
            VkPipelineColorBlendStateCreateInfo blending = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO)
                    .logicOpEnable(false).logicOp(VK_LOGIC_OP_COPY).pAttachments(blendAttachment);
            IntBuffer dynamicValues = stack.ints(org.lwjgl.vulkan.VK10.VK_DYNAMIC_STATE_VIEWPORT,
                    org.lwjgl.vulkan.VK10.VK_DYNAMIC_STATE_SCISSOR);
            VkPipelineDynamicStateCreateInfo dynamic = VkPipelineDynamicStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO).pDynamicStates(dynamicValues);
            IntBuffer colorFormat = stack.ints(format);
            VkPipelineRenderingCreateInfo rendering = VkPipelineRenderingCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO)
                    .colorAttachmentCount(1).pColorAttachmentFormats(colorFormat)
                    .depthAttachmentFormat(depthFormat);
            // The main pass carries a depth attachment; these 2D pipelines neither test nor write it.
            org.lwjgl.vulkan.VkPipelineDepthStencilStateCreateInfo depthState =
                    org.lwjgl.vulkan.VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                            .sType(org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO)
                            .depthTestEnable(false).depthWriteEnable(false)
                            .depthCompareOp(org.lwjgl.vulkan.VK10.VK_COMPARE_OP_ALWAYS);
            VkGraphicsPipelineCreateInfo.Buffer info = VkGraphicsPipelineCreateInfo.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO)
                    .pNext(rendering.address()).pStages(stages).pVertexInputState(vertexInput)
                    .pInputAssemblyState(inputAssembly).pViewportState(viewport)
                    .pRasterizationState(rasterization).pMultisampleState(multisampling)
                    .pDepthStencilState(depthState)
                    .pColorBlendState(blending).pDynamicState(dynamic)
                    .layout(textured ? texturedLayout : layout).renderPass(0L).subpass(0)
                    .basePipelineIndex(-1);
            LongBuffer address = stack.mallocLong(1);
            requireSuccess(vkCreateGraphicsPipelines(device, 0L, info, null, address), "vkCreateGraphicsPipelines");
            return address.get(0);
        } finally {
            if (vertexModule != 0) {
                vkDestroyShaderModule(device, vertexModule, null);
            }
            if (fragmentModule != 0) {
                vkDestroyShaderModule(device, fragmentModule, null);
            }
        }
    }

    private long createShaderModule(MemoryStack stack, String path) {
        byte[] encoded;
        try (InputStream input = VulkanGraphicsPipeline.class.getResourceAsStream(path)) {
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
        ByteBuffer code = stack.malloc(decoded.length);
        code.put(decoded).flip();
        VkShaderModuleCreateInfo info = VkShaderModuleCreateInfo.calloc(stack)
                .sType(org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO).pCode(code);
        LongBuffer address = stack.mallocLong(1);
        requireSuccess(vkCreateShaderModule(device, info, null, address), "vkCreateShaderModule");
        return address.get(0);
    }

    private void destroyPipelines() {
        if (pipeline != 0) {
            vkDestroyPipeline(device, pipeline, null);
            pipeline = 0;
        }
        if (texturedPipeline != 0) {
            vkDestroyPipeline(device, texturedPipeline, null);
            texturedPipeline = 0;
        }
    }

    private static void requireSuccess(int result, String operation) {
        if (result != VK_SUCCESS) {
            throw new IllegalStateException(operation + " failed with VkResult " + result);
        }
    }
}
