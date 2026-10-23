package alkaline.renderer.api;

import java.util.EnumSet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BufferDescriptorTest {
    @Test
    void keepsItsUsageSetImmutable() {
        EnumSet<BufferUsage> usages = EnumSet.of(BufferUsage.VERTEX, BufferUsage.TRANSFER_DESTINATION);
        BufferDescriptor descriptor = new BufferDescriptor(128, usages, BufferDescriptor.MemoryAccess.HOST_VISIBLE);
        usages.clear();

        assertEquals(EnumSet.of(BufferUsage.VERTEX, BufferUsage.TRANSFER_DESTINATION), descriptor.usages());
        assertThrows(UnsupportedOperationException.class, () -> descriptor.usages().clear());
    }

    @Test
    void hostVisibilityMatchesTheSelectedMemoryAccess() {
        assertTrue(new BufferDescriptor(16, EnumSet.of(BufferUsage.VERTEX),
                BufferDescriptor.MemoryAccess.HOST_VISIBLE).hostVisible());
        assertTrue(new BufferDescriptor(16, EnumSet.of(BufferUsage.VERTEX),
                BufferDescriptor.MemoryAccess.HOST_COHERENT).hostVisible());
        assertFalse(new BufferDescriptor(16, EnumSet.of(BufferUsage.VERTEX),
                BufferDescriptor.MemoryAccess.DEVICE_LOCAL).hostVisible());
    }

    @Test
    void rejectsSizesThatCannotBeMappedAsOneByteBuffer() {
        assertThrows(IllegalArgumentException.class,
                () -> BufferDescriptor.vertex((long)Integer.MAX_VALUE + 1));
    }

    @Test
    void rejectsNonPositiveSizesAndEmptyUsageSets() {
        assertThrows(IllegalArgumentException.class,
                () -> new BufferDescriptor(0, EnumSet.of(BufferUsage.VERTEX), BufferDescriptor.MemoryAccess.DEVICE_LOCAL));
        assertThrows(IllegalArgumentException.class,
                () -> new BufferDescriptor(16, EnumSet.noneOf(BufferUsage.class), BufferDescriptor.MemoryAccess.DEVICE_LOCAL));
    }
}
