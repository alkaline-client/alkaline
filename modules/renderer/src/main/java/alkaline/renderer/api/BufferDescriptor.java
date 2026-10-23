package alkaline.renderer.api;

import java.util.EnumSet;
import java.util.Set;

public record BufferDescriptor(long size, Set<BufferUsage> usages, MemoryAccess memoryAccess) {
    public BufferDescriptor {
        if (size <= 0 || size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Buffer size must be between 1 and Integer.MAX_VALUE bytes.");
        }
        if (memoryAccess == null) {
            throw new NullPointerException("memoryAccess");
        }
        if (usages == null) {
            throw new NullPointerException("usages");
        }
        usages = Set.copyOf(usages);
        if (usages.isEmpty()) {
            throw new IllegalArgumentException("A buffer must declare at least one usage.");
        }
    }

    public static BufferDescriptor vertex(long size) {
        return new BufferDescriptor(size, EnumSet.of(BufferUsage.VERTEX), MemoryAccess.HOST_VISIBLE);
    }

    public boolean hostVisible() {
        return memoryAccess == MemoryAccess.HOST_VISIBLE || memoryAccess == MemoryAccess.HOST_COHERENT;
    }

    public enum MemoryAccess {
        DEVICE_LOCAL,
        HOST_VISIBLE,
        HOST_COHERENT
    }
}
