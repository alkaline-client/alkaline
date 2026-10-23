package alkaline.renderer.vulkan.resource;

import org.lwjgl.vulkan.VK10;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.List;
import java.util.Objects;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState;
import org.lwjgl.vulkan.VkPipelineColorBlendStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDepthStencilStateCreateInfo;
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
import static org.lwjgl.vulkan.VK10.VK_COMPARE_OP_LESS_OR_EQUAL;
import static org.lwjgl.vulkan.VK10.VK_CULL_MODE_BACK_BIT;
import static org.lwjgl.vulkan.VK10.VK_CULL_MODE_NONE;
import static org.lwjgl.vulkan.VK10.VK_DYNAMIC_STATE_SCISSOR;
import static org.lwjgl.vulkan.VK10.VK_DYNAMIC_STATE_VIEWPORT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_FRONT_FACE_COUNTER_CLOCKWISE;
import static org.lwjgl.vulkan.VK10.VK_LOGIC_OP_COPY;
import static org.lwjgl.vulkan.VK10.VK_POLYGON_MODE_FILL;
import static org.lwjgl.vulkan.VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
import static org.lwjgl.vulkan.VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;
import static org.lwjgl.vulkan.VK10.VK_SAMPLE_COUNT_1_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_FRAGMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_VERTEX_BIT;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.VK_VERTEX_INPUT_RATE_INSTANCE;
import static org.lwjgl.vulkan.VK10.VK_VERTEX_INPUT_RATE_VERTEX;
import static org.lwjgl.vulkan.VK10.vkCreateGraphicsPipelines;
import static org.lwjgl.vulkan.VK10.vkCreatePipelineLayout;
import static org.lwjgl.vulkan.VK10.vkDestroyPipeline;
import static org.lwjgl.vulkan.VK10.vkDestroyPipelineLayout;
import static org.lwjgl.vulkan.VK10.vkDestroyShaderModule;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO;

/** A graphics pipeline built from a {@link PipelineSpec}; owned and rebuilt by its renderer. */
public final class VulkanPipeline implements AutoCloseable {
    static final int PUSH_STAGES = VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT;
    private static final int COLOR_MASK = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT
            | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT;

    private final VulkanRenderer owner;
    private final VkDevice device;
    private final PipelineSpec spec;
    private final long layout;
    private long pipeline;
    private boolean closed;

