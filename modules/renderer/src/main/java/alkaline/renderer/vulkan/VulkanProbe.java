package alkaline.renderer.vulkan;

import alkaline.platform.sdl.SdlWindow;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkQueueFamilyProperties;
import org.lwjgl.vulkan.VkSurfaceFormatKHR;

import static org.lwjgl.sdl.SDLError.SDL_GetError;
import static org.lwjgl.sdl.SDLVulkan.SDL_Vulkan_CreateSurface;
import static org.lwjgl.sdl.SDLVulkan.SDL_Vulkan_DestroySurface;
import static org.lwjgl.sdl.SDLVulkan.SDL_Vulkan_GetInstanceExtensions;
import static org.lwjgl.system.JNI.callPPV;
import static org.lwjgl.vulkan.KHRSurface.vkGetPhysicalDeviceSurfaceFormatsKHR;
import static org.lwjgl.vulkan.KHRSurface.vkGetPhysicalDeviceSurfacePresentModesKHR;
import static org.lwjgl.vulkan.KHRSurface.vkGetPhysicalDeviceSurfaceSupportKHR;
import static org.lwjgl.vulkan.KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK10.VK_QUEUE_GRAPHICS_BIT;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_APPLICATION_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.vkCreateInstance;
import static org.lwjgl.vulkan.VK10.vkDestroyInstance;
import static org.lwjgl.vulkan.VK10.vkEnumerateDeviceExtensionProperties;
import static org.lwjgl.vulkan.VK10.vkEnumeratePhysicalDevices;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceProperties;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceQueueFamilyProperties;

/** Probes an SDL window's Vulkan surface without creating a logical device or rendering a frame. */
public final class VulkanProbe {
    private VulkanProbe() {
    }

