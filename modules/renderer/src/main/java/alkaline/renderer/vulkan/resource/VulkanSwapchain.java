package alkaline.renderer.vulkan.resource;

import alkaline.renderer.vulkan.SwapchainSelection;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSurfaceCapabilitiesKHR;
import org.lwjgl.vulkan.VkSurfaceFormatKHR;

import static org.lwjgl.vulkan.KHRSurface.VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
import static org.lwjgl.vulkan.KHRSurface.VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
import static org.lwjgl.vulkan.KHRSurface.VK_PRESENT_MODE_FIFO_KHR;
import static org.lwjgl.vulkan.KHRSurface.VK_PRESENT_MODE_MAILBOX_KHR;
import static org.lwjgl.vulkan.KHRSurface.vkGetPhysicalDeviceSurfaceCapabilitiesKHR;
import static org.lwjgl.vulkan.KHRSurface.vkGetPhysicalDeviceSurfaceFormatsKHR;
import static org.lwjgl.vulkan.KHRSurface.vkGetPhysicalDeviceSurfacePresentModesKHR;
import static org.lwjgl.vulkan.KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRSwapchain.VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR;
import static org.lwjgl.vulkan.KHRSwapchain.vkCreateSwapchainKHR;
import static org.lwjgl.vulkan.KHRSwapchain.vkDestroySwapchainKHR;
import static org.lwjgl.vulkan.KHRSwapchain.vkGetSwapchainImagesKHR;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8A8_SRGB;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHARING_MODE_CONCURRENT;
import static org.lwjgl.vulkan.VK10.VK_SHARING_MODE_EXCLUSIVE;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.vkCreateImageView;
import static org.lwjgl.vulkan.VK10.vkCreateSemaphore;
import static org.lwjgl.vulkan.VK10.vkDestroyImageView;
import static org.lwjgl.vulkan.VK10.vkDestroySemaphore;

/** Owns swapchain images, views, presentation semaphores, and surface-dependent configuration. */
final class VulkanSwapchain implements AutoCloseable {
    private final VkPhysicalDevice physicalDevice;
    private final VkDevice device;
    private final long surface;
    private final int graphicsFamily;
    private final int presentFamily;
    private final int[] presentPreference;
    private int selectedPresentMode;
    private long handle;
    private long[] images = new long[0];
    private long[] views = new long[0];
    /** UNORM views of the same images when the format is sRGB and mutable formats are enabled; else empty. */
    private long[] gammaViews = new long[0];
    private final boolean mutableFormat;
    private long[] renderFinishedSemaphores = new long[0];
    private int format;
    private boolean capturable;
    private int width;
    private int height;

    private final boolean presentWait;

    VulkanSwapchain(VkPhysicalDevice physicalDevice, VkDevice device, long surface,
                    int graphicsFamily, int presentFamily, PresentMode presentMode, boolean mutableFormat,
                    boolean presentWait) {
        this.presentWait = presentWait;
        this.presentPreference = presentMode.preference();
        this.mutableFormat = mutableFormat;
        this.physicalDevice = Objects.requireNonNull(physicalDevice, "physicalDevice");
        this.device = Objects.requireNonNull(device, "device");
        this.surface = surface;
        this.graphicsFamily = graphicsFamily;
        this.presentFamily = presentFamily;
    }

