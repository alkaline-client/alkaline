package alkaline.renderer.vulkan.resource;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable description of a graphics pipeline. The renderer keeps the spec so a pipeline can be rebuilt when
 * its attachment formats change; shaders are packaged SPIR-V resources named {@code <name>.spv.base64}.
 */
public final class PipelineSpec {
    /** Fixed-function color blending; color math happens in the attachment's encoding (linear for sRGB). */
    public enum Blend {
        NONE,
        /** Straight alpha: src * a + dst * (1 - a). */
        ALPHA,
        /** Premultiplied alpha: src + dst * (1 - a). */
        PREMULTIPLIED,
        /** src * a + dst. */
        ADDITIVE,
        /** src * src + dst: vanilla's enchantment glint (GL_SRC_COLOR, GL_ONE). */
        SOURCE_SQUARED,
        /** No color writes: a depth prepass (vanilla's fancy clouds draw depth first, then color). */
        DEPTH_ONLY,
        /** (1 - dst) * src: custom sky "subtract". */
        INVERT,
        /** dst * src + dst * (1 - a): custom sky "multiply". */
        MULTIPLY,
        /** src + dst: custom sky "dodge". */
        DODGE,
        /** dst * (1 - src): custom sky "burn". */
        BURN,
        /** src + dst * (1 - src): custom sky "screen". */
        SCREEN,
        /** dst * src + src * dst: custom sky "overlay". */
        OVERLAY
    }

    public enum Topology { TRIANGLES, TRIANGLE_STRIP }

    public record Binding(int binding, int stride, boolean perInstance) { }

    public record Attribute(int location, int binding, VertexFormat format, int offset) { }

    public static final int MAX_PUSH_CONSTANT_BYTES = 128;

    private final String vertexShader;
    private final String fragmentShader;
    private final List<Binding> bindings;
    private final List<Attribute> attributes;
    private final Topology topology;
    private final Blend blend;
    private final boolean depthTest;
    private final boolean depthWrite;
    private final boolean cullBackFaces;
    private final boolean sampledImage;
    private final int sampledImages;
    private final int pushConstantBytes;
    private final RenderTargetFormat offscreenFormat;
    private final boolean offscreenDepth;
    private final boolean gammaSpace;

    private PipelineSpec(Builder builder) {
        vertexShader = builder.vertexShader;
        fragmentShader = builder.fragmentShader;
        bindings = List.copyOf(builder.bindings);
        attributes = List.copyOf(builder.attributes);
        topology = builder.topology;
        blend = builder.blend;
        depthTest = builder.depthTest;
        depthWrite = builder.depthWrite;
        cullBackFaces = builder.cullBackFaces;
        sampledImage = builder.sampledImages > 0;
        sampledImages = builder.sampledImages;
        pushConstantBytes = builder.pushConstantBytes;
        offscreenFormat = builder.offscreenFormat;
        offscreenDepth = builder.offscreenDepth;
        gammaSpace = builder.gammaSpace;
        if (offscreenFormat != null && !offscreenDepth && (depthTest || depthWrite)) {
            throw new IllegalArgumentException("This offscreen pipeline declares no depth attachment.");
        }
        for (Attribute attribute : attributes) {
            if (bindings.stream().noneMatch(binding -> binding.binding() == attribute.binding())) {
                throw new IllegalArgumentException("Attribute " + attribute.location() + " uses an undeclared binding.");
            }
        }
    }

    public static Builder builder(String vertexShader, String fragmentShader) {
        return new Builder(vertexShader, fragmentShader);
    }

    String vertexShader() { return vertexShader; }
    String fragmentShader() { return fragmentShader; }
    List<Binding> bindings() { return bindings; }
    List<Attribute> attributes() { return attributes; }
    Topology topology() { return topology; }
    Blend blend() { return blend; }
    boolean depthTest() { return depthTest; }
    boolean depthWrite() { return depthWrite; }
    boolean cullBackFaces() { return cullBackFaces; }
    boolean sampledImage() { return sampledImage; }
    /** How many single-image descriptor sets (sets 0..n-1, each one combined image sampler at binding 0). */
    int sampledImages() { return sampledImages; }
    int pushConstantBytes() { return pushConstantBytes; }

    /** Null means the pipeline renders into the main swapchain pass, which carries the depth attachment. */
    RenderTargetFormat offscreenFormat() { return offscreenFormat; }

