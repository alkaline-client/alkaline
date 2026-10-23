package alkaline.renderer.world;

public enum MaterialPass {
    OPAQUE(1),
    CUTOUT(2),
    TRANSLUCENT(3);

    private final byte code;

    MaterialPass(int code) {
        this.code = (byte) code;
    }

    public byte code() {
        return code;
    }
}