    public static List<DeviceReport> inspect(SdlWindow window) {
        Objects.requireNonNull(window, "window");
        window.handle();
        VulkanApiSupport.requireSupportedLoader(VK.getInstanceVersionSupported());
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer required = SDL_Vulkan_GetInstanceExtensions();
            if (required == null || !required.hasRemaining()) {
                throw new IllegalStateException("SDL Vulkan instance extensions unavailable: " + SDL_GetError());
            }
            PointerBuffer names = stack.mallocPointer(required.remaining());
            for (int i = required.position(); i < required.limit(); i++) {
                names.put(required.get(i));
            }
            names.flip();
            VkApplicationInfo application = VkApplicationInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
                    .pApplicationName(stack.UTF8("Alkaline 1.8.9 diagnostic"))
                    .apiVersion(VulkanApiSupport.MINIMUM_API_VERSION);
            VkInstanceCreateInfo createInfo = VkInstanceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                    .pApplicationInfo(application)
                    .ppEnabledExtensionNames(names);
            PointerBuffer address = stack.mallocPointer(1);
            requireSuccess(vkCreateInstance(createInfo, null, address), "vkCreateInstance");
            VkInstance instance;
            try {
                instance = new VkInstance(address.get(0), createInfo);
            } catch (RuntimeException | Error failure) {
                long destructor = org.lwjgl.vulkan.VK10.vkGetInstanceProcAddr(null,
                        stack.UTF8("vkDestroyInstance"));
                if (destructor != 0) {
                    callPPV(address.get(0), 0, destructor);
                }
                throw failure;
            }
            try {
                LongBuffer surfaceAddress = stack.mallocLong(1);
                if (!SDL_Vulkan_CreateSurface(window.handle(), instance, null, surfaceAddress)) {
                    throw new IllegalStateException("SDL_Vulkan_CreateSurface failed: " + SDL_GetError());
                }
                long surface = surfaceAddress.get(0);
                try {
                    return inspectDevices(instance, surface, stack);
                } finally {
                    SDL_Vulkan_DestroySurface(instance, surface, null);
                }
            } finally {
                vkDestroyInstance(instance, null);
            }
        }
    }

    private static List<DeviceReport> inspectDevices(VkInstance instance, long surface, MemoryStack stack) {
        IntBuffer count = stack.callocInt(1);
        requireSuccess(vkEnumeratePhysicalDevices(instance, count, null), "vkEnumeratePhysicalDevices count");
        if (count.get(0) == 0) {
            return List.of();
        }
        PointerBuffer addresses = stack.mallocPointer(count.get(0));
        requireSuccess(vkEnumeratePhysicalDevices(instance, count, addresses), "vkEnumeratePhysicalDevices");
        List<DeviceReport> reports = new ArrayList<>(count.get(0));
        for (int index = 0; index < count.get(0); index++) {
            VkPhysicalDevice device = new VkPhysicalDevice(addresses.get(index), instance);
            reports.add(inspectDevice(device, surface, stack));
        }
        return List.copyOf(reports);
    }

    private static DeviceReport inspectDevice(VkPhysicalDevice device, long surface, MemoryStack stack) {
        VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.malloc(stack);
        vkGetPhysicalDeviceProperties(device, properties);
        int api = properties.apiVersion();
        String name = properties.deviceNameString();
        int vendor = properties.vendorID();
        int id = properties.deviceID();
        if (!VulkanApiSupport.meetsMinimum(api)) {
            return new DeviceReport(name, api, vendor, id, -1, -1, false, List.of(), List.of(),
                    "Vulkan 1.3 or newer is required; device reports " + VulkanApiSupport.format(api));
        }
        try (MemoryStack frame = MemoryStack.stackPush()) {
            QueueFamilies queues = queueFamilies(device, surface, frame);
            boolean swapchain = hasSwapchain(device, frame);
            List<String> formats = surfaceFormats(device, surface, frame);
            List<Integer> modes = presentModes(device, surface, frame);
            String failure = null;
            if (queues.graphics() < 0 || queues.present() < 0) {
                failure = "Missing graphics or presentation queue";
            } else if (!swapchain) {
                failure = "VK_KHR_swapchain unavailable";
            } else if (formats.isEmpty() || modes.isEmpty()) {
                failure = "No surface format or presentation mode";
            }
            return new DeviceReport(name, api, vendor, id, queues.graphics(), queues.present(),
                    swapchain, formats, modes, failure);
        } catch (IllegalStateException failure) {
            return new DeviceReport(name, api, vendor, id, -1, -1, false,
                    List.of(), List.of(), failure.getMessage());
        }
    }

    private static QueueFamilies queueFamilies(VkPhysicalDevice device, long surface, MemoryStack stack) {
        IntBuffer count = stack.callocInt(1);
        vkGetPhysicalDeviceQueueFamilyProperties(device, count, null);
        VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.malloc(count.get(0), stack);
        vkGetPhysicalDeviceQueueFamilyProperties(device, count, families);
        boolean[] graphics = new boolean[count.get(0)];
        boolean[] present = new boolean[count.get(0)];
        IntBuffer supported = stack.callocInt(1);
        for (int i = 0; i < count.get(0); i++) {
            graphics[i] = families.get(i).queueCount() > 0
                    && (families.get(i).queueFlags() & VK_QUEUE_GRAPHICS_BIT) != 0;
            supported.put(0, 0);
            requireSuccess(vkGetPhysicalDeviceSurfaceSupportKHR(device, i, surface, supported),
                    "vkGetPhysicalDeviceSurfaceSupportKHR");
            present[i] = families.get(i).queueCount() > 0 && supported.get(0) != 0;
        }
        return QueueFamilies.choose(graphics, present);
    }

    private static boolean hasSwapchain(VkPhysicalDevice device, MemoryStack stack) {
        IntBuffer count = stack.callocInt(1);
        requireSuccess(vkEnumerateDeviceExtensionProperties(device, (java.nio.ByteBuffer) null, count, null),
                "vkEnumerateDeviceExtensionProperties count");
        if (count.get(0) == 0) {
            return false;
        }
        try (VkExtensionProperties.Buffer extensions = VkExtensionProperties.malloc(count.get(0))) {
            requireSuccess(vkEnumerateDeviceExtensionProperties(device, (java.nio.ByteBuffer) null, count,
                    extensions), "vkEnumerateDeviceExtensionProperties");
            for (int i = 0; i < count.get(0); i++) {
                if (VK_KHR_SWAPCHAIN_EXTENSION_NAME.equals(extensions.get(i).extensionNameString())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<String> surfaceFormats(VkPhysicalDevice device, long surface, MemoryStack stack) {
        IntBuffer count = stack.callocInt(1);
        requireSuccess(vkGetPhysicalDeviceSurfaceFormatsKHR(device, surface, count, null),
                "vkGetPhysicalDeviceSurfaceFormatsKHR count");
        if (count.get(0) == 0) {
            return List.of();
        }
        VkSurfaceFormatKHR.Buffer values = VkSurfaceFormatKHR.malloc(count.get(0), stack);
        requireSuccess(vkGetPhysicalDeviceSurfaceFormatsKHR(device, surface, count, values),
                "vkGetPhysicalDeviceSurfaceFormatsKHR");
        List<String> formats = new ArrayList<>(count.get(0));
        for (int i = 0; i < count.get(0); i++) {
            formats.add(values.get(i).format() + "/" + values.get(i).colorSpace());
        }
        return List.copyOf(formats);
    }

    private static List<Integer> presentModes(VkPhysicalDevice device, long surface, MemoryStack stack) {
        IntBuffer count = stack.callocInt(1);
        requireSuccess(vkGetPhysicalDeviceSurfacePresentModesKHR(device, surface, count, null),
                "vkGetPhysicalDeviceSurfacePresentModesKHR count");
        if (count.get(0) == 0) {
            return List.of();
        }
        IntBuffer values = stack.mallocInt(count.get(0));
        requireSuccess(vkGetPhysicalDeviceSurfacePresentModesKHR(device, surface, count, values),
                "vkGetPhysicalDeviceSurfacePresentModesKHR");
        List<Integer> modes = new ArrayList<>(count.get(0));
        for (int i = 0; i < count.get(0); i++) {
            modes.add(values.get(i));
        }
        return List.copyOf(modes);
    }

    private static void requireSuccess(int result, String operation) {
        if (result != VK_SUCCESS) {
            throw new IllegalStateException(operation + " failed with VkResult " + result);
        }
    }
}
