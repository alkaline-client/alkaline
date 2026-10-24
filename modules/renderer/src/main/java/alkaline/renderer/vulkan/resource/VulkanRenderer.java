package alkaline.renderer.vulkan.resource;

import alkaline.platform.sdl.SdlWindow;
import alkaline.renderer.api.BufferDescriptor;
import alkaline.renderer.api.RenderBuffer;
import alkaline.renderer.api.Renderer;
import alkaline.renderer.image.ImageSampling;
import alkaline.renderer.image.RgbaImage;
import alkaline.renderer.texture.TexturedMeshData;
import alkaline.renderer.vulkan.QueueFamilies;
import alkaline.renderer.vulkan.SwapchainSelection;
import alkaline.renderer.vulkan.VulkanApiSupport;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan13Features;
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
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderingAttachmentInfo;
import org.lwjgl.vulkan.VkRenderingInfo;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkSubmitInfo;
import org.lwjgl.vulkan.VkSurfaceCapabilitiesKHR;
import org.lwjgl.vulkan.VkSurfaceFormatKHR;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;
import org.lwjgl.vulkan.VkViewport;
import org.lwjgl.vulkan.VkVertexInputAttributeDescription;
import org.lwjgl.vulkan.VkVertexInputBindingDescription;
import org.lwjgl.vulkan.VkCommandBufferInheritanceInfo;
import org.lwjgl.vulkan.VkExtensionProperties;

import static org.lwjgl.sdl.SDLError.SDL_GetError;
import static org.lwjgl.sdl.SDLVideo.SDL_GetWindowSizeInPixels;
import static org.lwjgl.sdl.SDLVulkan.SDL_Vulkan_CreateSurface;
import static org.lwjgl.sdl.SDLVulkan.SDL_Vulkan_DestroySurface;
import static org.lwjgl.sdl.SDLVulkan.SDL_Vulkan_GetInstanceExtensions;
import static org.lwjgl.system.MemoryUtil.memAlloc;
import static org.lwjgl.system.MemoryUtil.memAllocFloat;
import static org.lwjgl.system.MemoryUtil.memAllocInt;
import static org.lwjgl.system.MemoryUtil.memAllocLong;
import static org.lwjgl.system.MemoryUtil.memAllocPointer;
import static org.lwjgl.system.MemoryUtil.memByteBuffer;
import static org.lwjgl.system.MemoryUtil.memFree;
import static org.lwjgl.vulkan.KHRSurface.VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
import static org.lwjgl.vulkan.KHRSurface.VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
import static org.lwjgl.vulkan.KHRSurface.VK_PRESENT_MODE_FIFO_KHR;
import static org.lwjgl.vulkan.KHRSurface.VK_PRESENT_MODE_MAILBOX_KHR;
import static org.lwjgl.vulkan.KHRSurface.VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
import static org.lwjgl.vulkan.KHRSurface.vkDestroySurfaceKHR;
import static org.lwjgl.vulkan.KHRSurface.vkGetPhysicalDeviceSurfaceCapabilitiesKHR;
import static org.lwjgl.vulkan.KHRSurface.vkGetPhysicalDeviceSurfaceFormatsKHR;
import static org.lwjgl.vulkan.KHRSurface.vkGetPhysicalDeviceSurfacePresentModesKHR;
import static org.lwjgl.vulkan.KHRSurface.vkGetPhysicalDeviceSurfaceSupportKHR;
import static org.lwjgl.vulkan.KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR;
import static org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
import static org.lwjgl.vulkan.KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRSwapchain.VK_SUBOPTIMAL_KHR;
import static org.lwjgl.vulkan.KHRSwapchain.VK_STRUCTURE_TYPE_PRESENT_INFO_KHR;
import static org.lwjgl.vulkan.KHRSwapchain.VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR;
import static org.lwjgl.vulkan.KHRSwapchain.vkAcquireNextImageKHR;
import static org.lwjgl.vulkan.KHRSwapchain.vkCreateSwapchainKHR;
import static org.lwjgl.vulkan.KHRSwapchain.vkDestroySwapchainKHR;
import static org.lwjgl.vulkan.KHRSwapchain.vkGetSwapchainImagesKHR;
import static org.lwjgl.vulkan.KHRSwapchain.vkQueuePresentKHR;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_INDEX_READ_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_VERTEX_ATTRIBUTE_READ_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_INDEX_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_COLOR_COMPONENT_A_BIT;
import static org.lwjgl.vulkan.VK10.VK_COLOR_COMPONENT_B_BIT;
import static org.lwjgl.vulkan.VK10.VK_COLOR_COMPONENT_G_BIT;
import static org.lwjgl.vulkan.VK10.VK_COLOR_COMPONENT_R_BIT;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_BUFFER_LEVEL_PRIMARY;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_CULL_MODE_NONE;
import static org.lwjgl.vulkan.VK10.VK_FENCE_CREATE_SIGNALED_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8A8_SRGB;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32B32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FRONT_FACE_COUNTER_CLOCKWISE;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_INDEX_TYPE_UINT32;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_LOGIC_OP_COPY;
import static org.lwjgl.vulkan.VK10.VK_NOT_READY;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_BIND_POINT_GRAPHICS;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
import static org.lwjgl.vulkan.VK10.VK_POLYGON_MODE_FILL;
import static org.lwjgl.vulkan.VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
import static org.lwjgl.vulkan.VK10.VK_QUEUE_FAMILY_IGNORED;
import static org.lwjgl.vulkan.VK10.VK_QUEUE_GRAPHICS_BIT;
import static org.lwjgl.vulkan.VK10.VK_SAMPLE_COUNT_1_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHARING_MODE_CONCURRENT;
import static org.lwjgl.vulkan.VK10.VK_SHARING_MODE_EXCLUSIVE;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_VERTEX_BIT;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_APPLICATION_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_SUBMIT_INFO;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.VK_TIMEOUT;
import static org.lwjgl.vulkan.VK10.VK_TRUE;
import static org.lwjgl.vulkan.VK10.VK_WHOLE_SIZE;
import static org.lwjgl.vulkan.VK10.vkAllocateCommandBuffers;
import static org.lwjgl.vulkan.VK10.vkBeginCommandBuffer;
import static org.lwjgl.vulkan.VK10.vkCmdBindDescriptorSets;
import static org.lwjgl.vulkan.VK10.vkCmdBindIndexBuffer;
import static org.lwjgl.vulkan.VK10.vkCmdBindPipeline;
import static org.lwjgl.vulkan.VK10.vkCmdBindVertexBuffers;
import static org.lwjgl.vulkan.VK10.vkCmdCopyBuffer;
import static org.lwjgl.vulkan.VK10.vkCmdDrawIndexed;
import static org.lwjgl.vulkan.VK10.vkCmdPipelineBarrier;
import static org.lwjgl.vulkan.VK10.vkCreateBuffer;
import static org.lwjgl.vulkan.VK10.vkCreateCommandPool;
import static org.lwjgl.vulkan.VK10.vkCreateFence;
import static org.lwjgl.vulkan.VK10.vkCreateImageView;
import static org.lwjgl.vulkan.VK10.vkCreateInstance;
import static org.lwjgl.vulkan.VK10.vkCreatePipelineLayout;
import static org.lwjgl.vulkan.VK10.vkCreateSemaphore;
import static org.lwjgl.vulkan.VK10.vkCreateShaderModule;
import static org.lwjgl.vulkan.VK10.vkDestroyBuffer;
import static org.lwjgl.vulkan.VK10.vkDestroyCommandPool;
import static org.lwjgl.vulkan.VK10.vkDestroyDevice;
import static org.lwjgl.vulkan.VK10.vkDestroyFence;
import static org.lwjgl.vulkan.VK10.vkDestroyImageView;
import static org.lwjgl.vulkan.VK10.vkDestroyInstance;
import static org.lwjgl.vulkan.VK10.vkDestroyPipeline;
import static org.lwjgl.vulkan.VK10.vkDestroyPipelineLayout;
import static org.lwjgl.vulkan.VK10.vkDestroySemaphore;
import static org.lwjgl.vulkan.VK10.vkDestroyShaderModule;
import static org.lwjgl.vulkan.VK10.vkDeviceWaitIdle;
import static org.lwjgl.vulkan.VK10.vkEndCommandBuffer;
import static org.lwjgl.vulkan.VK10.vkFreeCommandBuffers;
import static org.lwjgl.vulkan.VK10.vkGetDeviceQueue;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceProperties;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceQueueFamilyProperties;
import static org.lwjgl.vulkan.VK10.vkResetCommandBuffer;
import static org.lwjgl.vulkan.VK10.vkResetFences;
import static org.lwjgl.vulkan.VK10.vkQueueSubmit;
import static org.lwjgl.vulkan.VK10.vkQueueWaitIdle;
import static org.lwjgl.vulkan.VK10.vkCreateDevice;
import static org.lwjgl.vulkan.VK10.vkEnumerateDeviceExtensionProperties;
import static org.lwjgl.vulkan.VK10.vkEnumeratePhysicalDevices;
import static org.lwjgl.vulkan.VK10.vkCreateGraphicsPipelines;
import static org.lwjgl.vulkan.VK10.vkCmdSetScissor;
import static org.lwjgl.vulkan.VK10.vkCmdSetViewport;
import static org.lwjgl.vulkan.VK10.vkCmdPushConstants;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceFeatures2;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_RENDERING_INFO;
import static org.lwjgl.vulkan.VK13.vkCmdBeginRendering;
import static org.lwjgl.vulkan.VK13.vkCmdEndRendering;