    void create(int framebufferWidth, int framebufferHeight) {
        if (handle != 0) {
            throw new IllegalStateException("Swapchain already exists.");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSurfaceCapabilitiesKHR capabilities = VkSurfaceCapabilitiesKHR.malloc(stack);
            requireSuccess(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physicalDevice, surface, capabilities),
                    "vkGetPhysicalDeviceSurfaceCapabilitiesKHR");
            IntBuffer count = stack.callocInt(1);
            requireSuccess(vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, surface, count, null),
                    "vkGetPhysicalDeviceSurfaceFormatsKHR count");
            VkSurfaceFormatKHR.Buffer formats = VkSurfaceFormatKHR.malloc(count.get(0), stack);
            requireSuccess(vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, surface, count, formats),
                    "vkGetPhysicalDeviceSurfaceFormatsKHR");
            List<SwapchainSelection.SurfaceFormat> availableFormats = new ArrayList<>(count.get(0));
            for (int index = 0; index < count.get(0); index++) {
                availableFormats.add(new SwapchainSelection.SurfaceFormat(
                        formats.get(index).format(), formats.get(index).colorSpace()));
            }
            SwapchainSelection.SurfaceFormat preferred = new SwapchainSelection.SurfaceFormat(
                    VK_FORMAT_B8G8R8A8_SRGB, VK_COLOR_SPACE_SRGB_NONLINEAR_KHR);
            SwapchainSelection.SurfaceFormat selected = availableFormats.size() == 1
                    && availableFormats.getFirst().format() == org.lwjgl.vulkan.VK10.VK_FORMAT_UNDEFINED
                    ? preferred : SwapchainSelection.chooseSurfaceFormat(availableFormats, preferred);
            requireSuccess(vkGetPhysicalDeviceSurfacePresentModesKHR(physicalDevice, surface, count, null),
                    "vkGetPhysicalDeviceSurfacePresentModesKHR count");
            IntBuffer modes = stack.mallocInt(count.get(0));
            requireSuccess(vkGetPhysicalDeviceSurfacePresentModesKHR(physicalDevice, surface, count, modes),
                    "vkGetPhysicalDeviceSurfacePresentModesKHR");
            int[] modeValues = new int[count.get(0)];
            for (int index = 0; index < count.get(0); index++) {
                modeValues[index] = modes.get(index);
            }
            int presentMode = SwapchainSelection.choosePresentMode(modeValues, presentPreference,
                    VK_PRESENT_MODE_FIFO_KHR);
            selectedPresentMode = presentMode;
            if (framebufferWidth <= 0 || framebufferHeight <= 0) {
                throw new IllegalArgumentException("The SDL window has no drawable pixel area.");
            }
            SwapchainSelection.Extent extent = SwapchainSelection.chooseExtent(
                    capabilities.currentExtent().width(), capabilities.currentExtent().height(),
                    capabilities.minImageExtent().width(), capabilities.minImageExtent().height(),
                    capabilities.maxImageExtent().width(), capabilities.maxImageExtent().height(),
                    framebufferWidth, framebufferHeight);
            // Mailbox needs room to replace frames while one is on screen and one waits for the flip: with three
            // images a fullscreen (directly scanned out) Wayland window fell back to FIFO pacing (decision 0010).
            int imageCount = SwapchainSelection.chooseImageCount(capabilities.minImageCount(), capabilities.maxImageCount(),
                    presentMode == org.lwjgl.vulkan.KHRSurface.VK_PRESENT_MODE_MAILBOX_KHR ? 4 : 0);
            int compositeAlpha = (capabilities.supportedCompositeAlpha() & VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR) != 0
                    ? VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR
                    : Integer.lowestOneBit(capabilities.supportedCompositeAlpha());
            // Screenshots copy out of the presented image when the surface allows transfer reads.
            int captureUsage = capabilities.supportedUsageFlags() & org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
            capturable = captureUsage != 0;
            var create = org.lwjgl.vulkan.VkSwapchainCreateInfoKHR.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR)
                    .surface(surface).minImageCount(imageCount)
                    .imageFormat(selected.format()).imageColorSpace(selected.colorSpace())
                    .imageExtent(image -> image.set(extent.width(), extent.height()))
                    .imageArrayLayers(1).imageUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | captureUsage)
                    .preTransform(capabilities.currentTransform()).compositeAlpha(compositeAlpha)
                    .presentMode(presentMode).clipped(true);
            if (presentWait) {
                create.flags(create.flags() | org.lwjgl.vulkan.KHRPresentId2.VK_SWAPCHAIN_CREATE_PRESENT_ID_2_BIT_KHR
                        | org.lwjgl.vulkan.KHRPresentWait2.VK_SWAPCHAIN_CREATE_PRESENT_WAIT_2_BIT_KHR);
            }
            int alias = mutableFormat ? GammaSpace.unormAlias(selected.format()) : 0;
            if (alias != 0) {
                create.flags(create.flags() | org.lwjgl.vulkan.KHRSwapchainMutableFormat.VK_SWAPCHAIN_CREATE_MUTABLE_FORMAT_BIT_KHR)
                        .pNext(org.lwjgl.vulkan.VkImageFormatListCreateInfo.calloc(stack)
                                .sType(org.lwjgl.vulkan.VK12.VK_STRUCTURE_TYPE_IMAGE_FORMAT_LIST_CREATE_INFO)
                                .pViewFormats(stack.ints(selected.format(), alias)));
            }
            if (graphicsFamily != presentFamily) {
                create.imageSharingMode(VK_SHARING_MODE_CONCURRENT)
                        .pQueueFamilyIndices(stack.ints(graphicsFamily, presentFamily));
            } else {
                create.imageSharingMode(VK_SHARING_MODE_EXCLUSIVE);
            }
            LongBuffer address = stack.mallocLong(1);
            requireSuccess(vkCreateSwapchainKHR(device, create, null, address), "vkCreateSwapchainKHR");
            handle = address.get(0);
            format = selected.format();
            width = extent.width();
            height = extent.height();
            requireSuccess(vkGetSwapchainImagesKHR(device, handle, count, null), "vkGetSwapchainImagesKHR count");
            LongBuffer imageHandles = stack.mallocLong(count.get(0));
            requireSuccess(vkGetSwapchainImagesKHR(device, handle, count, imageHandles), "vkGetSwapchainImagesKHR");
            images = new long[count.get(0)];
            views = new long[count.get(0)];
            gammaViews = new long[alias != 0 ? count.get(0) : 0];
            renderFinishedSemaphores = new long[count.get(0)];
            for (int index = 0; index < count.get(0); index++) {
                images[index] = imageHandles.get(index);
                views[index] = createView(images[index], format, stack);
                if (alias != 0) {
                    gammaViews[index] = createView(images[index], alias, stack);
                }
                VkSemaphoreCreateInfo semaphoreInfo = VkSemaphoreCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO);
                LongBuffer semaphoreAddress = stack.mallocLong(1);
                requireSuccess(vkCreateSemaphore(device, semaphoreInfo, null, semaphoreAddress),
                        "vkCreateSemaphore present");
                renderFinishedSemaphores[index] = semaphoreAddress.get(0);
            }
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    /** The present mode the surface gave this swapchain, which may be a fallback of the requested one. */
    PresentMode presentMode() { return PresentMode.of(selectedPresentMode); }

    long handle() { return handle; }
    int format() { return format; }
    boolean capturable() { return capturable; }
    int width() { return width; }
    int height() { return height; }
    int imageCount() { return images.length; }
    long image(int index) { return images[index]; }
    long imageView(int index) { return views[index]; }

    /** The UNORM alias view of an image, or 0 when this swapchain has none. */
    long gammaView(int index) { return gammaViews.length == 0 ? 0 : gammaViews[index]; }

    private long createView(long image, int viewFormat, MemoryStack stack) {
        VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO).image(image)
                .viewType(org.lwjgl.vulkan.VK10.VK_IMAGE_VIEW_TYPE_2D).format(viewFormat)
                .components(components -> components.r(org.lwjgl.vulkan.VK10.VK_COMPONENT_SWIZZLE_IDENTITY)
                        .g(org.lwjgl.vulkan.VK10.VK_COMPONENT_SWIZZLE_IDENTITY)
                        .b(org.lwjgl.vulkan.VK10.VK_COMPONENT_SWIZZLE_IDENTITY)
                        .a(org.lwjgl.vulkan.VK10.VK_COMPONENT_SWIZZLE_IDENTITY))
                .subresourceRange(range -> range.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1));
        LongBuffer viewAddress = stack.mallocLong(1);
        requireSuccess(vkCreateImageView(device, viewInfo, null, viewAddress), "vkCreateImageView");
        return viewAddress.get(0);
    }
    long renderFinishedSemaphore(int index) { return renderFinishedSemaphores[index]; }

    @Override
    public void close() {
        for (long view : views) {
            if (view != 0) {
                vkDestroyImageView(device, view, null);
            }
        }
        for (long view : gammaViews) {
            if (view != 0) {
                vkDestroyImageView(device, view, null);
            }
        }
        for (long semaphore : renderFinishedSemaphores) {
            if (semaphore != 0) {
                vkDestroySemaphore(device, semaphore, null);
            }
        }
        if (handle != 0) {
            vkDestroySwapchainKHR(device, handle, null);
        }
        handle = 0;
        images = new long[0];
        views = new long[0];
        gammaViews = new long[0];
        renderFinishedSemaphores = new long[0];
        format = 0;
        width = 0;
        height = 0;
    }

    private static void requireSuccess(int result, String operation) {
        if (result != VK_SUCCESS) {
            throw new IllegalStateException(operation + " failed with VkResult " + result);
        }
    }
}
