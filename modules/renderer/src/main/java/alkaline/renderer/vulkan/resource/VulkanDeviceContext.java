package alkaline.renderer.vulkan.resource;

import alkaline.platform.sdl.SdlWindow;
import alkaline.renderer.vulkan.QueueFamilies;
import alkaline.renderer.vulkan.VulkanApiSupport;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.Objects;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan13Features;
import org.lwjgl.vulkan.VkFormatProperties;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkExtensionProperties;

import static org.lwjgl.sdl.SDLError.SDL_GetError;
import static org.lwjgl.sdl.SDLVulkan.SDL_Vulkan_CreateSurface;
import static org.lwjgl.sdl.SDLVulkan.SDL_Vulkan_DestroySurface;
import static org.lwjgl.sdl.SDLVulkan.SDL_Vulkan_GetInstanceExtensions;
import static org.lwjgl.vulkan.KHRSurface.vkGetPhysicalDeviceSurfacePresentModesKHR;
import static org.lwjgl.vulkan.KHRSurface.vkGetPhysicalDeviceSurfaceSupportKHR;
import static org.lwjgl.vulkan.KHRSurface.vkGetPhysicalDeviceSurfaceFormatsKHR;
import static org.lwjgl.vulkan.KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK10.VK_FENCE_CREATE_SIGNALED_BIT;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_BUFFER_LEVEL_PRIMARY;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_QUEUE_GRAPHICS_BIT;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_APPLICATION_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.vkAllocateCommandBuffers;
import static org.lwjgl.vulkan.VK10.vkCreateCommandPool;
import static org.lwjgl.vulkan.VK10.vkCreateDevice;
import static org.lwjgl.vulkan.VK10.vkCreateFence;
import static org.lwjgl.vulkan.VK10.vkCreateInstance;
import static org.lwjgl.vulkan.VK10.vkCreateSemaphore;
import static org.lwjgl.vulkan.VK10.vkDestroyCommandPool;
import static org.lwjgl.vulkan.VK10.vkDestroyDevice;
import static org.lwjgl.vulkan.VK10.vkDestroyFence;
import static org.lwjgl.vulkan.VK10.vkDestroyInstance;
import static org.lwjgl.vulkan.VK10.vkDestroySemaphore;
import static org.lwjgl.vulkan.VK10.vkDeviceWaitIdle;
import static org.lwjgl.vulkan.VK10.vkEnumerateDeviceExtensionProperties;
import static org.lwjgl.vulkan.VK10.vkEnumeratePhysicalDevices;
import static org.lwjgl.vulkan.VK10.vkGetDeviceQueue;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceProperties;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceQueueFamilyProperties;

/** Owns Vulkan instance, SDL surface, selected device, queues, command pool, and frame sync objects. */
final class VulkanDeviceContext implements AutoCloseable {
    private static final int FRAMES_IN_FLIGHT = 2;
    private static final String VALIDATION_LAYER = "VK_LAYER_KHRONOS_validation";

    final FrameResources[] frames = new FrameResources[FRAMES_IN_FLIGHT];

    private final SdlWindow window;
    private VkInstance instance;
    private VkPhysicalDevice physicalDevice;
    private VkDevice device;
    private VkQueue graphicsQueue;
    private VkQueue presentQueue;
    private int graphicsFamily = -1;
    private int presentFamily = -1;
    private long surface;
    private long commandPool;
    private int depthFormat;
    private boolean multiDrawIndirect;
    private boolean swapchainMutableFormat;
    private static final String MUTABLE_FORMAT_EXTENSION = "VK_KHR_swapchain_mutable_format";
    /** VK_KHR_get_surface_capabilities2 on the instance: needed to ask a surface about present waits. */
    private boolean surfaceCapabilities2;
    private boolean presentWait;
    private boolean hostImageCopy;
    private boolean depthClamp;

    private final boolean validation;