/** Persistent Vulkan 1.3 renderer for an existing SDL Vulkan window. All calls are thread-confined. */
public final class VulkanRenderer implements Renderer {
    private static final int FRAMES_IN_FLIGHT = 2;
    /** Longest a frame waits for its slot or a swapchain image before giving the loop back to the caller. */
    private static final long ACQUIRE_TIMEOUT_NANOS = 100_000_000L;
    private static final int FLOATS_PER_VERTEX = 6;
    private static final int BYTES_PER_VERTEX = FLOATS_PER_VERTEX * Float.BYTES;
    private static final int TRANSFORM_BYTES = 16 * Float.BYTES;
    /** Per-frame stream region; sized for thousands of entities and tens of thousands of particles. */
    private static final long STREAM_REGION_BYTES = 8L * 1024 * 1024;
    private static final float[] IDENTITY = {
            1, 0, 0, 0,
            0, 1, 0, 0,
            0, 0, 1, 0,
            0, 0, 0, 1
    };

    private final SdlWindow window;
    private final Thread ownerThread;
    private final Set<OwnedBuffer> buffers = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<VulkanMesh> meshes = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<VulkanTexturedMesh> texturedMeshes = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<VulkanSampledImage> sampledImages = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<VulkanStaticBuffer> staticBuffers = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<VulkanPipeline> pipelines = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<VulkanTrackedResource> trackedResources = Collections.newSetFromMap(new IdentityHashMap<>());
    private final FrameSlot[] frames = new FrameSlot[FRAMES_IN_FLIGHT];
    private VulkanDeviceContext context;
    private VulkanSwapchain swapchain;
    private VulkanGraphicsPipeline graphicsPipeline;
    private ByteBuffer transformBytes;
    private VkRenderingAttachmentInfo.Buffer colorAttachment;
    private VkRenderingInfo renderingInfo;
    private VkViewport.Buffer viewport;
    private VkRect2D.Buffer scissor;
    private IntBuffer acquiredImage;
    private LongBuffer vertexBinding;
    private LongBuffer vertexOffset;
    private int currentFrame;
    private int currentImage = -1;
    private boolean recording;
    private boolean recreatePending;
    private boolean recreationFailed;
    private VulkanUploader uploader;
    private VulkanTextureDescriptors textureDescriptors;
    private VulkanStreamBuffer stream;
    private VulkanFrame frame;
    private VulkanImage depthImage;
    private int depthAspect;
    private VkRenderingAttachmentInfo depthAttachment;
    private PresentMode presentMode;
    private boolean closed;
    private java.util.function.Consumer<RgbaImage> pendingCapture;
    private VulkanBuffer captureBuffer;
    /** Two timestamps per frame slot (command buffer start and end); 0 when the queue cannot time. */
    private long timestampPool;
    private double timestampPeriodNanos;
    private long timestampMask;
    private final boolean[] slotTimed = new boolean[FRAMES_IN_FLIGHT];
    private LongBuffer timestampResults;
    private long lastGpuFrameNanos = -1;

    public VulkanRenderer(SdlWindow window) {
        this(window, PresentMode.MAILBOX, false);
    }

