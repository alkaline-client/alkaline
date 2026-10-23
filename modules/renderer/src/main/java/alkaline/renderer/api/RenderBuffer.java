package alkaline.renderer.api;

import java.nio.ByteBuffer;

public interface RenderBuffer extends AutoCloseable {
    long nativeHandle();

    long size();

    boolean isHostVisible();

    /** Uploads through host mapping and is available only when {@link #isHostVisible()} is true. */
    void upload(long offset, ByteBuffer data);

    /** Releases the buffer after all submitted GPU work using it has completed. */
    @Override
    void close();
}
