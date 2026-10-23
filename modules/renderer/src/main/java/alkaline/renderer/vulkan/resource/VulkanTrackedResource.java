package alkaline.renderer.vulkan.resource;

/** Defers native destruction until every frame slot that referenced this resource has completed. */
abstract class VulkanTrackedResource {
    private int inFlightFrames;
    private boolean releaseRequested;
    private boolean released;

    final void markFrameInFlight(int frameIndex) {
        if (released || releaseRequested) {
            throw new IllegalStateException("Vulkan resource is being released.");
        }
        inFlightFrames |= 1 << frameIndex;
    }

    final void frameCompleted(int frameIndex) {
        inFlightFrames &= ~(1 << frameIndex);
        releaseIfReady();
    }

    final void requestRelease() {
        releaseRequested = true;
        releaseIfReady();
    }

    final void releaseAfterDeviceIdle() {
        inFlightFrames = 0;
        releaseRequested = true;
        releaseIfReady();
    }

    final void ensureResourceOpen() {
        if (releaseRequested || released) {
            throw new IllegalStateException("Vulkan resource is closed.");
        }
    }

    final boolean isResourceReleased() {
        return released;
    }

    private void releaseIfReady() {
        if (releaseRequested && inFlightFrames == 0 && !released) {
            released = true;
            releaseNative();
        }
    }

    abstract void releaseNative();
}
