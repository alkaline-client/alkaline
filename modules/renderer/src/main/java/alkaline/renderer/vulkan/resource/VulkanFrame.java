package alkaline.renderer.vulkan.resource;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.Objects;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderingAttachmentInfo;
import org.lwjgl.vulkan.VkRenderingInfo;
import org.lwjgl.vulkan.VkViewport;

import static org.lwjgl.system.MemoryUtil.memAllocLong;
import static org.lwjgl.system.MemoryUtil.memFree;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_SHADER_READ_BIT;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_LOAD_OP_CLEAR;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_LOAD_OP_DONT_CARE;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_LOAD_OP_LOAD;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_STORE_OP_STORE;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_INDEX_TYPE_UINT32;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_BIND_POINT_GRAPHICS;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO;
import static org.lwjgl.vulkan.VK10.vkCmdBindDescriptorSets;
import static org.lwjgl.vulkan.VK10.vkCmdBindIndexBuffer;
import static org.lwjgl.vulkan.VK10.vkCmdBindPipeline;
import static org.lwjgl.vulkan.VK10.vkCmdBindVertexBuffers;
import static org.lwjgl.vulkan.VK10.vkCmdDraw;
import static org.lwjgl.vulkan.VK10.vkCmdDrawIndexed;
import static org.lwjgl.vulkan.VK10.vkCmdPushConstants;
import static org.lwjgl.vulkan.VK10.vkCmdSetScissor;
import static org.lwjgl.vulkan.VK10.vkCmdSetViewport;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_RENDERING_INFO;
import static org.lwjgl.vulkan.VK13.vkCmdBeginRendering;
import static org.lwjgl.vulkan.VK13.vkCmdEndRendering;

/**
 * Command encoder for one acquired frame. Obtain it from {@link VulkanRenderer#acquireFrame()}; it is valid until
 * {@link VulkanRenderer#endFrame()}. Offscreen passes ({@link #beginRendering}) must be recorded before
 * {@link #beginMainPass}. All calls are confined to the renderer's owner thread and allocate no Java objects.
 */
public final class VulkanFrame {
    private enum Pass { NONE, MAIN, TARGET }

    private static final int MAX_COPY_MIPS = 16;

    private final VulkanRenderer renderer;
    private final VulkanStreamBuffer stream;
    private final LongBuffer singleHandle;
    private final LongBuffer singleOffset;
    private final VkViewport.Buffer viewport;
    private final VkRect2D.Buffer scissor;
    private final VkRenderingAttachmentInfo.Buffer targetAttachment;
    private final VkRenderingAttachmentInfo targetDepthAttachment;
    private final VkRenderingInfo targetRendering;
    private final org.lwjgl.vulkan.VkBufferCopy.Buffer bufferCopy;
    private final org.lwjgl.vulkan.VkMemoryBarrier.Buffer transferBarrier;
    /** Orders a suspended pass's attachment writes before the resumed pass loads them. */
    private final org.lwjgl.vulkan.VkMemoryBarrier.Buffer attachmentBarrier;
    private final org.lwjgl.vulkan.VkImageCopy.Buffer layerCopies;
    private final org.lwjgl.vulkan.VkImageMemoryBarrier.Buffer layerBarrier;
    private boolean transfersPending;

    private VkCommandBuffer command;
    private int index;
    private int width;
    private int height;
    private boolean open;
    private boolean mainPassRecorded;
    private Pass pass = Pass.NONE;
    /** True between {@link #beginGammaSpace} and {@link #endGammaSpace} while the pass runs on an alias view. */
    private boolean gammaSpace;
    private boolean gammaSwitched;
    private VulkanRenderTarget target;
    private VulkanPipeline pipeline;

