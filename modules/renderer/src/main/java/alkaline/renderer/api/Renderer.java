package alkaline.renderer.api;

public interface Renderer extends AutoCloseable {
    RenderBuffer createBuffer(BufferDescriptor descriptor);

    @Override
    void close();
}
