package alkaline.renderer.vulkan.world;

import alkaline.renderer.vulkan.resource.VulkanRenderer;
import alkaline.renderer.vulkan.resource.VulkanStaticBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Device-local storage in fixed-size pages, sub-allocated in whole units (a terrain quad's vertices, or a
 * translucent quad's six indices) with first-fit and coalescing frees. Freed ranges are reused only after
 * every frame that might still read them has completed, so replacing a section never stalls the GPU. Pages
 * are added on demand (outside frames) and never moved.
 */
final class GeometryPool implements AutoCloseable {
    /** Frames that may still be in flight after a free; matches the renderer's frame slots plus one. */
    private static final int FREE_DELAY_FRAMES = 3;

    record Allocation(int page, int firstQuad, int quads) {
    }

    private record PendingFree(long frame, Allocation allocation) {
    }

    private final VulkanRenderer renderer;
    private final int unitBytes;
    private final int pageUnits;
    private final List<VulkanStaticBuffer> pages = new ArrayList<>();
    private final List<TreeMap<Integer, Integer>> free = new ArrayList<>();
    private final ArrayDeque<PendingFree> pending = new ArrayDeque<>();
    private long frame;
    private long usedQuads;

    GeometryPool(VulkanRenderer renderer, int unitBytes, long pageBytes) {
        this.renderer = renderer;
        this.unitBytes = unitBytes;
        pageUnits = (int) (pageBytes / unitBytes);
    }

    int unitBytes() {
        return unitBytes;
    }

    VulkanStaticBuffer page(int index) {
        return pages.get(index);
    }

    int pageCount() {
        return pages.size();
    }

    long usedBytes() {
        return usedQuads * unitBytes;
    }

    long capacityBytes() {
        return (long) pages.size() * pageUnits * unitBytes;
    }

    /** Advances the frame clock and recycles ranges no frame can still read; call once per frame, outside it. */
    void beginFrame() {
        frame++;
        while (!pending.isEmpty() && frame - pending.peek().frame() >= FREE_DELAY_FRAMES) {
            release(pending.poll().allocation());
        }
    }

    /** Reserves quads; may create a page, so call outside a frame. Returns null for oversized requests. */
    Allocation allocate(int quads) {
        if (quads <= 0 || quads > pageUnits) {
            return null;
        }
        for (int page = 0; page < pages.size(); page++) {
            Allocation allocation = takeFrom(page, quads);
            if (allocation != null) {
                return allocation;
            }
        }
        grow();
        return takeFrom(pages.size() - 1, quads);
    }

    /** Reserves units from existing pages only; safe inside a frame. Returns null when nothing fits. */
    Allocation allocateWithoutGrowth(int quads) {
        if (quads <= 0 || quads > pageUnits) {
            return null;
        }
        for (int page = 0; page < pages.size(); page++) {
            Allocation allocation = takeFrom(page, quads);
            if (allocation != null) {
                return allocation;
            }
        }
        return null;
    }

    /** Adds a page; call outside a frame. */
    void grow() {
        pages.add(renderer.createDeviceBuffer((long) pageUnits * unitBytes));
        TreeMap<Integer, Integer> ranges = new TreeMap<>();
        ranges.put(0, pageUnits);
        free.add(ranges);
    }

    /** Whether a request this size fits without creating a page. */
    boolean fitsWithoutGrowth(int quads) {
        for (TreeMap<Integer, Integer> ranges : free) {
            for (int length : ranges.values()) {
                if (length >= quads) {
                    return true;
                }
            }
        }
        return false;
    }

    void free(Allocation allocation) {
        pending.add(new PendingFree(frame, allocation));
    }

    private Allocation takeFrom(int page, int quads) {
        TreeMap<Integer, Integer> ranges = free.get(page);
        for (Map.Entry<Integer, Integer> range : ranges.entrySet()) {
            if (range.getValue() >= quads) {
                int start = range.getKey();
                int remaining = range.getValue() - quads;
                ranges.remove(start);
                if (remaining > 0) {
                    ranges.put(start + quads, remaining);
                }
                usedQuads += quads;
                return new Allocation(page, start, quads);
            }
        }
        return null;
    }

    private void release(Allocation allocation) {
        TreeMap<Integer, Integer> ranges = free.get(allocation.page());
        int start = allocation.firstQuad();
        int length = allocation.quads();
        Map.Entry<Integer, Integer> before = ranges.floorEntry(start);
        if (before != null && before.getKey() + before.getValue() == start) {
            start = before.getKey();
            length += before.getValue();
            ranges.remove(before.getKey());
        }
        Integer after = ranges.get(allocation.firstQuad() + allocation.quads());
        if (after != null) {
            ranges.remove(allocation.firstQuad() + allocation.quads());
            length += after;
        }
        ranges.put(start, length);
        usedQuads -= allocation.quads();
    }

    @Override
    public void close() {
        pages.forEach(VulkanStaticBuffer::close);
        pages.clear();
        free.clear();
        pending.clear();
    }
}