    /**
     * @param presentMode initial presentation preference; change it later with {@link #presentMode(PresentMode)}
     * @param validation  enables the Khronos validation layer when installed (instance-level, fixed for life)
     */
    public VulkanRenderer(SdlWindow window, PresentMode presentMode, boolean validation) {
        this.window = Objects.requireNonNull(window, "window");
        this.presentMode = Objects.requireNonNull(presentMode, "presentMode");
        ownerThread = Thread.currentThread();
        window.handle();
        try {
            allocateNativeBuffers();
            context = new VulkanDeviceContext(window, validation);
            for (int index = 0; index < FRAMES_IN_FLIGHT; index++) {
                frames[index] = new FrameSlot(context.frames[index]);
            }
            uploader = new VulkanUploader(context.physicalDevice(), context.device(),
                    context.graphicsQueue(), context.commandPool());
            swapchain = new VulkanSwapchain(context.physicalDevice(), context.device(), context.surface(),
                    context.graphicsFamily(), context.presentFamily(), presentMode,
                    context.swapchainMutableFormat(), context.presentWait());
            swapchain.create(currentWindowWidth(), currentWindowHeight());
            depthAspect = VulkanImage.depthBarrierAspect(context.depthFormat());
            createDepthImage();
            graphicsPipeline = new VulkanGraphicsPipeline(context.device());
            graphicsPipeline.create(swapchain.format(), context.depthFormat());
            textureDescriptors = new VulkanTextureDescriptors(context.device());
            stream = new VulkanStreamBuffer(context.physicalDevice(), context.device(), STREAM_REGION_BYTES,
                    FRAMES_IN_FLIGHT);
            frame = new VulkanFrame(this, stream);
            createTimestampPool();
        } catch (RuntimeException | Error failure) {
            try {
                releaseNative();
            } catch (RuntimeException | Error cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            closed = true;
            releaseJavaBuffers();
            throw failure;
        }
    }

    /** Acquires a swapchain image without waiting; returns false when the GPU/window is not ready. */
    public boolean beginFrame() {
        return beginFrame(0.035f, 0.045f, 0.065f, 1.0f);
    }

    public boolean beginFrame(float red, float green, float blue, float alpha) {
        validateClearColor(red, green, blue, alpha);
        VulkanFrame acquired = acquireFrame();
        if (acquired == null) {
            return false;
        }
        acquired.beginMainPass(red, green, blue, alpha);
        return true;
    }

    /**
     * Acquires a swapchain image without waiting and opens its command buffer with no pass active, so callers
     * can record transfers and offscreen passes before {@link VulkanFrame#beginMainPass}. Returns null when the
     * GPU or window is not ready.
     */
    public VulkanFrame acquireFrame() {
        requireOwnerAndOpen();
        requireUsableRenderer(recreationFailed);
        if (recording) {
            throw new IllegalStateException("A Vulkan frame is already being recorded.");
        }
        if (recreatePending || swapchain == null || swapchain.handle() == 0) {
            recreateSwapchain();
            if (recreatePending || swapchain == null || swapchain.handle() == 0) {
                return null;
            }
        }
        FrameSlot slot = frames[currentFrame];
        // A precise driver wait, not a poll-and-sleep: sleeping a whole millisecond whenever the GPU was a
        // little behind made frame intervals bimodal (p95 several times p50).
        int fenceStatus = org.lwjgl.vulkan.VK10.vkWaitForFences(context.device(), slot.fence, true, ACQUIRE_TIMEOUT_NANOS);
        if (fenceStatus == VK_TIMEOUT) {
            return null;
        }
        requireSuccess(fenceStatus, "vkWaitForFences");
        completeTrackedFrame(currentFrame);
        readTimestamps(currentFrame);
        acquiredImage.put(0, 0);
        // Bounded so a hidden window (which may never release an image under FIFO) cannot freeze event handling.
        int result = vkAcquireNextImageKHR(context.device(), swapchain.handle(), ACQUIRE_TIMEOUT_NANOS,
                slot.imageAvailable, 0L, acquiredImage);
        if (result == VK_TIMEOUT || result == VK_NOT_READY) {
            return null;
        }
        if (result == VK_ERROR_OUT_OF_DATE_KHR) {
            recreatePending = true;
            return null;
        }
        if (result != VK_SUCCESS && result != VK_SUBOPTIMAL_KHR) {
            requireSuccess(result, "vkAcquireNextImageKHR");
        }
        recreatePending |= result == VK_SUBOPTIMAL_KHR;
        currentImage = acquiredImage.get(0);
        requireSuccess(vkResetCommandBuffer(slot.commandBuffer, 0), "vkResetCommandBuffer");
        requireSuccess(vkBeginCommandBuffer(slot.commandBuffer, slot.beginInfo), "vkBeginCommandBuffer");
        if (timestampPool != 0) {
            org.lwjgl.vulkan.VK10.vkCmdResetQueryPool(slot.commandBuffer, timestampPool, currentFrame * 2, 2);
            org.lwjgl.vulkan.VK10.vkCmdWriteTimestamp(slot.commandBuffer,
                    org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, timestampPool, currentFrame * 2);
        }
        recording = true;
        frame.begin(slot.commandBuffer, currentFrame, swapchain.width(), swapchain.height());
        return frame;
    }

    /** Records swapchain/depth transitions and begins the main dynamic-rendering pass. */
    void beginMainRendering(VkCommandBuffer command, float red, float green, float blue, float alpha) {
        validateClearColor(red, green, blue, alpha);
        transitionSwapchainImage(command, currentImage,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                0, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
        // The previous frame's depth writes must finish before this frame clears the shared depth image.
        VulkanImage.barrier(command, depthImage.image(), depthAspect, 1,
                VK_IMAGE_LAYOUT_UNDEFINED, VK13.VK_IMAGE_LAYOUT_DEPTH_ATTACHMENT_OPTIMAL,
                VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT,
                VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT,
                VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
                VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);
        colorAttachment.get(0)
                .sType(VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO)
                .imageView(swapchain.imageView(currentImage))
                .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                .loadOp(org.lwjgl.vulkan.VK10.VK_ATTACHMENT_LOAD_OP_CLEAR)
                .storeOp(org.lwjgl.vulkan.VK10.VK_ATTACHMENT_STORE_OP_STORE);
        colorAttachment.get(0).clearValue().color().float32(0, red).float32(1, green)
                .float32(2, blue).float32(3, alpha);
        depthAttachment
                .sType(VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO)
                .imageView(depthImage.view())
                .imageLayout(VK13.VK_IMAGE_LAYOUT_DEPTH_ATTACHMENT_OPTIMAL)
                .loadOp(org.lwjgl.vulkan.VK10.VK_ATTACHMENT_LOAD_OP_CLEAR)
                // Stored so the pass can be suspended and resumed (gamma-space segments).
                .storeOp(org.lwjgl.vulkan.VK10.VK_ATTACHMENT_STORE_OP_STORE);
        depthAttachment.clearValue().depthStencil().depth(1.0f).stencil(0);
        renderingInfo.sType(VK_STRUCTURE_TYPE_RENDERING_INFO)
                .layerCount(1)
                .pColorAttachments(colorAttachment)
                .pDepthAttachment(depthAttachment);
        StructWrites.rect(renderingInfo.address() + VkRenderingInfo.RENDERAREA, 0, 0, swapchain.width(),
                swapchain.height());
        vkCmdBeginRendering(command, renderingInfo);
    }

    /** Begins the main pass again after a suspension, on {@code colorView}, loading color and depth. */
    void resumeMainRendering(VkCommandBuffer command, long colorView) {
        colorAttachment.get(0).imageView(colorView).loadOp(org.lwjgl.vulkan.VK10.VK_ATTACHMENT_LOAD_OP_LOAD);
        depthAttachment.loadOp(org.lwjgl.vulkan.VK10.VK_ATTACHMENT_LOAD_OP_LOAD);
        vkCmdBeginRendering(command, renderingInfo);
    }

    int mainColorFormat() {
        return swapchain.format();
    }

    long mainColorView() {
        return swapchain.imageView(currentImage);
    }

    /** The current swapchain image's UNORM alias view, or 0. */
    long mainGammaView() {
        return swapchain.gammaView(currentImage);
    }

    /** Presents the currently recorded frame and advances the frame slot; false means the swapchain was outdated. */
    public boolean endFrame() {
        requireOwnerAndOpen();
        if (!recording) {
            throw new IllegalStateException("Call beginFrame() before ending a frame.");
        }
        return endFrameInternal();
    }

    /** Updates the reusable transform with a column-major object-to-clip matrix. */
    static void writeObjectToClipTransform(ByteBuffer destination, float[] objectToClip) {
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(objectToClip, "objectToClip");
        if (objectToClip.length != 16 || destination.remaining() < TRANSFORM_BYTES) {
            throw new IllegalArgumentException("A 16-float transform destination is required.");
        }
        for (int i = 0; i < 16; i++) {
            if (!Float.isFinite(objectToClip[i])) {
                throw new IllegalArgumentException("Transform components must be finite.");
            }
            destination.putFloat(destination.position() + i * Float.BYTES, objectToClip[i]);
        }
    }

    /** Records one indexed mesh with a column-major object-to-clip matrix; input is copied into owned storage. */
    public void drawIndexed(VulkanMesh mesh, float[] objectToClip) {
        requireOwnerAndOpen();
        if (!recording || !frame.mainPassOpen()) {
            throw new IllegalStateException("Call beginFrame() before drawing.");
        }
        Objects.requireNonNull(mesh, "mesh");
        Objects.requireNonNull(objectToClip, "objectToClip");
        if (objectToClip.length != 16) {
            throw new IllegalArgumentException("The object-to-clip matrix must contain 16 floats.");
        }
        if (!mesh.belongsTo(this)) {
            throw new IllegalArgumentException("A mesh can only be drawn by its creating renderer.");
        }
        mesh.ensureOpen();
        writeObjectToClipTransform(transformBytes, objectToClip);
        FrameSlot frame = frames[currentFrame];
        vkCmdBindPipeline(frame.commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, graphicsPipeline.pipeline());
        vertexBinding.put(0, mesh.vertexHandle());
        vertexOffset.put(0, 0L);
        vkCmdBindVertexBuffers(frame.commandBuffer, 0, vertexBinding, vertexOffset);
        vkCmdBindIndexBuffer(frame.commandBuffer, mesh.indexHandle(), 0L, VK_INDEX_TYPE_UINT32);
        vkCmdPushConstants(frame.commandBuffer, graphicsPipeline.pipelineLayout(), VK_SHADER_STAGE_VERTEX_BIT, 0,
                transformBytes);
        vkCmdDrawIndexed(frame.commandBuffer, mesh.indexCount(), 1, 0, 0, 0);
        trackResource(mesh, currentFrame);
        this.frame.forgetPipeline();
    }

    /** Uses an identity transform for the deterministic object-space triangle smoke mesh. */
    public void drawIndexed(VulkanMesh mesh) {
        drawIndexed(mesh, IDENTITY);
    }

    /** Records one sampled-texture mesh. The texture and mesh must belong to this renderer. */
    public void drawTextured(VulkanTexture texture, VulkanTexturedMesh mesh, float[] objectToClip) {
        requireOwnerAndOpen();
        if (!recording || !frame.mainPassOpen()) {
            throw new IllegalStateException("Call beginFrame() before drawing.");
        }
        Objects.requireNonNull(texture, "texture");
        Objects.requireNonNull(mesh, "mesh");
        Objects.requireNonNull(objectToClip, "objectToClip");
        if (objectToClip.length != 16) {
            throw new IllegalArgumentException("The object-to-clip matrix must contain 16 floats.");
        }
        if (!texture.belongsTo(this) || !mesh.belongsTo(this)) {
            throw new IllegalArgumentException("Textures and meshes can only be drawn by their creating renderer.");
        }
        texture.ensureResourceOpen();
        mesh.ensureResourceOpen();
        writeObjectToClipTransform(transformBytes, objectToClip);
        FrameSlot frame = frames[currentFrame];
        vkCmdBindPipeline(frame.commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, graphicsPipeline.texturedPipeline());
        vertexBinding.put(0, mesh.vertexHandle());
        vertexOffset.put(0, 0L);
        vkCmdBindVertexBuffers(frame.commandBuffer, 0, vertexBinding, vertexOffset);
        vkCmdBindIndexBuffer(frame.commandBuffer, mesh.indexHandle(), 0L, VK_INDEX_TYPE_UINT32);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkCmdBindDescriptorSets(frame.commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS,
                    graphicsPipeline.texturedPipelineLayout(), 0, stack.longs(texture.descriptorSet()), null);
        }
        vkCmdPushConstants(frame.commandBuffer, graphicsPipeline.texturedPipelineLayout(),
                VK_SHADER_STAGE_VERTEX_BIT, 0, transformBytes);
        vkCmdDrawIndexed(frame.commandBuffer, mesh.indexCount(), 1, 0, 0, 0);
        trackResource(texture, currentFrame);
        trackResource(mesh, currentFrame);
        this.frame.forgetPipeline();
    }

    public void drawTextured(VulkanTexture texture, VulkanTexturedMesh mesh) {
        drawTextured(texture, mesh, IDENTITY);
    }

    /** Uses the batch's antialiasing preference to select linear or nearest sampling. */
    public VulkanTexture createTexture(TexturedMeshData data) {
        Objects.requireNonNull(data, "data");
        ImageSampling sampling = data.options().antialiasing() ? ImageSampling.LINEAR : ImageSampling.NEAREST;
        return createTexture(data.texture(), sampling);
    }

    /** Creates an immutable sampled RGBA texture outside frame recording. */
    public VulkanTexture createTexture(RgbaImage source, ImageSampling sampling) {
        Objects.requireNonNull(sampling, "sampling");
        return createTexture(source, new TextureOptions(sampling, true, false));
    }

    /** Creates an immutable sampled texture outside frame recording. */
    public VulkanTexture createTexture(RgbaImage source, TextureOptions options) {
        Objects.requireNonNull(source, "source");
        return createTextureInternal(List.of(source), false, options);
    }

    /** Creates a same-size 2D array texture (sampler2DArray) whose layers follow list order. */
    public VulkanTexture createTextureArray(List<RgbaImage> layers, TextureOptions options) {
        Objects.requireNonNull(layers, "layers");
        return createTextureInternal(List.copyOf(layers), true, options);
    }

    private VulkanTexture createTextureInternal(List<RgbaImage> layers, boolean array, TextureOptions options) {
        requireOwnerAndOpen();
        Objects.requireNonNull(options, "options");
        requireOutsideFrame("Texture uploads");
        VulkanTexture texture = new VulkanTexture(this, context.physicalDevice(), context.device(), textureDescriptors,
                graphicsPipeline.textureDescriptorSetLayout(), layers, array, options, uploader);
        sampledImages.add(texture);
        trackedResources.add(texture);
        return texture;
    }

    /** Creates a fixed-size offscreen color target that later passes can sample. */
    public VulkanRenderTarget createRenderTarget(int width, int height, RenderTargetFormat format, boolean linear,
                                                 boolean repeat) {
        return createRenderTarget(width, height, format, linear, repeat, false);
    }

    /** As above; with {@code depth}, passes into the target also get a private depth attachment. */
    public VulkanRenderTarget createRenderTarget(int width, int height, RenderTargetFormat format, boolean linear,
                                                 boolean repeat, boolean depth) {
        requireOwnerAndOpen();
        requireOutsideFrame("Render target creation");
        VulkanRenderTarget target = new VulkanRenderTarget(this, context.physicalDevice(), context.device(),
                textureDescriptors, graphicsPipeline.textureDescriptorSetLayout(), width, height, format, linear,
                repeat, depth ? context.depthFormat() : 0);
        sampledImages.add(target);
        trackedResources.add(target);
        return target;
    }

    /** Creates a BGRA texture that {@link VulkanFrame#upload} can replace every frame without stalls. */
    public VulkanDynamicTexture createDynamicTexture(int width, int height, boolean srgb, boolean linear) {
        requireOwnerAndOpen();
        requireOutsideFrame("Dynamic texture creation");
        VulkanDynamicTexture texture = new VulkanDynamicTexture(this, context.physicalDevice(), context.device(),
                textureDescriptors, graphicsPipeline.textureDescriptorSetLayout(), uploader, width, height, srgb,
                linear, FRAMES_IN_FLIGHT);
        sampledImages.add(texture);
        trackedResources.add(texture);
        return texture;
    }

    /** Uploads immutable vertex and/or index bytes (native order) into device-local memory. */
    public VulkanStaticBuffer createStaticBuffer(ByteBuffer data) {
        requireOwnerAndOpen();
        Objects.requireNonNull(data, "data");
        requireOutsideFrame("Static buffer uploads");
        if (!data.hasRemaining() || (data.remaining() & 3) != 0) {
            throw new IllegalArgumentException("Static buffer data must be non-empty and four-byte aligned.");
        }
        VulkanBuffer buffer = VulkanBuffer.create(context.physicalDevice(), context.device(), data.remaining(),
                VK_BUFFER_USAGE_VERTEX_BUFFER_BIT | VK_BUFFER_USAGE_INDEX_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        try {
            uploader.upload(buffer, 0, data);
        } catch (RuntimeException | Error failure) {
            buffer.close();
            throw failure;
        }
        VulkanStaticBuffer created = new VulkanStaticBuffer(this, buffer);
        staticBuffers.add(created);
        trackedResources.add(created);
        return created;
    }

    /**
     * Device-local storage that shaders read through a buffer reference ({@link VulkanStaticBuffer#deviceAddress()}),
     * filled by {@link VulkanFrame#copyFromStream}. Call outside a frame.
     */
    public VulkanStaticBuffer createAddressableDeviceBuffer(long bytes) {
        requireOwnerAndOpen();
        requireOutsideFrame("Device buffer creation");
        if (bytes <= 0 || (bytes & 3) != 0) {
            throw new IllegalArgumentException("Device buffer size must be positive and four-byte aligned.");
        }
        VulkanBuffer buffer = VulkanBuffer.create(context.physicalDevice(), context.device(), bytes,
                org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, true);
        VulkanStaticBuffer created = new VulkanStaticBuffer(this, buffer);
        staticBuffers.add(created);
        trackedResources.add(created);
        return created;
    }

    /**
     * Creates an uninitialized device-local vertex/index buffer that frames fill with
     * {@link VulkanFrame#copyFromStream}; suits geometry that changes over time without blocking uploads.
     */
    public VulkanStaticBuffer createDeviceBuffer(long bytes) {
        requireOwnerAndOpen();
        requireOutsideFrame("Device buffer creation");
        if (bytes <= 0 || (bytes & 3) != 0) {
            throw new IllegalArgumentException("Device buffer size must be positive and four-byte aligned.");
        }
        VulkanBuffer buffer = VulkanBuffer.create(context.physicalDevice(), context.device(), bytes,
                VK_BUFFER_USAGE_VERTEX_BUFFER_BIT | VK_BUFFER_USAGE_INDEX_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        VulkanStaticBuffer created = new VulkanStaticBuffer(this, buffer);
        staticBuffers.add(created);
        trackedResources.add(created);
        return created;
    }

    /** Builds a pipeline for the main pass (color + depth) or an offscreen format named by the spec. */
    public VulkanPipeline createPipeline(PipelineSpec spec) {
        requireOwnerAndOpen();
        Objects.requireNonNull(spec, "spec");
        requireOutsideFrame("Pipeline creation");
        VulkanPipeline pipeline = new VulkanPipeline(this, context.device(), spec,
                graphicsPipeline.textureDescriptorSetLayout(), swapchain.format(), context.depthFormat());
        pipelines.add(pipeline);
        return pipeline;
    }

    /**
     * Copies the next presented frame to CPU memory and hands it to the callback on this thread once the GPU
     * finishes it. Costs one fence wait on that frame only. Returns false when the surface cannot be read.
     */
    public boolean captureNextFrame(java.util.function.Consumer<RgbaImage> callback) {
        requireOwnerAndOpen();
        Objects.requireNonNull(callback, "callback");
        if (swapchain == null || !swapchain.capturable()) {
            return false;
        }
        pendingCapture = callback;
        return true;
    }

    /**
     * Whether {@link VulkanFrame#drawIndexedIndirect} may issue more than one draw per call with nonzero first
     * instances (the multiDrawIndirect and drawIndirectFirstInstance features). Without it, callers record
     * direct draws.
     */
    public boolean supportsMultiDrawIndirect() {
        return context.multiDrawIndirect();
    }

    /** True when the swapchain encodes linear shader output to sRGB, so sRGB-space math must linearize first. */
    public boolean presentsSrgb() {
        requireOwnerAndOpen();
        int format = swapchain.format();
        return format == VK_FORMAT_B8G8R8A8_SRGB || format == org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_SRGB
                || format == org.lwjgl.vulkan.VK10.VK_FORMAT_A8B8G8R8_SRGB_PACK32;
    }

    /** Bytes the busiest frame has taken from its stream region so far. */
    public long streamPeakBytes() {
        requireOwnerAndOpen();
        return stream.peakBytes();
    }

    void closePipeline(VulkanPipeline pipeline) {
        requireOwnerThread();
        if (closed || !pipelines.remove(pipeline)) {
            return;
        }
        requireOutsideFrame("Pipeline destruction");
        // Pipelines are long-lived; an idle wait on the rare close keeps lifetime tracking out of the draw path.
        requireSuccess(vkDeviceWaitIdle(context.device()), "vkDeviceWaitIdle before pipeline destruction");
        pipeline.destroy();
    }

    void closeStaticBuffer(VulkanStaticBuffer buffer) {
        requireOwnerThread();
        if (closed || !staticBuffers.remove(buffer)) {
            return;
        }
        requireOutsideFrame("Static buffer release");
        buffer.release();
        if (buffer.isResourceReleased()) {
            trackedResources.remove(buffer);
        }
    }

    int depthAspect() {
        return depthAspect;
    }

    void track(VulkanTrackedResource resource, int frameIndex) {
        trackResource(resource, frameIndex);
    }

    void requireOwnerThreadForFrame() {
        requireOwnerThread();
    }

    private void requireOutsideFrame(String operation) {
        if (recording) {
            throw new IllegalStateException(operation + " are only allowed outside a frame.");
        }
    }

    private void createDepthImage() {
        depthImage = VulkanImage.create(context.physicalDevice(), context.device(), swapchain.width(),
                swapchain.height(), 1, context.depthFormat(),
                org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT,
                org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_DEPTH_BIT, false);
    }

    private void destroyDepthImage() {
        if (depthImage != null) {
            depthImage.destroy(context.device());
            depthImage = null;
        }
    }

    /** Creates reusable device-local xyz + uv + rgba vertices and uint32 indices. */
    public VulkanTexturedMesh createTexturedMesh(float[] xyzUvRgba, int[] indices) {
        requireOwnerAndOpen();
        Objects.requireNonNull(xyzUvRgba, "xyzUvRgba");
        Objects.requireNonNull(indices, "indices");
        if (recording) {
            throw new IllegalStateException("Mesh uploads are only allowed outside a frame.");
        }
        validateTexturedMesh(xyzUvRgba, indices);
        return createTexturedMeshBuffers(xyzUvRgba, indices);
    }

    /** Creates reusable device-local mesh buffers from CPU-side image or font batch data. */
    public VulkanTexturedMesh createTexturedMesh(TexturedMeshData data) {
        Objects.requireNonNull(data, "data");
        return createTexturedMesh(data.copyVertices(), data.copyIndices());
    }

    private VulkanTexturedMesh createTexturedMeshBuffers(float[] verticesData, int[] indicesData) {
        long vertexBytes = Math.multiplyExact((long) verticesData.length, Float.BYTES);
        long indexBytes = Math.multiplyExact((long) indicesData.length, Integer.BYTES);
        VulkanBuffer vertices = VulkanBuffer.create(context.physicalDevice(), context.device(), vertexBytes,
                VK_BUFFER_USAGE_VERTEX_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        VulkanBuffer indexBuffer = null;
        try {
            indexBuffer = VulkanBuffer.create(context.physicalDevice(), context.device(), indexBytes,
                    VK_BUFFER_USAGE_INDEX_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                    org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                    org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            ByteBuffer vertexData = memAlloc(Math.toIntExact(vertexBytes));
            ByteBuffer indexData = null;
            try {
                indexData = memAlloc(Math.toIntExact(indexBytes));
                vertexData.asFloatBuffer().put(verticesData);
                indexData.asIntBuffer().put(indicesData);
                vertexData.limit(Math.toIntExact(vertexBytes));
                indexData.limit(Math.toIntExact(indexBytes));
                uploader.upload(vertices, 0, vertexData);
                uploader.upload(indexBuffer, 0, indexData);
            } finally {
                memFree(vertexData);
                if (indexData != null) {
                    memFree(indexData);
                }
            }
            VulkanTexturedMesh mesh = new VulkanTexturedMesh(this, vertices, indexBuffer, indicesData.length);
            texturedMeshes.add(mesh);
            trackedResources.add(mesh);
            return mesh;
        } catch (RuntimeException | Error failure) {
            if (indexBuffer != null) {
                indexBuffer.close();
            }
            vertices.close();
            throw failure;
        }
    }

    /** Creates reusable object-space xyz + rgb vertices and uint32 indices in device-local memory. */
    public VulkanMesh createIndexedMesh(float[] xyzRgb, int[] indices) {
        requireOwnerAndOpen();
        Objects.requireNonNull(xyzRgb, "xyzRgb");
        Objects.requireNonNull(indices, "indices");
        if (recording) {
            throw new IllegalStateException("Mesh uploads are only allowed outside a frame.");
        }
        validateIndexedMesh(xyzRgb, indices);
        long vertexBytes = Math.multiplyExact((long) xyzRgb.length, Float.BYTES);
        long indexBytes = Math.multiplyExact((long) indices.length, Integer.BYTES);
        VulkanBuffer vertices = VulkanBuffer.create(context.physicalDevice(), context.device(), vertexBytes,
                VK_BUFFER_USAGE_VERTEX_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        VulkanBuffer indexBuffer = null;
        try {
            indexBuffer = VulkanBuffer.create(context.physicalDevice(), context.device(), indexBytes,
                    VK_BUFFER_USAGE_INDEX_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                    org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                    org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            ByteBuffer vertexData = memAlloc(Math.toIntExact(vertexBytes));
            ByteBuffer indexData = memAlloc(Math.toIntExact(indexBytes));
            try {
                FloatBuffer vertexFloats = vertexData.asFloatBuffer();
                vertexFloats.put(xyzRgb);
                IntBuffer indexInts = indexData.asIntBuffer();
                indexInts.put(indices);
                vertexData.limit(Math.toIntExact(vertexBytes));
                indexData.limit(Math.toIntExact(indexBytes));
                uploader.upload(vertices, 0, vertexData);
                uploader.upload(indexBuffer, 0, indexData);
            } finally {
                memFree(vertexData);
                memFree(indexData);
            }
            VulkanMesh mesh = new VulkanMesh(this, vertices, indexBuffer, indices.length);
            meshes.add(mesh);
            trackedResources.add(mesh);
            return mesh;
        } catch (RuntimeException | Error failure) {
            if (indexBuffer != null) {
                indexBuffer.close();
            }
            vertices.close();
            throw failure;
        }
    }

    @Override
    public synchronized RenderBuffer createBuffer(BufferDescriptor descriptor) {
        requireOwnerAndOpen();
        if (recording) {
            throw new IllegalStateException("Buffer creation is only allowed outside a frame.");
        }
        Objects.requireNonNull(descriptor, "descriptor");
        int usage = VulkanResourceFactory.getUsageFlags(descriptor);
        if (!descriptor.hostVisible()) {
            usage |= VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        }
        VulkanBuffer nativeBuffer = VulkanBuffer.create(context.physicalDevice(), context.device(), descriptor.size(), usage,
                VulkanResourceFactory.getRequiredMemoryProperties(descriptor),
                VulkanResourceFactory.getPreferredMemoryProperties(descriptor));
        OwnedBuffer buffer = new OwnedBuffer(nativeBuffer);
        buffers.add(buffer);
        return buffer;
    }

    /** The present mode in use (the requested one, or the surface's fallback for it), or null before a swapchain. */
    public PresentMode activePresentMode() {
        return swapchain == null ? null : swapchain.presentMode();
    }

    /** Switches presentation mode, recreating the swapchain; only valid outside a frame. */
    public void presentMode(PresentMode mode) {
        requireOwnerAndOpen();
        Objects.requireNonNull(mode, "mode");
        requireOutsideFrame("Present mode changes");
        if (mode != presentMode) {
            presentMode = mode;
            recreateSwapchain();
        }
    }

    /**
     * Rebuilds the swapchain before the next frame after the window's pixel size changed. Wayland drivers need not
     * report a resize as out of date, and the compositor then stretches the old images, blurring everything.
     */
    public void windowResized() {
        requireOwnerAndOpen();
        recreatePending = true;
    }

    /** Recreates swapchain images, views, presentation semaphores, and the format-specific pipeline. */
    public void recreateSwapchain() {
        requireOwnerAndOpen();
        requireUsableRenderer(recreationFailed);
        if (recording) {
            throw new IllegalStateException("Cannot recreate the swapchain during frame recording.");
        }
        int width = currentWindowWidth();
        int height = currentWindowHeight();
        if (width <= 0 || height <= 0) {
            recreatePending = true;
            return;
        }
        try {
            requireSuccess(vkDeviceWaitIdle(context.device()), "vkDeviceWaitIdle for swapchain recreation");
            if (swapchain != null) {
                swapchain.close();
            }
            swapchain = new VulkanSwapchain(context.physicalDevice(), context.device(), context.surface(),
                    context.graphicsFamily(), context.presentFamily(), presentMode,
                    context.swapchainMutableFormat(), context.presentWait());
            swapchain.create(width, height);
            destroyDepthImage();
            createDepthImage();
            if (graphicsPipeline != null) {
                graphicsPipeline.recreate(swapchain.format(), context.depthFormat());
            }
            for (VulkanPipeline pipeline : pipelines) {
                if (pipeline.targetsMainPass()) {
                    pipeline.rebuild(swapchain.format(), context.depthFormat());
                }
            }
            recreatePending = false;
        } catch (RuntimeException | Error failure) {
            recreationFailed = true;
            throw failure;
        }
    }

    @Override
    public synchronized void close() {
        requireOwnerThread();
        if (closed) {
            return;
        }
        closed = true;
        RuntimeException failure = null;
        try {
            if (context != null) {
                int idle = vkDeviceWaitIdle(context.device());
                if (idle != VK_SUCCESS) {
                    failure = new IllegalStateException("vkDeviceWaitIdle failed with VkResult " + idle);
                }
            }
            for (VulkanMesh mesh : meshes.toArray(VulkanMesh[]::new)) {
                mesh.release();
            }
            meshes.clear();
            for (OwnedBuffer buffer : buffers.toArray(OwnedBuffer[]::new)) {
                buffer.release();
            }
            buffers.clear();
        } catch (RuntimeException cleanupFailure) {
            if (failure == null) {
                failure = cleanupFailure;
            } else {
                failure.addSuppressed(cleanupFailure);
            }
        } finally {
            try {
                releaseNative();
            } catch (RuntimeException cleanupFailure) {
                if (failure == null) {
                    failure = cleanupFailure;
                } else {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            releaseJavaBuffers();
        }
        if (failure != null) {
            throw failure;
        }
    }

    void closeMesh(VulkanMesh mesh) {
        if (!mesh.belongsTo(this)) {
            throw new IllegalArgumentException("A mesh can only be closed by its creating renderer.");
        }
        requireOwnerThread();
        if (closed || !meshes.contains(mesh)) {
            return;
        }
        requireOwnerAndOpen();
        if (recording) {
            throw new IllegalStateException("Cannot release a mesh during frame recording.");
        }
        meshes.remove(mesh);
        mesh.release();
        if (mesh.isResourceReleased()) {
            trackedResources.remove(mesh);
        }
    }

    void closeTexturedMesh(VulkanTexturedMesh mesh) {
        if (!mesh.belongsTo(this)) {
            throw new IllegalArgumentException("A mesh can only be closed by its creating renderer.");
        }
        requireOwnerThread();
        if (closed || !texturedMeshes.contains(mesh)) {
            return;
        }
        requireOwnerAndOpen();
        if (recording) {
            throw new IllegalStateException("Cannot release a mesh during frame recording.");
        }
        texturedMeshes.remove(mesh);
        mesh.release();
        if (mesh.isResourceReleased()) {
            trackedResources.remove(mesh);
        }
    }

    void closeSampled(VulkanSampledImage image) {
        if (!image.belongsTo(this)) {
            throw new IllegalArgumentException("An image can only be closed by its creating renderer.");
        }
        requireOwnerThread();
        if (closed || !sampledImages.contains(image)) {
            return;
        }
        requireOwnerAndOpen();
        if (recording) {
            throw new IllegalStateException("Cannot release an image during frame recording.");
        }
        sampledImages.remove(image);
        image.requestRelease();
        if (image.isResourceReleased()) {
            trackedResources.remove(image);
        }
    }

    static void requireUsableRenderer(boolean recreationFailed) {
        if (recreationFailed) {
            throw new IllegalStateException("Vulkan renderer swapchain recreation previously failed; close and recreate it.");
        }
    }

    static void validateDeviceLocalUpload(long offset, int length) {
        if (offset < 0 || length < 0) {
            throw new IllegalArgumentException("Device-local upload offset and length must not be negative.");
        }
        if ((offset & 3L) != 0 || (length & 3) != 0) {
            throw new IllegalArgumentException("Device-local buffer upload offsets and lengths must be four-byte aligned.");
        }
    }

    static void validateClearColor(float red, float green, float blue, float alpha) {
        requireNormalizedColor(red, "red");
        requireNormalizedColor(green, "green");
        requireNormalizedColor(blue, "blue");
        requireNormalizedColor(alpha, "alpha");
    }

    private static void requireNormalizedColor(float value, String component) {
        if (!Float.isFinite(value) || value < 0.0f || value > 1.0f) {
            throw new IllegalArgumentException("Clear color " + component + " must be finite and between zero and one.");
        }
    }

    static void validateTexturedMesh(float[] xyzUvRgba, int[] indices) {
        Objects.requireNonNull(xyzUvRgba, "xyzUvRgba");
        Objects.requireNonNull(indices, "indices");
        if (xyzUvRgba.length == 0 || xyzUvRgba.length % 9 != 0 || xyzUvRgba.length > 2_000_000) {
            throw new IllegalArgumentException("Textured vertices must contain at most 2,000,000 floats in nine-float records.");
        }
        if (indices.length == 0 || indices.length % 3 != 0 || indices.length > 1_000_000) {
            throw new IllegalArgumentException("Textured meshes require at most 1,000,000 triangle indices.");
        }
        int vertexCount = xyzUvRgba.length / 9;
        for (int offset = 0; offset < xyzUvRgba.length; offset += 9) {
            for (int component = 0; component < 9; component++) {
                if (!Float.isFinite(xyzUvRgba[offset + component])) {
                    throw new IllegalArgumentException("Textured vertex components must be finite.");
                }
            }
            if (xyzUvRgba[offset + 3] < 0f || xyzUvRgba[offset + 3] > 1f
                    || xyzUvRgba[offset + 4] < 0f || xyzUvRgba[offset + 4] > 1f) {
                throw new IllegalArgumentException("Texture coordinates must be normalized to the range zero to one.");
            }
            for (int channel = 5; channel < 9; channel++) {
                if (xyzUvRgba[offset + channel] < 0f || xyzUvRgba[offset + channel] > 1f) {
                    throw new IllegalArgumentException("Vertex color components must be normalized.");
                }
            }
        }
        for (int index : indices) {
            if (index < 0 || index >= vertexCount) {
                throw new IllegalArgumentException("Textured mesh index is outside the vertex array.");
            }
        }
    }

    static void validateIndexedMesh(float[] xyzRgb, int[] indices) {
        Objects.requireNonNull(xyzRgb, "xyzRgb");
        Objects.requireNonNull(indices, "indices");
        if (xyzRgb.length == 0 || xyzRgb.length % FLOATS_PER_VERTEX != 0) {
            throw new IllegalArgumentException("Mesh vertices must contain six floats per vertex (xyz + rgb).");
        }
        if (indices.length == 0 || indices.length % 3 != 0) {
            throw new IllegalArgumentException("Indexed triangle meshes require a non-empty index count divisible by 3.");
        }
        int vertexCount = xyzRgb.length / FLOATS_PER_VERTEX;
        for (float component : xyzRgb) {
            if (!Float.isFinite(component)) {
                throw new IllegalArgumentException("Mesh vertex components must be finite.");
            }
        }
        for (int index : indices) {
            if (index < 0 || index >= vertexCount) {
                throw new IllegalArgumentException("Mesh index is outside the vertex array.");
            }
        }
    }

    private boolean endFrameInternal() {
        FrameSlot slot = frames[currentFrame];
        if (!frame.finishPasses()) {
            frame.beginMainPass(0f, 0f, 0f, 1f);
        }
        frame.close();
        FrameSlot frame = slot;
        vkCmdEndRendering(frame.commandBuffer);
        java.util.function.Consumer<RgbaImage> capture = pendingCapture;
        pendingCapture = null;
        int captureWidth = swapchain.width();
        int captureHeight = swapchain.height();
        if (capture != null) {
            recordCapture(frame.commandBuffer, captureWidth, captureHeight);
            transitionSwapchainImage(frame.commandBuffer, currentImage,
                    org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                    org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                    org.lwjgl.vulkan.VK10.VK_ACCESS_TRANSFER_READ_BIT, 0);
        } else {
            transitionSwapchainImage(frame.commandBuffer, currentImage,
                    VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                    VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                    VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, 0);
        }
        if (timestampPool != 0) {
            org.lwjgl.vulkan.VK10.vkCmdWriteTimestamp(frame.commandBuffer, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                    timestampPool, currentFrame * 2 + 1);
            slotTimed[currentFrame] = true;
        }
        requireSuccess(vkEndCommandBuffer(frame.commandBuffer), "vkEndCommandBuffer");
        requireSuccess(vkResetFences(context.device(), frame.fence), "vkResetFences");
        frame.signalSemaphores.put(0, swapchain.renderFinishedSemaphore(currentImage));
        requireSuccess(vkQueueSubmit(context.graphicsQueue(), frame.submitInfo, frame.fence), "vkQueueSubmit frame");
        if (capture != null) {
            deliverCapture(frame.fence, captureWidth, captureHeight, capture);
        }
        frame.presentImageIndices.put(0, currentImage);
        frame.presentWaitSemaphores.put(0, swapchain.renderFinishedSemaphore(currentImage));
        frame.presentSwapchains.put(0, swapchain.handle());
        if (context.presentWait()) {
            frame.presentIds.put(0, ++presentId);
            frame.presentInfo.pNext(frame.presentId.address());
            presentedSwapchain = swapchain.handle();
        }
        int result = vkQueuePresentKHR(context.presentQueue(), frame.presentInfo);

        currentFrame = (currentFrame + 1) % FRAMES_IN_FLIGHT;
        currentImage = -1;
        recording = false;
        if (result == VK_ERROR_OUT_OF_DATE_KHR || result == VK_SUBOPTIMAL_KHR) {
            recreatePending = true;
            return false;
        }
        requireSuccess(result, "vkQueuePresentKHR");
        return true;
    }

    private void recordCapture(VkCommandBuffer command, int width, int height) {
        long bytes = (long) width * height * 4;
        if (captureBuffer == null || captureBuffer.size() < bytes) {
            if (captureBuffer != null) {
                captureBuffer.close();
            }
            captureBuffer = VulkanBuffer.create(context.physicalDevice(), context.device(), bytes,
                    org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                    org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT
                            | org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                    org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT
                            | org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
                            | org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_CACHED_BIT);
        }
        transitionSwapchainImage(command, currentImage,
                VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, org.lwjgl.vulkan.VK10.VK_ACCESS_TRANSFER_READ_BIT);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            org.lwjgl.vulkan.VkBufferImageCopy.Buffer copy = org.lwjgl.vulkan.VkBufferImageCopy.calloc(1, stack)
                    .bufferOffset(0).bufferRowLength(0).bufferImageHeight(0)
                    .imageSubresource(s -> s.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0)
                            .baseArrayLayer(0).layerCount(1))
                    .imageOffset(o -> o.set(0, 0, 0))
                    .imageExtent(e -> e.set(width, height, 1));
            org.lwjgl.vulkan.VK10.vkCmdCopyImageToBuffer(command, swapchain.image(currentImage),
                    org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, captureBuffer.nativeHandle(), copy);
        }
        org.lwjgl.vulkan.VkBufferMemoryBarrier.Buffer hostRead;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            hostRead = org.lwjgl.vulkan.VkBufferMemoryBarrier.calloc(1, stack)
                    .sType(org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER)
                    .srcAccessMask(org.lwjgl.vulkan.VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(org.lwjgl.vulkan.VK10.VK_ACCESS_HOST_READ_BIT)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .buffer(captureBuffer.nativeHandle()).offset(0).size(VK_WHOLE_SIZE);
            vkCmdPipelineBarrier(command, org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_HOST_BIT, 0, null, hostRead, null);
        }
    }

    private void deliverCapture(long fence, int width, int height, java.util.function.Consumer<RgbaImage> callback) {
        requireSuccess(org.lwjgl.vulkan.VK10.vkWaitForFences(context.device(), fence, true, Long.MAX_VALUE),
                "vkWaitForFences capture");
        ByteBuffer source = captureBuffer.map();
        int format = swapchain.format();
        boolean bgra = format == VK_FORMAT_B8G8R8A8_SRGB || format == org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8A8_UNORM;
        byte[] pixels = new byte[width * height * 4];
        source.get(0, pixels);
        for (int offset = 0; offset < pixels.length; offset += 4) {
            if (bgra) {
                byte blue = pixels[offset];
                pixels[offset] = pixels[offset + 2];
                pixels[offset + 2] = blue;
            }
            pixels[offset + 3] = (byte) 0xFF;
        }
        callback.accept(new RgbaImage(width, height, pixels));
    }

    private void transitionSwapchainImage(
            VkCommandBuffer command,
            int imageIndex,
            int oldLayout,
            int newLayout,
            int sourceStage,
            int destinationStage,
            int sourceAccess,
            int destinationAccess
    ) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                    .srcAccessMask(sourceAccess)
                    .dstAccessMask(destinationAccess)
                    .oldLayout(oldLayout)
                    .newLayout(newLayout)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(swapchain.image(imageIndex));
            StructWrites.subresourceRange(barrier.address() + VkImageMemoryBarrier.SUBRESOURCERANGE,
                    VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1);
            vkCmdPipelineBarrier(command, sourceStage, destinationStage, 0,
                    null, null, barrier);
        }
    }

    private int currentWindowWidth() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer width = stack.callocInt(1);
            IntBuffer height = stack.callocInt(1);
            if (!SDL_GetWindowSizeInPixels(window.handle(), width, height)) {
                throw new IllegalStateException("SDL_GetWindowSizeInPixels failed: " + SDL_GetError());
            }
            return width.get(0);
        }
    }

    private int currentWindowHeight() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer width = stack.callocInt(1);
            IntBuffer height = stack.callocInt(1);
            if (!SDL_GetWindowSizeInPixels(window.handle(), width, height)) {
                throw new IllegalStateException("SDL_GetWindowSizeInPixels failed: " + SDL_GetError());
            }
            return height.get(0);
        }
    }

    private void completeTrackedFrame(int frameIndex) {
        // Iterates in place (releaseNative never touches these sets), so a frame copies no array.
        for (java.util.Iterator<VulkanTrackedResource> iterator = trackedResources.iterator(); iterator.hasNext(); ) {
            VulkanTrackedResource resource = iterator.next();
            resource.frameCompleted(frameIndex);
            if (resource.isResourceReleased()) {
                iterator.remove();
                sampledImages.remove(resource);
                staticBuffers.remove(resource);
                meshes.remove(resource);
                texturedMeshes.remove(resource);
            }
        }
    }

    /** The id of the last presented frame (0 before any); counts up across swapchains. */
    private long presentId;
    /** The swapchain the last frame was presented to; a wait on another one has nothing to wait for. */
    private long presentedSwapchain;
    private org.lwjgl.vulkan.VkPresentWait2InfoKHR presentWaitInfo = org.lwjgl.vulkan.VkPresentWait2InfoKHR.calloc()
            .sType(org.lwjgl.vulkan.KHRPresentWait2.VK_STRUCTURE_TYPE_PRESENT_WAIT_2_INFO_KHR);

    /** Whether {@link #waitForPresent} works here (VK_KHR_present_wait2 on this device and surface). */
    public boolean presentWaitSupported() {
        return context.presentWait();
    }

    /** The id {@link #endFrame} gave the most recent present, for {@link #waitForPresent}. */
    public long lastPresentId() {
        return presentId;
    }

    /**
     * Blocks until the frame presented with {@code id} has reached the screen (VK_KHR_present_wait2), or the
     * timeout passes. Returns false on timeout, without support, or when the swapchain was replaced since.
     */
    public boolean waitForPresent(long id, long timeoutNanos) {
        requireOwnerAndOpen();
        if (!context.presentWait() || id <= 0 || swapchain == null || swapchain.handle() != presentedSwapchain) {
            return false;
        }
        presentWaitInfo.presentId(id).timeout(Math.max(0L, timeoutNanos));
        int result = org.lwjgl.vulkan.KHRPresentWait2.vkWaitForPresent2KHR(context.device(), presentedSwapchain,
                presentWaitInfo);
        if (result == VK_TIMEOUT || result == VK_ERROR_OUT_OF_DATE_KHR || result == VK_SUBOPTIMAL_KHR
                || result == org.lwjgl.vulkan.KHRSurface.VK_ERROR_SURFACE_LOST_KHR) {
            recreatePending |= result == VK_ERROR_OUT_OF_DATE_KHR;
            return result == VK_SUBOPTIMAL_KHR;
        }
        requireSuccess(result, "vkWaitForPresent2KHR");
        return true;
    }

    /**
     * Blocks until the next frame slot's previous submission has finished, or the timeout passes. Call it
     * before polling input: the wait then happens before input is sampled rather than between sampling and
     * recording, which keeps input-to-photon latency to one frame of CPU work.
     *
     * @return false when the timeout passed first
     */
    public boolean awaitFrameSlot(long timeoutNanos) {
        requireOwnerAndOpen();
        if (recording) {
            throw new IllegalStateException("Wait for a frame slot before acquiring the frame.");
        }
        int result = org.lwjgl.vulkan.VK10.vkWaitForFences(context.device(), frames[currentFrame].fence, true,
                Math.max(0L, timeoutNanos));
        if (result == VK_TIMEOUT) {
            return false;
        }
        requireSuccess(result, "vkWaitForFences");
        return true;
    }

    /**
     * GPU time between the start and end of the most recently completed frame's command buffer, in nanoseconds,
     * or -1 when the graphics queue does not support timestamps. Includes waits inside the frame (for example
     * on the swapchain image), so it bounds GPU work from above.
     */
    public long gpuFrameNanos() {
        return lastGpuFrameNanos;
    }

    private void createTimestampPool() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer count = stack.callocInt(1);
            org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceQueueFamilyProperties(context.physicalDevice(), count, null);
            org.lwjgl.vulkan.VkQueueFamilyProperties.Buffer families =
                    org.lwjgl.vulkan.VkQueueFamilyProperties.malloc(count.get(0), stack);
            org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceQueueFamilyProperties(context.physicalDevice(), count, families);
            int validBits = families.get(context.graphicsFamily()).timestampValidBits();
            VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.malloc(stack);
            org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceProperties(context.physicalDevice(), properties);
            float period = properties.limits().timestampPeriod();
            if (validBits == 0 || period <= 0f) {
                return;
            }
            org.lwjgl.vulkan.VkQueryPoolCreateInfo info = org.lwjgl.vulkan.VkQueryPoolCreateInfo.calloc(stack)
                    .sType(org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO)
                    .queryType(org.lwjgl.vulkan.VK10.VK_QUERY_TYPE_TIMESTAMP)
                    .queryCount(FRAMES_IN_FLIGHT * 2);
            LongBuffer handle = stack.mallocLong(1);
            requireSuccess(org.lwjgl.vulkan.VK10.vkCreateQueryPool(context.device(), info, null, handle),
                    "vkCreateQueryPool");
            timestampResults = memAllocLong(2);
            timestampPool = handle.get(0);
            timestampPeriodNanos = period;
            timestampMask = validBits >= 64 ? -1L : (1L << validBits) - 1;
        }
    }

    /** Reads a completed slot's timestamps; its fence has signaled, so the results are available. */
    private void readTimestamps(int slot) {
        if (timestampPool == 0 || !slotTimed[slot]) {
            return;
        }
        slotTimed[slot] = false;
        int result = org.lwjgl.vulkan.VK10.vkGetQueryPoolResults(context.device(), timestampPool, slot * 2, 2,
                timestampResults, Long.BYTES, org.lwjgl.vulkan.VK10.VK_QUERY_RESULT_64_BIT);
        if (result == VK_SUCCESS) {
            long ticks = (timestampResults.get(1) - timestampResults.get(0)) & timestampMask;
            lastGpuFrameNanos = Math.round(ticks * timestampPeriodNanos);
        }
    }

    private void trackResource(VulkanTrackedResource resource, int frameIndex) {
        trackedResources.add(resource);
        resource.markFrameInFlight(frameIndex);
    }

    private void allocateNativeBuffers() {
        try {
            transformBytes = memAlloc(TRANSFORM_BYTES);
            colorAttachment = VkRenderingAttachmentInfo.calloc(1);
            renderingInfo = VkRenderingInfo.calloc();
            viewport = VkViewport.calloc(1);
            scissor = VkRect2D.calloc(1);
            acquiredImage = memAllocInt(1);
            vertexBinding = memAllocLong(1);
            vertexOffset = memAllocLong(1);
            depthAttachment = VkRenderingAttachmentInfo.calloc();
        } catch (RuntimeException | Error failure) {
            releaseJavaBuffers();
            throw failure;
        }
    }

    private void releaseNative() {
        if (context != null && context.device() != null) {
            requireSuccess(vkDeviceWaitIdle(context.device()), "vkDeviceWaitIdle before renderer resource destruction");
            for (VulkanTrackedResource resource : trackedResources.toArray(VulkanTrackedResource[]::new)) {
                resource.releaseAfterDeviceIdle();
            }
            trackedResources.clear();
            sampledImages.clear();
            staticBuffers.clear();
            meshes.clear();
            texturedMeshes.clear();
            for (VulkanPipeline pipeline : pipelines) {
                pipeline.destroy();
            }
            pipelines.clear();
        }
        if (stream != null) {
            stream.close();
            stream = null;
        }
        if (timestampPool != 0) {
            org.lwjgl.vulkan.VK10.vkDestroyQueryPool(context.device(), timestampPool, null);
            timestampPool = 0;
        }
        if (timestampResults != null) {
            memFree(timestampResults);
            timestampResults = null;
        }
        if (captureBuffer != null) {
            captureBuffer.close();
            captureBuffer = null;
        }
        if (context != null && context.device() != null) {
            destroyDepthImage();
        }
        if (textureDescriptors != null) {
            textureDescriptors.close();
            textureDescriptors = null;
        }
        if (swapchain != null) {
            swapchain.close();
            swapchain = null;
        }
        if (graphicsPipeline != null) {
            graphicsPipeline.close();
            graphicsPipeline = null;
        }
        if (context != null) {
            context.close();
            context = null;
        }
    }

    private void releaseJavaBuffers() {
        if (transformBytes != null) {
            memFree(transformBytes);
            transformBytes = null;
        }
        if (colorAttachment != null) {
            colorAttachment.free();
            colorAttachment = null;
        }
        if (renderingInfo != null) {
            renderingInfo.free();
            renderingInfo = null;
        }
        if (viewport != null) {
            viewport.free();
            viewport = null;
        }
        if (scissor != null) {
            scissor.free();
            scissor = null;
        }
        if (acquiredImage != null) {
            memFree(acquiredImage);
            acquiredImage = null;
        }
        if (presentWaitInfo != null) {
            presentWaitInfo.free();
            presentWaitInfo = null;
        }
        if (vertexBinding != null) {
            memFree(vertexBinding);
            vertexBinding = null;
        }
        if (vertexOffset != null) {
            memFree(vertexOffset);
            vertexOffset = null;
        }
        if (depthAttachment != null) {
            depthAttachment.free();
            depthAttachment = null;
        }
        if (frame != null) {
            frame.free();
            frame = null;
        }
        for (int index = 0; index < frames.length; index++) {
            if (frames[index] != null) {
                frames[index].freeNativeBuffers();
                frames[index] = null;
            }
        }
    }

    private void requireOwnerAndOpen() {
        requireOwnerThread();
        if (closed) {
            throw new IllegalStateException("Vulkan renderer is closed.");
        }
        window.handle();
    }

    private void requireOwnerThread() {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException("Vulkan renderer access must stay on the SDL window's creating thread.");
        }
    }

    private static void requireSuccess(int result, String operation) {
        if (result != VK_SUCCESS) {
            throw new IllegalStateException(operation + " failed with VkResult " + result);
        }
    }

    private final class OwnedBuffer implements RenderBuffer {
        private final VulkanBuffer delegate;
        private boolean released;

        private OwnedBuffer(VulkanBuffer delegate) {
            this.delegate = delegate;
        }

        @Override
        public long nativeHandle() {
            requireOwnerAndOpen();
            return delegate.nativeHandle();
        }

        @Override
        public long size() {
            requireOwnerAndOpen();
            return delegate.size();
        }

        @Override
        public boolean isHostVisible() {
            requireOwnerAndOpen();
            return delegate.isHostVisible();
        }

        @Override
        public void upload(long offset, ByteBuffer data) {
            requireOwnerAndOpen();
            Objects.requireNonNull(data, "data");
            if (delegate.isHostVisible()) {
                delegate.upload(offset, data);
            } else {
                if (offset < 0 || offset > delegate.size() || data.remaining() > delegate.size() - offset) {
                    throw new IndexOutOfBoundsException("Upload range exceeds the Vulkan buffer.");
                }
                validateDeviceLocalUpload(offset, data.remaining());
                uploader.upload(delegate, offset, data);
            }
        }

        @Override
        public void close() {
            requireOwnerAndOpen();
            if (!released) {
                if (recording) {
                    throw new IllegalStateException("Cannot release a buffer during frame recording.");
                }
                requireSuccess(vkDeviceWaitIdle(context.device()), "vkDeviceWaitIdle before buffer destruction");
                released = true;
                buffers.remove(this);
                delegate.close();
            }
        }

        private void release() {
            if (!released) {
                released = true;
                delegate.close();
            }
        }
    }

    private final class FrameSlot {
        private final VkCommandBuffer commandBuffer;
        private final long imageAvailable;
        private final long fence;
        private VkCommandBufferBeginInfo beginInfo;
        private VkSubmitInfo submitInfo;
        private VkPresentInfoKHR presentInfo;
        private org.lwjgl.vulkan.VkPresentId2KHR presentId;
        private LongBuffer presentIds;
        private LongBuffer waitSemaphores;
        private IntBuffer waitStages;
        private PointerBuffer commandBuffers;
        private LongBuffer signalSemaphores;
        private LongBuffer presentWaitSemaphores;
        private LongBuffer presentSwapchains;
        private IntBuffer presentImageIndices;

        private FrameSlot(VulkanDeviceContext.FrameResources resources) {
            commandBuffer = resources.commandBuffer;
            imageAvailable = resources.imageAvailable;
            fence = resources.fence;
            try {
                beginInfo = VkCommandBufferBeginInfo.calloc().sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);
                submitInfo = VkSubmitInfo.calloc();
                presentInfo = VkPresentInfoKHR.calloc();
                waitSemaphores = memAllocLong(1);
                waitStages = memAllocInt(1);
                commandBuffers = memAllocPointer(1);
                signalSemaphores = memAllocLong(1);
                presentWaitSemaphores = memAllocLong(1);
                presentSwapchains = memAllocLong(1);
                presentImageIndices = memAllocInt(1);
                presentIds = memAllocLong(1);
                presentId = org.lwjgl.vulkan.VkPresentId2KHR.calloc()
                        .sType(org.lwjgl.vulkan.KHRPresentId2.VK_STRUCTURE_TYPE_PRESENT_ID_2_KHR)
                        .swapchainCount(1).pPresentIds(presentIds);
                waitSemaphores.put(0, imageAvailable);
                waitStages.put(0, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT);
                commandBuffers.put(0, commandBuffer.address());
                submitInfo.sType(VK_STRUCTURE_TYPE_SUBMIT_INFO).waitSemaphoreCount(1).pWaitSemaphores(waitSemaphores)
                        .pWaitDstStageMask(waitStages).pCommandBuffers(commandBuffers)
                        .pSignalSemaphores(signalSemaphores);
                presentInfo.sType(VK_STRUCTURE_TYPE_PRESENT_INFO_KHR)
                        .pWaitSemaphores(presentWaitSemaphores).swapchainCount(1)
                        .pSwapchains(presentSwapchains).pImageIndices(presentImageIndices);
            } catch (RuntimeException | Error failure) {
                freeNativeBuffers();
                throw failure;
            }
        }

        private void freeNativeBuffers() {
            if (beginInfo != null) {
                beginInfo.free();
                beginInfo = null;
            }
            if (submitInfo != null) {
                submitInfo.free();
                submitInfo = null;
            }
            if (presentInfo != null) {
                presentInfo.free();
                presentInfo = null;
            }
            if (waitSemaphores != null) {
                memFree(waitSemaphores);
                waitSemaphores = null;
            }
            if (waitStages != null) {
                memFree(waitStages);
                waitStages = null;
            }
            if (commandBuffers != null) {
                memFree(commandBuffers);
                commandBuffers = null;
            }
            if (signalSemaphores != null) {
                memFree(signalSemaphores);
                signalSemaphores = null;
            }
            if (presentWaitSemaphores != null) {
                memFree(presentWaitSemaphores);
                presentWaitSemaphores = null;
            }
            if (presentSwapchains != null) {
                memFree(presentSwapchains);
                presentSwapchains = null;
            }
            if (presentImageIndices != null) {
                memFree(presentImageIndices);
                presentImageIndices = null;
            }
            if (presentId != null) {
                presentId.free();
                presentId = null;
            }
            if (presentIds != null) {
                memFree(presentIds);
                presentIds = null;
            }
        }
    }
}