    VulkanPipeline(VulkanRenderer owner, VkDevice device, PipelineSpec spec, long imageSetLayout,
                   int mainColorFormat, int depthFormat) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.device = Objects.requireNonNull(device, "device");
        this.spec = Objects.requireNonNull(spec, "spec");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPipelineLayoutCreateInfo info = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO);
            if (spec.sampledImages() == 1) {
                info.pSetLayouts(stack.longs(imageSetLayout));
            } else if (spec.sampledImages() == 2) {
                info.pSetLayouts(stack.longs(imageSetLayout, imageSetLayout));
            } else if (spec.sampledImages() == 3) {
                info.pSetLayouts(stack.longs(imageSetLayout, imageSetLayout, imageSetLayout));
            }
            if (spec.pushConstantBytes() > 0) {
                info.pPushConstantRanges(VkPushConstantRange.calloc(1, stack)
                        .stageFlags(PUSH_STAGES).offset(0).size(spec.pushConstantBytes()));
            }
            LongBuffer address = stack.mallocLong(1);
            requireSuccess(vkCreatePipelineLayout(device, info, null, address), "vkCreatePipelineLayout");
            layout = address.get(0);
        }
        try {
            pipeline = build(mainColorFormat, depthFormat);
        } catch (RuntimeException | Error failure) {
            vkDestroyPipelineLayout(device, layout, null);
            throw failure;
        }
    }

    PipelineSpec spec() {
        return spec;
    }

    boolean targetsMainPass() {
        return spec.offscreenFormat() == null;
    }

    long handle() {
        ensureOpen();
        return pipeline;
    }

    long layout() {
        ensureOpen();
        return layout;
    }

    boolean belongsTo(VulkanRenderer renderer) {
        return owner == renderer;
    }

    /** Rebuilds a main-pass pipeline after the swapchain color format changed. Device must be idle. */
    void rebuild(int mainColorFormat, int depthFormat) {
        ensureOpen();
        long replacement = build(mainColorFormat, depthFormat);
        vkDestroyPipeline(device, pipeline, null);
        pipeline = replacement;
    }

    @Override
    public void close() {
        owner.closePipeline(this);
    }

    /** Destroys native objects; the renderer guarantees no frame still references them. */
    void destroy() {
        if (!closed) {
            closed = true;
            vkDestroyPipeline(device, pipeline, null);
            vkDestroyPipelineLayout(device, layout, null);
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Vulkan pipeline is closed.");
        }
    }

    private long build(int mainColorFormat, int depthFormat) {
        long vertexModule = 0;
        long fragmentModule = 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vertexModule = VulkanShaders.createModule(device, stack, spec.vertexShader());
            fragmentModule = VulkanShaders.createModule(device, stack, spec.fragmentShader());
            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
            stages.get(0).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK_SHADER_STAGE_VERTEX_BIT).module(vertexModule).pName(stack.UTF8("main"));
            stages.get(1).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fragmentModule).pName(stack.UTF8("main"));

            List<PipelineSpec.Binding> bindingList = spec.bindings();
            List<PipelineSpec.Attribute> attributeList = spec.attributes();
            VkPipelineVertexInputStateCreateInfo vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO);
            if (!bindingList.isEmpty()) {
                VkVertexInputBindingDescription.Buffer bindings =
                        VkVertexInputBindingDescription.calloc(bindingList.size(), stack);
                for (int index = 0; index < bindingList.size(); index++) {
                    PipelineSpec.Binding binding = bindingList.get(index);
                    bindings.get(index).binding(binding.binding()).stride(binding.stride())
                            .inputRate(binding.perInstance() ? VK_VERTEX_INPUT_RATE_INSTANCE : VK_VERTEX_INPUT_RATE_VERTEX);
                }
                vertexInput.pVertexBindingDescriptions(bindings);
            }
            if (!attributeList.isEmpty()) {
                VkVertexInputAttributeDescription.Buffer attributes =
                        VkVertexInputAttributeDescription.calloc(attributeList.size(), stack);
                for (int index = 0; index < attributeList.size(); index++) {
                    PipelineSpec.Attribute attribute = attributeList.get(index);
                    attributes.get(index).location(attribute.location()).binding(attribute.binding())
                            .format(attribute.format().vulkanFormat()).offset(attribute.offset());
                }
                vertexInput.pVertexAttributeDescriptions(attributes);
            }
            VkPipelineInputAssemblyStateCreateInfo inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO)
                    .topology(spec.topology() == PipelineSpec.Topology.TRIANGLE_STRIP
                            ? VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP : VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST)
                    .primitiveRestartEnable(false);
            VkPipelineViewportStateCreateInfo viewport = VkPipelineViewportStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO).viewportCount(1).scissorCount(1);
            VkPipelineRasterizationStateCreateInfo rasterization = VkPipelineRasterizationStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO)
                    .polygonMode(VK_POLYGON_MODE_FILL)
                    .cullMode(spec.cullBackFaces() ? VK_CULL_MODE_BACK_BIT : VK_CULL_MODE_NONE)
                    .frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE).lineWidth(1.0f);
            VkPipelineMultisampleStateCreateInfo multisampling = VkPipelineMultisampleStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO)
                    .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);
            VkPipelineColorBlendAttachmentState.Buffer blendAttachment =
                    VkPipelineColorBlendAttachmentState.calloc(1, stack).colorWriteMask(COLOR_MASK);
            switch (spec.blend()) {
                case NONE -> blendAttachment.blendEnable(false);
                case DEPTH_ONLY -> blendAttachment.blendEnable(false).colorWriteMask(0);
                case ALPHA -> blendAttachment.blendEnable(true)
                        .srcColorBlendFactor(VK_BLEND_FACTOR_SRC_ALPHA)
                        .dstColorBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA).colorBlendOp(VK_BLEND_OP_ADD)
                        .srcAlphaBlendFactor(VK_BLEND_FACTOR_ONE)
                        .dstAlphaBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA).alphaBlendOp(VK_BLEND_OP_ADD);
                case PREMULTIPLIED -> blendAttachment.blendEnable(true)
                        .srcColorBlendFactor(VK_BLEND_FACTOR_ONE)
                        .dstColorBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA).colorBlendOp(VK_BLEND_OP_ADD)
                        .srcAlphaBlendFactor(VK_BLEND_FACTOR_ONE)
                        .dstAlphaBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA).alphaBlendOp(VK_BLEND_OP_ADD);
                case SOURCE_SQUARED -> blendAttachment.blendEnable(true)
                        .srcColorBlendFactor(org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_SRC_COLOR)
                        .dstColorBlendFactor(VK_BLEND_FACTOR_ONE).colorBlendOp(VK_BLEND_OP_ADD)
                        .srcAlphaBlendFactor(org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_ZERO)
                        .dstAlphaBlendFactor(VK_BLEND_FACTOR_ONE).alphaBlendOp(VK_BLEND_OP_ADD);
                case INVERT -> colorBlend(blendAttachment, VK10.VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR,
                        VK10.VK_BLEND_FACTOR_ZERO);
                case MULTIPLY -> colorBlend(blendAttachment, VK10.VK_BLEND_FACTOR_DST_COLOR,
                        VK10.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA);
                case DODGE -> colorBlend(blendAttachment, VK10.VK_BLEND_FACTOR_ONE, VK10.VK_BLEND_FACTOR_ONE);
                case BURN -> colorBlend(blendAttachment, VK10.VK_BLEND_FACTOR_ZERO,
                        VK10.VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR);
                case SCREEN -> colorBlend(blendAttachment, VK10.VK_BLEND_FACTOR_ONE,
                        VK10.VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR);
                case OVERLAY -> colorBlend(blendAttachment, VK10.VK_BLEND_FACTOR_DST_COLOR,
                        VK10.VK_BLEND_FACTOR_SRC_COLOR);
                case ADDITIVE -> blendAttachment.blendEnable(true)
                        .srcColorBlendFactor(VK_BLEND_FACTOR_SRC_ALPHA)
                        .dstColorBlendFactor(VK_BLEND_FACTOR_ONE).colorBlendOp(VK_BLEND_OP_ADD)
                        .srcAlphaBlendFactor(VK_BLEND_FACTOR_ONE)
                        .dstAlphaBlendFactor(VK_BLEND_FACTOR_ONE).alphaBlendOp(VK_BLEND_OP_ADD);
            }
            VkPipelineColorBlendStateCreateInfo blending = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO)
                    .logicOpEnable(false).logicOp(VK_LOGIC_OP_COPY).pAttachments(blendAttachment);
            VkPipelineDepthStencilStateCreateInfo depth = VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO)
                    .depthTestEnable(spec.depthTest()).depthWriteEnable(spec.depthWrite())
                    .depthCompareOp(VK_COMPARE_OP_LESS_OR_EQUAL).minDepthBounds(0f).maxDepthBounds(1f);
            IntBuffer dynamicValues = stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR);
            VkPipelineDynamicStateCreateInfo dynamic = VkPipelineDynamicStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO).pDynamicStates(dynamicValues);
            boolean main = spec.offscreenFormat() == null;
            VkPipelineRenderingCreateInfo rendering = VkPipelineRenderingCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO)
                    .colorAttachmentCount(1)
                    .pColorAttachmentFormats(stack.ints(GammaSpace.attachmentFormat(
                            main ? mainColorFormat : spec.offscreenFormat().vulkanFormat(), spec.gammaSpace())))
                    .depthAttachmentFormat(main || spec.offscreenDepth() ? depthFormat : VK_FORMAT_UNDEFINED);
            VkGraphicsPipelineCreateInfo.Buffer info = VkGraphicsPipelineCreateInfo.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO)
                    .pNext(rendering.address()).pStages(stages).pVertexInputState(vertexInput)
                    .pInputAssemblyState(inputAssembly).pViewportState(viewport)
                    .pRasterizationState(rasterization).pMultisampleState(multisampling)
                    .pDepthStencilState(main || spec.offscreenDepth() ? depth : null)
                    .pColorBlendState(blending).pDynamicState(dynamic)
                    .layout(layout).renderPass(0L).subpass(0).basePipelineIndex(-1);
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

    private static void requireSuccess(int result, String operation) {
        if (result != VK_SUCCESS) {
            throw new IllegalStateException(operation + " failed with VkResult " + result);
        }
    }

    /** A color-only blend (destination alpha kept): the custom sky's modes. */
    private static void colorBlend(VkPipelineColorBlendAttachmentState.Buffer attachment, int source, int destination) {
        attachment.blendEnable(true).srcColorBlendFactor(source).dstColorBlendFactor(destination)
                .colorBlendOp(VK_BLEND_OP_ADD).srcAlphaBlendFactor(VK10.VK_BLEND_FACTOR_ZERO)
                .dstAlphaBlendFactor(VK10.VK_BLEND_FACTOR_ONE).alphaBlendOp(VK_BLEND_OP_ADD);
    }
}