    VulkanFrame(VulkanRenderer renderer, VulkanStreamBuffer stream) {
        this.renderer = renderer;
        this.stream = stream;
        LongBuffer handle = null;
        LongBuffer offset = null;
        VkViewport.Buffer createdViewport = null;
        VkRect2D.Buffer createdScissor = null;
        VkRenderingAttachmentInfo.Buffer attachment = null;
        try {
            handle = memAllocLong(1);
            offset = memAllocLong(1);
            createdViewport = VkViewport.calloc(1);
            createdScissor = VkRect2D.calloc(1);
            attachment = VkRenderingAttachmentInfo.calloc(1);
            targetDepthAttachment = VkRenderingAttachmentInfo.calloc();
            targetRendering = VkRenderingInfo.calloc();
            attachmentBarrier = org.lwjgl.vulkan.VkMemoryBarrier.calloc(1)
                    .sType(org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                    .srcAccessMask(org.lwjgl.vulkan.VK10.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT
                            | org.lwjgl.vulkan.VK10.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT)
                    .dstAccessMask(org.lwjgl.vulkan.VK10.VK_ACCESS_COLOR_ATTACHMENT_READ_BIT
                            | org.lwjgl.vulkan.VK10.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT
                            | org.lwjgl.vulkan.VK10.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT
                            | org.lwjgl.vulkan.VK10.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);
            bufferCopy = org.lwjgl.vulkan.VkBufferCopy.calloc(1);
            layerCopies = org.lwjgl.vulkan.VkImageCopy.calloc(MAX_COPY_MIPS);
            layerBarrier = org.lwjgl.vulkan.VkImageMemoryBarrier.calloc(1)
                    .sType(org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                    .srcQueueFamilyIndex(org.lwjgl.vulkan.VK10.VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(org.lwjgl.vulkan.VK10.VK_QUEUE_FAMILY_IGNORED);
            transferBarrier = org.lwjgl.vulkan.VkMemoryBarrier.calloc(1)
                    .sType(org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                    .srcAccessMask(org.lwjgl.vulkan.VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(org.lwjgl.vulkan.VK10.VK_ACCESS_VERTEX_ATTRIBUTE_READ_BIT
                            | org.lwjgl.vulkan.VK10.VK_ACCESS_INDEX_READ_BIT
                            | org.lwjgl.vulkan.VK10.VK_ACCESS_SHADER_READ_BIT);
        } catch (RuntimeException | Error failure) {
            if (handle != null) memFree(handle);
            if (offset != null) memFree(offset);
            if (createdViewport != null) createdViewport.free();
            if (createdScissor != null) createdScissor.free();
            if (attachment != null) attachment.free();
            throw failure;
        }
        singleHandle = handle;
        singleOffset = offset;
        viewport = createdViewport;
        scissor = createdScissor;
        targetAttachment = attachment;
    }

    /** Frame-in-flight slot; per-slot resources may be reused once this slot's previous submission completed. */
    public int index() {
        requireOpen();
        return index;
    }

    /** Swapchain width in pixels. */
    public int width() {
        requireOpen();
        return width;
    }

    /** Swapchain height in pixels. */
    public int height() {
        requireOpen();
        return height;
    }

    public boolean inMainPass() {
        return open && pass == Pass.MAIN;
    }

    // ---- streaming memory ---------------------------------------------------------------------------------

    /** Reserves frame-local bytes; returns an absolute offset into {@link #stream()}, or -1 when exhausted. */
    public long allocate(long bytes, int alignment) {
        requireOpen();
        return stream.allocate(bytes, alignment);
    }

    /** Native-order, persistently mapped view of the stream buffer. Write only at offsets from {@link #allocate}. */
    public ByteBuffer stream() {
        requireOpen();
        return stream.bytes();
    }

    /**
     * The stream as native-order ints (index = byte offset / 4), cached so per-frame bulk writes create no view
     * objects. Write only; the memory may be write-combined.
     */
    public java.nio.IntBuffer streamInts() {
        requireOpen();
        if (streamInts == null) {
            streamInts = stream.bytes().asIntBuffer();
        }
        return streamInts;
    }

    private java.nio.IntBuffer streamInts;

    /**
     * The CPU address of a stream offset from {@link #allocate}, for hot writers that store with
     * {@code MemoryUtil.memPut*} instead of checked buffer calls. Write only, within the allocation.
     */
    public long streamPointer(long offset) {
        requireOpen();
        return org.lwjgl.system.MemoryUtil.memAddress(stream.bytes(), 0) + offset;
    }

    /** GPU virtual address of a stream offset, for shaders using buffer references. */
    public long streamAddress(long offset) {
        requireOpen();
        return stream.deviceAddress(offset);
    }

    /** Bytes available to each frame slot. */
    public long streamCapacity() {
        return stream.regionBytes();
    }

    // ---- transfers ----------------------------------------------------------------------------------------

    /** Replaces a dynamic texture's contents; only valid while no pass is recording. */
    public void upload(VulkanDynamicTexture texture, ByteBuffer bgra) {
        upload(texture, bgra, null);
    }

    /**
     * Replaces only the given row bands ({@code start, end} pairs) of a dynamic texture, or all of it for null;
     * only valid while no pass is recording.
     */
    public void upload(VulkanDynamicTexture texture, ByteBuffer bgra, int[] rowBands) {
        requireOpen();
        Objects.requireNonNull(texture, "texture");
        Objects.requireNonNull(bgra, "bgra");
        if (pass != Pass.NONE) {
            throw new IllegalStateException("Texture updates must be recorded outside rendering passes.");
        }
        requireOwned(texture.belongsTo(renderer));
        texture.record(command, index, bgra, rowBands);
        renderer.track(texture, index);
    }

    /**
     * Copies bytes written to this frame's stream region into a device buffer. Only valid outside passes; the
     * next pass waits for all such copies before reading vertices, indices or shader data. No staging
     * allocation, no stall.
     */
    public void copyFromStream(long streamOffset, VulkanStaticBuffer destination, long destinationOffset, long bytes) {
        requireOpen();
        Objects.requireNonNull(destination, "destination");
        if (pass != Pass.NONE) {
            throw new IllegalStateException("Buffer copies must be recorded outside rendering passes.");
        }
        requireOwned(destination.belongsTo(renderer));
        if (bytes <= 0 || streamOffset < 0 || destinationOffset < 0 || destinationOffset + bytes > destination.size()) {
            throw new IllegalArgumentException("Buffer copy range lies outside the destination.");
        }
        bufferCopy.get(0).srcOffset(streamOffset).dstOffset(destinationOffset).size(bytes);
        org.lwjgl.vulkan.VK10.vkCmdCopyBuffer(command, stream.handle(), destination.handle(), bufferCopy);
        renderer.track(destination, index);
        transfersPending = true;
    }

    /**
     * Replaces one layer of a texture array with another layer of the same texture, every mip level, for
     * animated textures: the next frame's art is stored in spare layers and copied over the layer meshes
     * reference. Only valid outside passes. Earlier sampling of the texture (also by frames still in flight)
     * completes before the copy, and later fragment shaders see the new contents.
     */
    public void copyTextureLayer(VulkanTexture texture, int fromLayer, int toLayer) {
        requireOpen();
        Objects.requireNonNull(texture, "texture");
        if (pass != Pass.NONE) {
            throw new IllegalStateException("Texture copies must be recorded outside rendering passes.");
        }
        requireOwned(texture.belongsTo(renderer));
        VulkanImage image = texture.image();
        if (fromLayer == toLayer || fromLayer < 0 || toLayer < 0 || fromLayer >= image.layers() || toLayer >= image.layers()) {
            throw new IllegalArgumentException("Layer copy needs two different layers of the texture.");
        }
        int levels = Math.min(image.mipLevels(), MAX_COPY_MIPS);
        // Same image as source and destination: GENERAL serves both roles for the copy.
        layerTransition(image, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL,
                VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_ACCESS_SHADER_READ_BIT, org.lwjgl.vulkan.VK10.VK_ACCESS_TRANSFER_READ_BIT
                        | org.lwjgl.vulkan.VK10.VK_ACCESS_TRANSFER_WRITE_BIT);
        layerCopies.limit(levels);
        int width = texture.width();
        int height = texture.height();
        for (int level = 0; level < levels; level++) {
            int levelWidth = Math.max(1, width >> level);
            int levelHeight = Math.max(1, height >> level);
            org.lwjgl.vulkan.VkImageCopy region = layerCopies.get(level);
            region.srcSubresource().aspectMask(image.aspect()).mipLevel(level).baseArrayLayer(fromLayer).layerCount(1);
            region.dstSubresource().aspectMask(image.aspect()).mipLevel(level).baseArrayLayer(toLayer).layerCount(1);
            region.srcOffset().set(0, 0, 0);
            region.dstOffset().set(0, 0, 0);
            region.extent().set(levelWidth, levelHeight, 1);
        }
        org.lwjgl.vulkan.VK10.vkCmdCopyImage(command, image.image(), org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL,
                image.image(), org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL, layerCopies);
        layerCopies.limit(MAX_COPY_MIPS);
        layerTransition(image, org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                org.lwjgl.vulkan.VK10.VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT);
        renderer.track(texture, index);
    }

    private void layerTransition(VulkanImage image, int oldLayout, int newLayout, int sourceStage, int destinationStage,
                                 int sourceAccess, int destinationAccess) {
        layerBarrier.srcAccessMask(sourceAccess).dstAccessMask(destinationAccess)
                .oldLayout(oldLayout).newLayout(newLayout).image(image.image());
        StructWrites.subresourceRange(layerBarrier.address() + org.lwjgl.vulkan.VkImageMemoryBarrier.SUBRESOURCERANGE,
                image.aspect(), 0, image.mipLevels(), 0, image.layers());
        org.lwjgl.vulkan.VK10.vkCmdPipelineBarrier(command, sourceStage, destinationStage, 0, null, null, layerBarrier);
    }

    private void flushTransfers() {
        if (transfersPending) {
            org.lwjgl.vulkan.VK10.vkCmdPipelineBarrier(command, org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_VERTEX_INPUT_BIT
                            | org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_VERTEX_SHADER_BIT
                            | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, transferBarrier, null, null);
            transfersPending = false;
        }
    }

    // ---- passes -------------------------------------------------------------------------------------------

    /** Starts an offscreen pass; the target is shader-readable again after {@link #endRendering()}. */
    public void beginRendering(VulkanRenderTarget renderTarget, boolean clear, float red, float green, float blue,
                               float alpha) {
        requireOpen();
        Objects.requireNonNull(renderTarget, "renderTarget");
        if (pass != Pass.NONE) {
            throw new IllegalStateException("End the current pass before beginning another.");
        }
        if (mainPassRecorded) {
            throw new IllegalStateException("Offscreen passes must be recorded before the main pass.");
        }
        requireOwned(renderTarget.belongsTo(renderer));
        flushTransfers();
        VulkanImage image = renderTarget.image();
        int oldLayout = renderTarget.layout();
        image.transition(command, oldLayout == VK_IMAGE_LAYOUT_UNDEFINED ? VK_IMAGE_LAYOUT_UNDEFINED : oldLayout,
                VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                oldLayout == VK_IMAGE_LAYOUT_UNDEFINED ? VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT
                        : VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                oldLayout == VK_IMAGE_LAYOUT_UNDEFINED ? 0 : VK_ACCESS_SHADER_READ_BIT,
                VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
        renderTarget.layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        targetAttachment.get(0)
                .sType(VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO)
                .imageView(image.view())
                .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                .loadOp(clear ? VK_ATTACHMENT_LOAD_OP_CLEAR
                        : oldLayout == VK_IMAGE_LAYOUT_UNDEFINED ? VK_ATTACHMENT_LOAD_OP_DONT_CARE
                        : VK_ATTACHMENT_LOAD_OP_LOAD)
                .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
        targetAttachment.get(0).clearValue().color().float32(0, red).float32(1, green).float32(2, blue)
                .float32(3, alpha);
        int targetWidth = renderTarget.width();
        int targetHeight = renderTarget.height();
        VkRenderingAttachmentInfo depthAttachment = null;
        if (renderTarget.hasDepth()) {
            VulkanImage depth = renderTarget.depthImage();
            // Depth is cleared every pass, so the previous contents are discarded; wait only for prior writes.
            VulkanImage.barrier(command, depth.image(), renderer.depthAspect(), 1,
                    VK_IMAGE_LAYOUT_UNDEFINED, org.lwjgl.vulkan.VK13.VK_IMAGE_LAYOUT_DEPTH_ATTACHMENT_OPTIMAL,
                    org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT
                            | org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT,
                    org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT
                            | org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT,
                    org.lwjgl.vulkan.VK10.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
                    org.lwjgl.vulkan.VK10.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT
                            | org.lwjgl.vulkan.VK10.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);
            targetDepthAttachment.sType(VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO)
                    .imageView(depth.view())
                    .imageLayout(org.lwjgl.vulkan.VK13.VK_IMAGE_LAYOUT_DEPTH_ATTACHMENT_OPTIMAL)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                    // Stored so the pass can be suspended and resumed (gamma-space segments).
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            targetDepthAttachment.clearValue().depthStencil().depth(1.0f).stencil(0);
            depthAttachment = targetDepthAttachment;
        }
        targetRendering.sType(VK_STRUCTURE_TYPE_RENDERING_INFO)
                .layerCount(1)
                .pColorAttachments(targetAttachment)
                .pDepthAttachment(depthAttachment);
        StructWrites.rect(targetRendering.address() + VkRenderingInfo.RENDERAREA, 0, 0, targetWidth, targetHeight);
        vkCmdBeginRendering(command, targetRendering);
        pass = Pass.TARGET;
        target = renderTarget;
        pipeline = null;
        setViewport(0, 0, targetWidth, targetHeight, false);
        setScissor(0, 0, targetWidth, targetHeight);
        renderer.track(renderTarget, index);
    }

    public void endRendering() {
        requireOpen();
        if (pass != Pass.TARGET) {
            throw new IllegalStateException("No offscreen pass is recording.");
        }
        if (gammaSpace) {
            throw new IllegalStateException("End the gamma-space segment before the pass.");
        }
        vkCmdEndRendering(command);
        target.image().transition(command, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                VK_ACCESS_SHADER_READ_BIT);
        target.layout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        target = null;
        pipeline = null;
        pass = Pass.NONE;
    }

    /**
     * Clears the current pass's depth to 1 (vanilla clears depth before drawing the first-person hand). The
     * target must have depth.
     */
    public void clearDepth() {
        requirePass();
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            org.lwjgl.vulkan.VkClearAttachment.Buffer attachment = org.lwjgl.vulkan.VkClearAttachment.calloc(1, stack)
                    .aspectMask(renderer.depthAspect());
            attachment.get(0).clearValue().depthStencil().depth(1.0f).stencil(0);
            int clearWidth = pass == Pass.MAIN ? width : target.width();
            int clearHeight = pass == Pass.MAIN ? height : target.height();
            org.lwjgl.vulkan.VkClearRect.Buffer rect = org.lwjgl.vulkan.VkClearRect.calloc(1, stack)
                    .baseArrayLayer(0).layerCount(1);
            rect.get(0).rect().offset().set(0, 0);
            rect.get(0).rect().extent().set(clearWidth, clearHeight);
            org.lwjgl.vulkan.VK10.vkCmdClearAttachments(command, attachment, rect);
        }
    }

    /** Begins the swapchain pass with a cleared color and depth attachment. */
    public void beginMainPass(float red, float green, float blue, float alpha) {
        requireOpen();
        if (pass != Pass.NONE || mainPassRecorded) {
            throw new IllegalStateException("The main pass can begin once, outside other passes.");
        }
        flushTransfers();
        renderer.beginMainRendering(command, red, green, blue, alpha);
        pass = Pass.MAIN;
        mainPassRecorded = true;
        pipeline = null;
        setViewport(0, 0, width, height, false);
        setScissor(0, 0, width, height);
    }

    /**
     * Continues the current pass with the color attachment's UNORM alias, for pipelines built with
     * {@link PipelineSpec.Builder#gammaSpace()}: blending then runs on sRGB-encoded values, as in vanilla's
     * framebuffer. Color and depth are kept. Returns false, changing nothing, when the attachment is sRGB without
     * an alias (the device lacks mutable swapchain formats); attachments that are already UNORM need no switch.
     */
    public boolean beginGammaSpace() {
        requirePass();
        if (gammaSpace) {
            throw new IllegalStateException("Already in a gamma-space segment.");
        }
        int format = pass == Pass.MAIN ? renderer.mainColorFormat() : target.format().vulkanFormat();
        if (GammaSpace.unormAlias(format) == 0) {
            gammaSpace = true;
            gammaSwitched = false;
            return true;
        }
        long alias = pass == Pass.MAIN ? renderer.mainGammaView() : target.image().aliasView();
        if (alias == 0) {
            return false;
        }
        resume(alias);
        gammaSpace = true;
        gammaSwitched = true;
        return true;
    }

    /** Returns to the attachment's own view after {@link #beginGammaSpace}. */
    public void endGammaSpace() {
        requirePass();
        if (!gammaSpace) {
            throw new IllegalStateException("No gamma-space segment is open.");
        }
        if (gammaSwitched) {
            resume(pass == Pass.MAIN ? renderer.mainColorView() : target.image().view());
        }
        gammaSpace = false;
        gammaSwitched = false;
    }

    /** Ends the render pass instance and begins another on the same attachments, loading both. */
    private void resume(long colorView) {
        vkCmdEndRendering(command);
        org.lwjgl.vulkan.VK10.vkCmdPipelineBarrier(command,
                org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT
                        | org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT,
                org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT
                        | org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT
                        | org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT,
                0, attachmentBarrier, null, null);
        if (pass == Pass.MAIN) {
            renderer.resumeMainRendering(command, colorView);
        } else {
            targetAttachment.get(0).imageView(colorView).loadOp(VK_ATTACHMENT_LOAD_OP_LOAD);
            if (target.hasDepth()) {
                targetDepthAttachment.loadOp(VK_ATTACHMENT_LOAD_OP_LOAD);
            }
            vkCmdBeginRendering(command, targetRendering);
        }
    }

    // ---- state --------------------------------------------------------------------------------------------

    /**
     * Sets the viewport. With flipY the viewport uses a negative height so clip-space +Y points up and
     * counter-clockwise triangles stay front-facing, matching OpenGL-style projection matrices.
     */
    public void setViewport(float x, float y, float viewportWidth, float viewportHeight, boolean flipY) {
        requirePass();
        // The buffer's own setters write element 0 without creating a struct wrapper per call.
        if (flipY) {
            viewport.x(x).y(y + viewportHeight).width(viewportWidth).height(-viewportHeight);
        } else {
            viewport.x(x).y(y).width(viewportWidth).height(viewportHeight);
        }
        viewport.minDepth(0f).maxDepth(1f);
        vkCmdSetViewport(command, 0, viewport);
    }

    public void setScissor(int x, int y, int scissorWidth, int scissorHeight) {
        requirePass();
        StructWrites.rect(scissor.address(), Math.max(0, x), Math.max(0, y), Math.max(0, scissorWidth),
                Math.max(0, scissorHeight));
        vkCmdSetScissor(command, 0, scissor);
    }

    public void bindPipeline(VulkanPipeline value) {
        requirePass();
        Objects.requireNonNull(value, "pipeline");
        requireOwned(value.belongsTo(renderer));
        boolean main = value.targetsMainPass();
        if (main != (pass == Pass.MAIN)
                || (!main && (value.spec().offscreenFormat() != target.format()
                        || value.spec().offscreenDepth() != target.hasDepth()))) {
            throw new IllegalArgumentException("Pipeline attachment formats do not match the current pass.");
        }
        vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_GRAPHICS, value.handle());
        pipeline = value;
    }

    /** Binds an image at set 0 for the bound pipeline. */
    public void bindImage(VulkanSampledImage image) {
        bindImage(0, image);
    }

    /** Binds an image to descriptor set {@code set} of a pipeline that declares that many sampled images. */
    public void bindImage(int set, VulkanSampledImage image) {
        requirePipeline();
        Objects.requireNonNull(image, "image");
        requireOwned(image.belongsTo(renderer));
        if (set < 0 || set >= pipeline.spec().sampledImages()) {
            throw new IllegalStateException("The bound pipeline declares no sampled image at set " + set + ".");
        }
        if (image == target) {
            throw new IllegalArgumentException("A render target cannot be sampled while it is being rendered.");
        }
        singleHandle.put(0, image.descriptorSet());
        vkCmdBindDescriptorSets(command, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.layout(), set, singleHandle, null);
        renderer.track(image, index);
    }

    /** Pushes the remaining bytes of a native-order buffer at offset zero. */
    public void pushConstants(ByteBuffer data) {
        pushConstants(0, data);
    }

    /** Pushes the remaining bytes at a byte offset, leaving the rest of the range as last pushed. */
    public void pushConstants(int offset, ByteBuffer data) {
        requirePipeline();
        Objects.requireNonNull(data, "data");
        if (data.remaining() == 0 || offset < 0 || offset % 4 != 0
                || offset + data.remaining() > pipeline.spec().pushConstantBytes() || data.remaining() % 4 != 0) {
            throw new IllegalArgumentException("Push constant data exceeds the pipeline's declared range.");
        }
        vkCmdPushConstants(command, pipeline.layout(), VulkanPipeline.PUSH_STAGES, offset, data);
    }

    public void bindStreamVertices(int binding, long offset) {
        requirePipeline();
        bindVertexHandle(binding, stream.handle(), offset);
    }

    public void bindVertices(int binding, VulkanStaticBuffer buffer, long offset) {
        requirePipeline();
        Objects.requireNonNull(buffer, "buffer");
        requireOwned(buffer.belongsTo(renderer));
        bindVertexHandle(binding, buffer.handle(), offset);
        renderer.track(buffer, index);
    }

    /** Binds uint32 indices from a static buffer. */
    public void bindIndices(VulkanStaticBuffer buffer, long offset) {
        requirePipeline();
        Objects.requireNonNull(buffer, "buffer");
        requireOwned(buffer.belongsTo(renderer));
        vkCmdBindIndexBuffer(command, buffer.handle(), offset, VK_INDEX_TYPE_UINT32);
        renderer.track(buffer, index);
    }

    /** Binds uint32 indices written to this frame's stream region. */
    public void bindStreamIndices(long offset) {
        requirePipeline();
        vkCmdBindIndexBuffer(command, stream.handle(), offset, VK_INDEX_TYPE_UINT32);
    }

    public void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
        requirePipeline();
        if (vertexCount > 0 && instanceCount > 0) {
            vkCmdDraw(command, vertexCount, instanceCount, firstVertex, firstInstance);
        }
    }

    public void drawIndexed(int indexCount, int instanceCount, int firstIndex, int vertexOffset, int firstInstance) {
        requirePipeline();
        if (indexCount > 0 && instanceCount > 0) {
            vkCmdDrawIndexed(command, indexCount, instanceCount, firstIndex, vertexOffset, firstInstance);
        }
    }

    /**
     * Draws {@code count} VkDrawIndexedIndirectCommand records written to this frame's stream at
     * {@code streamOffset} (4-byte aligned, {@code stride} bytes apart). More than one draw, or a nonzero first
     * instance, needs {@link VulkanRenderer#supportsMultiDrawIndirect()}.
     */
    public void drawIndexedIndirect(long streamOffset, int count, int stride) {
        requirePipeline();
        if (count <= 0) {
            return;
        }
        if ((streamOffset & 3) != 0 || stride < 20 || (stride & 3) != 0) {
            throw new IllegalArgumentException("Indirect commands need 4-byte alignment and a stride of at least 20.");
        }
        if (count > 1 && !renderer.supportsMultiDrawIndirect()) {
            throw new IllegalStateException("This device cannot issue several draws per indirect call.");
        }
        org.lwjgl.vulkan.VK10.vkCmdDrawIndexedIndirect(command, stream.handle(), streamOffset, count, stride);
    }

    // ---- lifecycle (renderer-owned) -----------------------------------------------------------------------

    VkCommandBuffer commandBuffer() {
        requireOpen();
        return command;
    }

    void begin(VkCommandBuffer commandBuffer, int frameIndex, int frameWidth, int frameHeight) {
        command = commandBuffer;
        index = frameIndex;
        width = frameWidth;
        height = frameHeight;
        open = true;
        mainPassRecorded = false;
        pass = Pass.NONE;
        target = null;
        pipeline = null;
        transfersPending = false;
        stream.beginRegion(frameIndex);
    }

    /** Ends any open offscreen pass and reports whether the main pass was recorded. */
    boolean finishPasses() {
        if (pass == Pass.TARGET) {
            endRendering();
        }
        return mainPassRecorded;
    }

    /** Called when legacy renderer draws bind their own pipeline behind the encoder's back. */
    void forgetPipeline() {
        pipeline = null;
    }

    boolean mainPassOpen() {
        return open && pass == Pass.MAIN;
    }

    void close() {
        open = false;
        pass = Pass.NONE;
        gammaSpace = false;
        gammaSwitched = false;
        target = null;
        pipeline = null;
        command = null;
    }

    void free() {
        memFree(singleHandle);
        memFree(singleOffset);
        viewport.free();
        scissor.free();
        targetAttachment.free();
        targetDepthAttachment.free();
        targetRendering.free();
        attachmentBarrier.free();
        bufferCopy.free();
        transferBarrier.free();
        layerCopies.free();
        layerBarrier.free();
    }

    private void bindVertexHandle(int binding, long handle, long offset) {
        if (binding < 0 || offset < 0) {
            throw new IllegalArgumentException("Vertex binding and offset must not be negative.");
        }
        singleHandle.put(0, handle);
        singleOffset.put(0, offset);
        vkCmdBindVertexBuffers(command, binding, singleHandle, singleOffset);
    }

    private void requireOpen() {
        if (!open) {
            throw new IllegalStateException("The frame is not open; acquire a frame first.");
        }
        renderer.requireOwnerThreadForFrame();
    }

    private void requirePass() {
        requireOpen();
        if (pass == Pass.NONE) {
            throw new IllegalStateException("Begin a pass before recording draw state.");
        }
    }

    private void requirePipeline() {
        requirePass();
        if (pipeline == null) {
            throw new IllegalStateException("Bind a pipeline first.");
        }
    }

    private static void requireOwned(boolean owned) {
        if (!owned) {
            throw new IllegalArgumentException("Resources can only be used by their creating renderer.");
        }
    }
}