    VulkanDeviceContext(SdlWindow window, boolean validation) {
        this.window = Objects.requireNonNull(window, "window");
        this.validation = validation;
        window.handle();
        try {
            initializeInstanceAndSurface();
            selectPhysicalDevice();
            createDevice();
            createCommandPoolAndFrames();
        } catch (RuntimeException | Error failure) {
            try {
                close();
            } catch (RuntimeException | Error cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    VkInstance instance() { return instance; }
    VkPhysicalDevice physicalDevice() { return physicalDevice; }

    /** Whether the depthClamp feature is enabled (pipelines may clamp depth instead of clipping it). */
    boolean depthClamp() { return depthClamp; }

    /** Whether Vulkan 1.4 host image copy is enabled for sampled textures ({@link #supportsHostImageCopy}). */
    boolean hostImageCopy() { return hostImageCopy; }

    /** Whether multiDrawIndirect and drawIndirectFirstInstance were both available and enabled. */
    boolean multiDrawIndirect() { return multiDrawIndirect; }

    /** Whether VK_KHR_swapchain_mutable_format was available and enabled (UNORM views of sRGB swapchains). */
    boolean swapchainMutableFormat() { return swapchainMutableFormat; }

    /**
     * Whether VK_KHR_present_id2 and VK_KHR_present_wait2 are enabled and the surface supports both: the renderer
     * can then wait until a presented frame reaches the screen (V-Sync pacing, decision 0010).
     */
    boolean presentWait() { return presentWait; }
    VkDevice device() { return device; }
    VkQueue graphicsQueue() { return graphicsQueue; }
    VkQueue presentQueue() { return presentQueue; }
    int graphicsFamily() { return graphicsFamily; }
    int presentFamily() { return presentFamily; }
    long surface() { return surface; }
    long commandPool() { return commandPool; }
    int depthFormat() { return depthFormat; }

    @Override
    public void close() {
        RuntimeException failure = null;
        if (device != null) {
            int idle = vkDeviceWaitIdle(device);
            if (idle != VK_SUCCESS) {
                failure = new IllegalStateException("vkDeviceWaitIdle failed with VkResult " + idle);
            }
            for (FrameResources frame : frames) {
                if (frame != null && frame.imageAvailable != 0) {
                    vkDestroySemaphore(device, frame.imageAvailable, null);
                }
                if (frame != null && frame.fence != 0) {
                    vkDestroyFence(device, frame.fence, null);
                }
            }
            if (commandPool != 0) {
                vkDestroyCommandPool(device, commandPool, null);
                commandPool = 0;
            }
            vkDestroyDevice(device, null);
            device = null;
        }
        if (instance != null) {
            if (surface != 0) {
                SDL_Vulkan_DestroySurface(instance, surface, null);
                surface = 0;
            }
            vkDestroyInstance(instance, null);
            instance = null;
        }
        if (failure != null) {
            throw failure;
        }
    }

    private void initializeInstanceAndSurface() {
        VulkanApiSupport.requireSupportedLoader(VK.getInstanceVersionSupported());
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer extensions = SDL_Vulkan_GetInstanceExtensions();
            if (extensions == null || !extensions.hasRemaining()) {
                throw new IllegalStateException("SDL Vulkan instance extensions unavailable: " + SDL_GetError());
            }
            surfaceCapabilities2 = instanceExtensionAvailable(stack,
                    org.lwjgl.vulkan.KHRGetSurfaceCapabilities2.VK_KHR_GET_SURFACE_CAPABILITIES_2_EXTENSION_NAME);
            PointerBuffer names = stack.mallocPointer(extensions.remaining() + 1);
            for (int index = extensions.position(); index < extensions.limit(); index++) {
                names.put(extensions.get(index));
            }
            if (surfaceCapabilities2) {
                names.put(stack.UTF8(org.lwjgl.vulkan.KHRGetSurfaceCapabilities2.VK_KHR_GET_SURFACE_CAPABILITIES_2_EXTENSION_NAME));
            }
            names.flip();
            VkApplicationInfo app = VkApplicationInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
                    .pApplicationName(stack.UTF8("Alkaline Vulkan renderer"))
                    // The highest version the renderer can use; 1.4 features stay optional and are gated per device.
                    .apiVersion(org.lwjgl.vulkan.VK14.VK_API_VERSION_1_4);
            VkInstanceCreateInfo create = VkInstanceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO).pApplicationInfo(app)
                    .ppEnabledExtensionNames(names);
            if (validation) {
                if (instanceLayerAvailable(stack, VALIDATION_LAYER)) {
                    create.ppEnabledLayerNames(stack.pointers(stack.UTF8(VALIDATION_LAYER)));
                    System.out.println("Vulkan validation layer enabled.");
                } else {
                    System.err.println("Vulkan validation requested, but " + VALIDATION_LAYER + " is not installed.");
                }
            }
            PointerBuffer address = stack.mallocPointer(1);
            requireSuccess(vkCreateInstance(create, null, address), "vkCreateInstance");
            instance = new VkInstance(address.get(0), create);
            LongBuffer surfaceAddress = stack.mallocLong(1);
            if (!SDL_Vulkan_CreateSurface(window.handle(), instance, null, surfaceAddress)) {
                throw new IllegalStateException("SDL_Vulkan_CreateSurface failed: " + SDL_GetError());
            }
            surface = surfaceAddress.get(0);
        }
    }

    private static boolean instanceLayerAvailable(MemoryStack stack, String name) {
        IntBuffer count = stack.callocInt(1);
        if (org.lwjgl.vulkan.VK10.vkEnumerateInstanceLayerProperties(count, null) != VK_SUCCESS || count.get(0) == 0) {
            return false;
        }
        org.lwjgl.vulkan.VkLayerProperties.Buffer layers = org.lwjgl.vulkan.VkLayerProperties.malloc(count.get(0), stack);
        if (org.lwjgl.vulkan.VK10.vkEnumerateInstanceLayerProperties(count, layers) != VK_SUCCESS) {
            return false;
        }
        for (int index = 0; index < count.get(0); index++) {
            if (layers.get(index).layerNameString().equals(name)) {
                return true;
            }
        }
        return false;
    }

    private void selectPhysicalDevice() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer count = stack.callocInt(1);
            requireSuccess(vkEnumeratePhysicalDevices(instance, count, null), "vkEnumeratePhysicalDevices count");
            if (count.get(0) == 0) {
                throw new IllegalStateException("No Vulkan physical devices are available.");
            }
            PointerBuffer handles = stack.mallocPointer(count.get(0));
            requireSuccess(vkEnumeratePhysicalDevices(instance, count, handles), "vkEnumeratePhysicalDevices");
            String lastFailure = "No physical device satisfies Vulkan 1.3, dynamic rendering, queues, and swapchain requirements.";
            for (int index = 0; index < count.get(0); index++) {
                try (MemoryStack candidateStack = MemoryStack.stackPush()) {
                    VkPhysicalDevice candidate = new VkPhysicalDevice(handles.get(index), instance);
                    VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.malloc(candidateStack);
                    vkGetPhysicalDeviceProperties(candidate, properties);
                    if (!VulkanApiSupport.meetsMinimum(properties.apiVersion())) {
                        lastFailure = properties.deviceNameString() + " does not support Vulkan 1.3.";
                        continue;
                    }
                    QueueFamilies queues = findQueueFamilies(candidate, candidateStack);
                    if (queues.graphics() < 0 || queues.present() < 0) {
                        lastFailure = properties.deviceNameString() + " has no graphics/present queue pair.";
                        continue;
                    }
                    if (!hasExtension(candidate, VK_KHR_SWAPCHAIN_EXTENSION_NAME)) {
                        lastFailure = properties.deviceNameString() + " does not expose VK_KHR_swapchain.";
                        continue;
                    }
                    VkPhysicalDeviceVulkan13Features v13 = VkPhysicalDeviceVulkan13Features.calloc(candidateStack)
                            .sType(org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES);
                    VkPhysicalDeviceVulkan12Features v12 = VkPhysicalDeviceVulkan12Features.calloc(candidateStack)
                            .sType(org.lwjgl.vulkan.VK12.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES);
                    // LWJGL's typed pNext prepends: the resulting chain is features -> v12 -> v13.
                    VkPhysicalDeviceFeatures2 features = VkPhysicalDeviceFeatures2.calloc(candidateStack)
                            .sType(org.lwjgl.vulkan.VK11.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2)
                            .pNext(v13).pNext(v12);
                    org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceFeatures2(candidate, features);
                    if (!v13.dynamicRendering()) {
                        lastFailure = properties.deviceNameString() + " does not support Vulkan 1.3 dynamic rendering.";
                        continue;
                    }
                    if (!v13.shaderDemoteToHelperInvocation()) {
                        lastFailure = properties.deviceNameString() + " does not support shader demote (discard).";
                        continue;
                    }
                    if (!v12.bufferDeviceAddress()) {
                        lastFailure = properties.deviceNameString() + " does not support buffer device addresses.";
                        continue;
                    }
                    int candidateDepth = findDepthFormat(candidate, candidateStack);
                    if (candidateDepth == 0) {
                        lastFailure = properties.deviceNameString() + " has no optimal-tiling depth attachment format.";
                        continue;
                    }
                    if (!hasSurfaceFormats(candidate, candidateStack) || !hasPresentModes(candidate, candidateStack)) {
                        lastFailure = properties.deviceNameString() + " cannot present to the SDL surface.";
                        continue;
                    }
                    physicalDevice = candidate;
                    depthFormat = candidateDepth;
                    // Optional: many draws per indirect call, each with its own first instance (the world
                    // renderer's per-section data). Without them the renderer issues direct draws instead.
                    multiDrawIndirect = features.features().multiDrawIndirect()
                            && features.features().drawIndirectFirstInstance();
                    // Optional: UNORM views of the sRGB swapchain, so the glint blends as vanilla's does.
                    swapchainMutableFormat = hasExtension(candidate, MUTABLE_FORMAT_EXTENSION);
                    // Optional: waiting for a present to reach the screen paces V-Sync to the display.
                    presentWait = supportsPresentWait(candidate, candidateStack);
                    // Optional: the sky draws at exactly the far plane, after the terrain (WorldRenderer).
                    depthClamp = features.features().depthClamp();
                    // Optional, Vulkan 1.4 only: textures written from the CPU without a queue round trip.
                    hostImageCopy = supportsHostImageCopy(candidate, properties.apiVersion(), candidateStack);
                    graphicsFamily = queues.graphics();
                    presentFamily = queues.present();
                    return;
                }
            }
            throw new IllegalStateException(lastFailure);
        }
    }

    private QueueFamilies findQueueFamilies(VkPhysicalDevice candidate, MemoryStack stack) {
        IntBuffer count = stack.callocInt(1);
        vkGetPhysicalDeviceQueueFamilyProperties(candidate, count, null);
        org.lwjgl.vulkan.VkQueueFamilyProperties.Buffer families =
                org.lwjgl.vulkan.VkQueueFamilyProperties.malloc(count.get(0), stack);
        vkGetPhysicalDeviceQueueFamilyProperties(candidate, count, families);
        boolean[] graphics = new boolean[count.get(0)];
        boolean[] present = new boolean[count.get(0)];
        IntBuffer supported = stack.callocInt(1);
        for (int index = 0; index < count.get(0); index++) {
            graphics[index] = families.get(index).queueCount() > 0
                    && (families.get(index).queueFlags() & VK_QUEUE_GRAPHICS_BIT) != 0;
            supported.put(0, 0);
            requireSuccess(vkGetPhysicalDeviceSurfaceSupportKHR(candidate, index, surface, supported),
                    "vkGetPhysicalDeviceSurfaceSupportKHR");
            present[index] = families.get(index).queueCount() > 0 && supported.get(0) != 0;
        }
        return QueueFamilies.choose(graphics, present);
    }

    /**
     * Vulkan 1.4's host image copy for the sampled RGBA8 textures: the feature, both formats' host transfer
     * support, copies straight into SHADER_READ_ONLY_OPTIMAL, and no loss of device access speed for images that
     * allow it (optimalDeviceAccess). Only then do textures skip the staging copy.
     */
    private static boolean supportsHostImageCopy(VkPhysicalDevice candidate, int apiVersion, MemoryStack stack) {
        if (org.lwjgl.vulkan.VK10.VK_API_VERSION_MAJOR(apiVersion) == 1
                && org.lwjgl.vulkan.VK10.VK_API_VERSION_MINOR(apiVersion) < 4) {
            return false;
        }
        var v14 = org.lwjgl.vulkan.VkPhysicalDeviceVulkan14Features.calloc(stack)
                .sType(org.lwjgl.vulkan.VK14.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_4_FEATURES);
        VkPhysicalDeviceFeatures2 features = VkPhysicalDeviceFeatures2.calloc(stack)
                .sType(org.lwjgl.vulkan.VK11.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2).pNext(v14);
        org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceFeatures2(candidate, features);
        if (!v14.hostImageCopy()) {
            return false;
        }
        var copy = org.lwjgl.vulkan.VkPhysicalDeviceHostImageCopyProperties.calloc(stack)
                .sType(org.lwjgl.vulkan.VK14.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_HOST_IMAGE_COPY_PROPERTIES);
        var properties = org.lwjgl.vulkan.VkPhysicalDeviceProperties2.calloc(stack)
                .sType(org.lwjgl.vulkan.VK11.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2).pNext(copy);
        org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceProperties2(candidate, properties);
        IntBuffer layouts = stack.mallocInt(copy.copyDstLayoutCount());
        copy.pCopyDstLayouts(layouts);
        org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceProperties2(candidate, properties);
        boolean shaderRead = false;
        for (int index = 0; index < copy.copyDstLayoutCount(); index++) {
            shaderRead |= layouts.get(index) == org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        }
        if (!shaderRead) {
            return false;
        }
        for (int format : new int[]{org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM,
                org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_SRGB}) {
            var formatFeatures = org.lwjgl.vulkan.VkFormatProperties3.calloc(stack)
                    .sType(org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_FORMAT_PROPERTIES_3);
            var formatProperties = org.lwjgl.vulkan.VkFormatProperties2.calloc(stack)
                    .sType(org.lwjgl.vulkan.VK11.VK_STRUCTURE_TYPE_FORMAT_PROPERTIES_2).pNext(formatFeatures);
            org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceFormatProperties2(candidate, format, formatProperties);
            if ((formatFeatures.optimalTilingFeatures()
                    & org.lwjgl.vulkan.VK14.VK_FORMAT_FEATURE_2_HOST_IMAGE_TRANSFER_BIT) == 0) {
                return false;
            }
            var performance = org.lwjgl.vulkan.VkHostImageCopyDevicePerformanceQuery.calloc(stack)
                    .sType(org.lwjgl.vulkan.VK14.VK_STRUCTURE_TYPE_HOST_IMAGE_COPY_DEVICE_PERFORMANCE_QUERY);
            var imageProperties = org.lwjgl.vulkan.VkImageFormatProperties2.calloc(stack)
                    .sType(org.lwjgl.vulkan.VK11.VK_STRUCTURE_TYPE_IMAGE_FORMAT_PROPERTIES_2).pNext(performance);
            var imageInfo = org.lwjgl.vulkan.VkPhysicalDeviceImageFormatInfo2.calloc(stack)
                    .sType(org.lwjgl.vulkan.VK11.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_IMAGE_FORMAT_INFO_2)
                    .format(format).type(org.lwjgl.vulkan.VK10.VK_IMAGE_TYPE_2D)
                    .tiling(org.lwjgl.vulkan.VK10.VK_IMAGE_TILING_OPTIMAL)
                    .usage(VulkanTexture.STATIC_USAGE | org.lwjgl.vulkan.VK14.VK_IMAGE_USAGE_HOST_TRANSFER_BIT);
            if (org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceImageFormatProperties2(candidate, imageInfo,
                    imageProperties) != VK_SUCCESS || !performance.optimalDeviceAccess()) {
                return false;
            }
        }
        return true;
    }

    /** Present id 2 and present wait 2: device extensions, their features, and the surface's support. */
    private boolean supportsPresentWait(VkPhysicalDevice candidate, MemoryStack stack) {
        if (!surfaceCapabilities2
                || !hasExtension(candidate, org.lwjgl.vulkan.KHRPresentId2.VK_KHR_PRESENT_ID_2_EXTENSION_NAME)
                || !hasExtension(candidate, org.lwjgl.vulkan.KHRPresentWait2.VK_KHR_PRESENT_WAIT_2_EXTENSION_NAME)) {
            return false;
        }
        var id2 = org.lwjgl.vulkan.VkPhysicalDevicePresentId2FeaturesKHR.calloc(stack)
                .sType(org.lwjgl.vulkan.KHRPresentId2.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PRESENT_ID_2_FEATURES_KHR);
        var wait2 = org.lwjgl.vulkan.VkPhysicalDevicePresentWait2FeaturesKHR.calloc(stack)
                .sType(org.lwjgl.vulkan.KHRPresentWait2.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PRESENT_WAIT_2_FEATURES_KHR)
                .pNext(id2.address());
        VkPhysicalDeviceFeatures2 features = VkPhysicalDeviceFeatures2.calloc(stack)
                .sType(org.lwjgl.vulkan.VK11.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2).pNext(wait2.address());
        org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceFeatures2(candidate, features);
        if (!id2.presentId2() || !wait2.presentWait2()) {
            return false;
        }
        var surfaceId2 = org.lwjgl.vulkan.VkSurfaceCapabilitiesPresentId2KHR.calloc(stack)
                .sType(org.lwjgl.vulkan.KHRPresentId2.VK_STRUCTURE_TYPE_SURFACE_CAPABILITIES_PRESENT_ID_2_KHR);
        var surfaceWait2 = org.lwjgl.vulkan.VkSurfaceCapabilitiesPresentWait2KHR.calloc(stack)
                .sType(org.lwjgl.vulkan.KHRPresentWait2.VK_STRUCTURE_TYPE_SURFACE_CAPABILITIES_PRESENT_WAIT_2_KHR)
                .pNext(surfaceId2.address());
        var capabilities = org.lwjgl.vulkan.VkSurfaceCapabilities2KHR.calloc(stack)
                .sType(org.lwjgl.vulkan.KHRGetSurfaceCapabilities2.VK_STRUCTURE_TYPE_SURFACE_CAPABILITIES_2_KHR)
                .pNext(surfaceWait2.address());
        var info = org.lwjgl.vulkan.VkPhysicalDeviceSurfaceInfo2KHR.calloc(stack)
                .sType(org.lwjgl.vulkan.KHRGetSurfaceCapabilities2.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SURFACE_INFO_2_KHR)
                .surface(surface);
        int result = org.lwjgl.vulkan.KHRGetSurfaceCapabilities2.vkGetPhysicalDeviceSurfaceCapabilities2KHR(candidate,
                info, capabilities);
        return result == org.lwjgl.vulkan.VK10.VK_SUCCESS && surfaceId2.presentId2Supported()
                && surfaceWait2.presentWait2Supported();
    }

    private boolean instanceExtensionAvailable(MemoryStack stack, String name) {
        IntBuffer count = stack.callocInt(1);
        requireSuccess(org.lwjgl.vulkan.VK10.vkEnumerateInstanceExtensionProperties((java.nio.ByteBuffer) null, count, null),
                "vkEnumerateInstanceExtensionProperties count");
        var properties = org.lwjgl.vulkan.VkExtensionProperties.malloc(count.get(0), stack);
        requireSuccess(org.lwjgl.vulkan.VK10.vkEnumerateInstanceExtensionProperties((java.nio.ByteBuffer) null, count,
                properties), "vkEnumerateInstanceExtensionProperties");
        for (int index = 0; index < count.get(0); index++) {
            if (name.equals(properties.get(index).extensionNameString())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasExtension(VkPhysicalDevice candidate, String name) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            return hasExtension(candidate, name, stack);
        }
    }

    private static boolean hasExtension(VkPhysicalDevice candidate, String name, MemoryStack stack) {
        IntBuffer count = stack.callocInt(1);
        requireSuccess(vkEnumerateDeviceExtensionProperties(candidate, (ByteBuffer) null, count, null),
                "vkEnumerateDeviceExtensionProperties count");
        try (VkExtensionProperties.Buffer extensions = VkExtensionProperties.malloc(count.get(0))) {
            requireSuccess(vkEnumerateDeviceExtensionProperties(candidate, (ByteBuffer) null, count, extensions),
                    "vkEnumerateDeviceExtensionProperties");
            for (int index = 0; index < count.get(0); index++) {
                if (name.equals(extensions.get(index).extensionNameString())) {
                    return true;
                }
            }
            return false;
        }
    }

    /** Prefers 32-bit float depth; every candidate must support optimal-tiling depth attachments. */
    private static int findDepthFormat(VkPhysicalDevice candidate, MemoryStack stack) {
        int[] candidates = {
                org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT,
                org.lwjgl.vulkan.VK10.VK_FORMAT_X8_D24_UNORM_PACK32,
                org.lwjgl.vulkan.VK10.VK_FORMAT_D24_UNORM_S8_UINT,
                org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT_S8_UINT,
        };
        VkFormatProperties properties = VkFormatProperties.malloc(stack);
        for (int format : candidates) {
            org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceFormatProperties(candidate, format, properties);
            if ((properties.optimalTilingFeatures()
                    & org.lwjgl.vulkan.VK10.VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT) != 0) {
                return format;
            }
        }
        return 0;
    }

    private boolean hasSurfaceFormats(VkPhysicalDevice candidate, MemoryStack stack) {
        IntBuffer count = stack.callocInt(1);
        requireSuccess(vkGetPhysicalDeviceSurfaceFormatsKHR(candidate, surface, count, null),
                "vkGetPhysicalDeviceSurfaceFormatsKHR count");
        return count.get(0) > 0;
    }

    private boolean hasPresentModes(VkPhysicalDevice candidate, MemoryStack stack) {
        IntBuffer count = stack.callocInt(1);
        requireSuccess(vkGetPhysicalDeviceSurfacePresentModesKHR(candidate, surface, count, null),
                "vkGetPhysicalDeviceSurfacePresentModesKHR count");
        return count.get(0) > 0;
    }

    private void createDevice() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int queueCount = graphicsFamily == presentFamily ? 1 : 2;
            VkDeviceQueueCreateInfo.Buffer queues = VkDeviceQueueCreateInfo.calloc(queueCount, stack);
            FloatBuffer priority = stack.floats(1.0f);
            queues.get(0).sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
                    .queueFamilyIndex(graphicsFamily).pQueuePriorities(priority);
            if (queueCount == 2) {
                queues.get(1).sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
                        .queueFamilyIndex(presentFamily).pQueuePriorities(priority);
            }
            var enabledId2 = org.lwjgl.vulkan.VkPhysicalDevicePresentId2FeaturesKHR.calloc(stack)
                    .sType(org.lwjgl.vulkan.KHRPresentId2.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PRESENT_ID_2_FEATURES_KHR)
                    .presentId2(true);
            var enabledWait2 = org.lwjgl.vulkan.VkPhysicalDevicePresentWait2FeaturesKHR.calloc(stack)
                    .sType(org.lwjgl.vulkan.KHRPresentWait2.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PRESENT_WAIT_2_FEATURES_KHR)
                    .pNext(enabledId2.address()).presentWait2(true);
            var enabled14 = org.lwjgl.vulkan.VkPhysicalDeviceVulkan14Features.calloc(stack)
                    .sType(org.lwjgl.vulkan.VK14.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_4_FEATURES)
                    .pNext(presentWait ? enabledWait2.address() : 0L)
                    .hostImageCopy(true);
            long after13 = hostImageCopy ? enabled14.address() : presentWait ? enabledWait2.address() : 0L;
            VkPhysicalDeviceVulkan13Features enabled13 = VkPhysicalDeviceVulkan13Features.calloc(stack)
                    .sType(org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES)
                    .pNext(after13)
                    .dynamicRendering(true)
                    // glslc's Vulkan 1.3 target compiles discard to OpDemoteToHelperInvocation.
                    .shaderDemoteToHelperInvocation(true);
            // Stream-buffer data is addressed directly from shaders; queried during device selection.
            VkPhysicalDeviceVulkan12Features enabled12 = VkPhysicalDeviceVulkan12Features.calloc(stack)
                    .sType(org.lwjgl.vulkan.VK12.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES)
                    .pNext(enabled13.address())
                    .bufferDeviceAddress(true);
            PointerBuffer extensions = stack.mallocPointer(4).put(stack.UTF8(VK_KHR_SWAPCHAIN_EXTENSION_NAME));
            if (swapchainMutableFormat) {
                // Its dependencies (image format lists, maintenance2) are core in Vulkan 1.2 and 1.1.
                extensions.put(stack.UTF8(MUTABLE_FORMAT_EXTENSION));
            }
            if (presentWait) {
                extensions.put(stack.UTF8(org.lwjgl.vulkan.KHRPresentId2.VK_KHR_PRESENT_ID_2_EXTENSION_NAME));
                extensions.put(stack.UTF8(org.lwjgl.vulkan.KHRPresentWait2.VK_KHR_PRESENT_WAIT_2_EXTENSION_NAME));
            }
            extensions.flip();
            org.lwjgl.vulkan.VkPhysicalDeviceFeatures core = org.lwjgl.vulkan.VkPhysicalDeviceFeatures.calloc(stack)
                    .multiDrawIndirect(multiDrawIndirect)
                    .drawIndirectFirstInstance(multiDrawIndirect)
                    .depthClamp(depthClamp);
            VkDeviceCreateInfo create = VkDeviceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO).pNext(enabled12.address())
                    .pQueueCreateInfos(queues).ppEnabledExtensionNames(extensions).pEnabledFeatures(core);
            PointerBuffer address = stack.mallocPointer(1);
            requireSuccess(vkCreateDevice(physicalDevice, create, null, address), "vkCreateDevice");
            device = new VkDevice(address.get(0), physicalDevice, create);
            PointerBuffer graphics = stack.mallocPointer(1);
            vkGetDeviceQueue(device, graphicsFamily, 0, graphics);
            graphicsQueue = new VkQueue(graphics.get(0), device);
            PointerBuffer present = stack.mallocPointer(1);
            vkGetDeviceQueue(device, presentFamily, 0, present);
            presentQueue = new VkQueue(present.get(0), device);
        }
    }

    private void createCommandPoolAndFrames() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT).queueFamilyIndex(graphicsFamily);
            LongBuffer poolAddress = stack.mallocLong(1);
            requireSuccess(vkCreateCommandPool(device, poolInfo, null, poolAddress), "vkCreateCommandPool");
            commandPool = poolAddress.get(0);
            VkCommandBufferAllocateInfo allocate = VkCommandBufferAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO).commandPool(commandPool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(FRAMES_IN_FLIGHT);
            PointerBuffer commands = stack.mallocPointer(FRAMES_IN_FLIGHT);
            requireSuccess(vkAllocateCommandBuffers(device, allocate, commands), "vkAllocateCommandBuffers");
            VkSemaphoreCreateInfo semaphoreInfo = VkSemaphoreCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO);
            VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO).flags(VK_FENCE_CREATE_SIGNALED_BIT);
            for (int index = 0; index < FRAMES_IN_FLIGHT; index++) {
                LongBuffer semaphoreAddress = stack.mallocLong(1);
                requireSuccess(vkCreateSemaphore(device, semaphoreInfo, null, semaphoreAddress), "vkCreateSemaphore");
                long semaphore = semaphoreAddress.get(0);
                try {
                    LongBuffer fenceAddress = stack.mallocLong(1);
                    requireSuccess(vkCreateFence(device, fenceInfo, null, fenceAddress), "vkCreateFence");
                    frames[index] = new FrameResources(new VkCommandBuffer(commands.get(index), device),
                            semaphore, fenceAddress.get(0));
                } catch (RuntimeException | Error failure) {
                    vkDestroySemaphore(device, semaphore, null);
                    throw failure;
                }
            }
        }
    }

    private static void requireSuccess(int result, String operation) {
        if (result != VK_SUCCESS) {
            throw new IllegalStateException(operation + " failed with VkResult " + result);
        }
    }

    static final class FrameResources {
        final VkCommandBuffer commandBuffer;
        final long imageAvailable;
        final long fence;

        FrameResources(VkCommandBuffer commandBuffer, long imageAvailable, long fence) {
            this.commandBuffer = commandBuffer;
            this.imageAvailable = imageAvailable;
            this.fence = fence;
        }
    }
}