    /** Whether an offscreen pipeline renders into targets created with a depth attachment. */
    boolean offscreenDepth() { return offscreenDepth; }

    /** Whether the pipeline renders through the color image's UNORM alias (see {@link VulkanFrame#beginGammaSpace}). */
    boolean gammaSpace() { return gammaSpace; }

    public static final class Builder {
        private final String vertexShader;
        private final String fragmentShader;
        private final List<Binding> bindings = new ArrayList<>();
        private final List<Attribute> attributes = new ArrayList<>();
        private Topology topology = Topology.TRIANGLES;
        private Blend blend = Blend.NONE;
        private boolean depthTest;
        private boolean depthWrite;
        private boolean cullBackFaces;
        private int sampledImages;
        private int pushConstantBytes;
        private RenderTargetFormat offscreenFormat;
        private boolean offscreenDepth;
        private boolean gammaSpace;

        private Builder(String vertexShader, String fragmentShader) {
            this.vertexShader = requireShaderName(vertexShader);
            this.fragmentShader = requireShaderName(fragmentShader);
        }

        public Builder binding(int binding, int stride, boolean perInstance) {
            if (binding < 0 || stride <= 0 || stride > 2048) {
                throw new IllegalArgumentException("Vertex binding stride must be between 1 and 2048 bytes.");
            }
            bindings.add(new Binding(binding, stride, perInstance));
            return this;
        }

        public Builder attribute(int location, int binding, VertexFormat format, int offset) {
            if (location < 0 || offset < 0) {
                throw new IllegalArgumentException("Vertex attribute location and offset must not be negative.");
            }
            attributes.add(new Attribute(location, binding, Objects.requireNonNull(format, "format"), offset));
            return this;
        }

        public Builder topology(Topology value) {
            topology = Objects.requireNonNull(value, "topology");
            return this;
        }

        public Builder blend(Blend value) {
            blend = Objects.requireNonNull(value, "blend");
            return this;
        }

        /** Depth compare is LESS_OR_EQUAL against the main pass depth attachment, cleared to 1. */
        public Builder depth(boolean test, boolean write) {
            depthTest = test;
            depthWrite = write;
            return this;
        }

        /** Culls clockwise faces as seen in a y-up (GL-style) viewport; see {@link VulkanFrame#setViewport}. */
        public Builder cullBackFaces(boolean value) {
            cullBackFaces = value;
            return this;
        }

        /** Declares one combined image sampler at set 0, binding 0. */
        public Builder sampledImage(boolean value) {
            sampledImages = value ? Math.max(1, sampledImages) : 0;
            return this;
        }

        /** Declares {@code count} combined image samplers, one per set (0..count-1), each at binding 0. */
        public Builder sampledImages(int count) {
            if (count < 0 || count > 2) {
                throw new IllegalArgumentException("Pipelines sample at most two images.");
            }
            sampledImages = count;
            return this;
        }

        /** Push constants visible to both vertex and fragment stages. */
        public Builder pushConstants(int bytes) {
            if (bytes < 0 || bytes > MAX_PUSH_CONSTANT_BYTES || bytes % 4 != 0) {
                throw new IllegalArgumentException("Push constants must be a multiple of 4 bytes up to 128.");
            }
            pushConstantBytes = bytes;
            return this;
        }

        public Builder offscreen(RenderTargetFormat format) {
            return offscreen(format, false);
        }

        /** Offscreen color target, with the renderer's depth format when the target was created with depth. */
        public Builder offscreen(RenderTargetFormat format, boolean withDepth) {
            offscreenFormat = Objects.requireNonNull(format, "format");
            offscreenDepth = withDepth;
            return this;
        }

        /**
         * Renders between {@link VulkanFrame#beginGammaSpace} and {@link VulkanFrame#endGammaSpace}: the
         * attachment is the UNORM alias of an sRGB color image, so blending runs on sRGB-encoded values as in
         * OpenGL's default framebuffer, and shaders output encoded colors.
         */
        public Builder gammaSpace() {
            gammaSpace = true;
            return this;
        }

        public PipelineSpec build() {
            return new PipelineSpec(this);
        }

        private static String requireShaderName(String name) {
            Objects.requireNonNull(name, "shader");
            if (!name.matches("[a-z0-9_]+\\.(vert|frag)")) {
                throw new IllegalArgumentException("Shader names must be packaged resource names like 'ui.frag'.");
            }
            return name;
        }
    }
}
