package alkaline.renderer.particle;

import java.util.Objects;

/**
 * Data description of a particle's look and motion. Every vanilla 1.8.9 particle family reduces to these
 * parameters (see {@link VanillaParticles}); new effects are new values, not new classes. Built with
 * {@link #builder}; defaults are vanilla EntityFX's.
 */
public final class ParticleType {
    /** Which texture a particle samples. */
    public enum Sheet {
        /** {@code particle/particles.png}: a 16x16 grid of 8 px cells. */
        PARTICLES(16),
        /** {@code entity/explosion.png}: a 4x4 grid of animation frames (EntityLargeExplodeFX). */
        EXPLOSION(4),
        /** The block texture array: each particle samples a quarter of one block texture (EntityDiggingFX). */
        BLOCKS(1);

        private final int grid;

        Sheet(int grid) {
            this.grid = grid;
        }

        /** Cells per row and column. */
        public int grid() {
            return grid;
        }
    }

    public enum Frames {
        /** Always {@code firstCell}. */
        FIXED,
        /** One random cell in {@code [firstCell, firstCell + cellCount)} for the whole life. */
        RANDOM,
        /** Runs from the last cell to the first over the life, like smoke and spells. */
        AGE_REVERSED,
        /** Runs from the first cell to the last over the life (explosions). */
        AGE
    }

    /** How the spawn velocity becomes the particle's motion. */
    public enum Launch {
        /** The given velocity, times {@code velocityScale}. */
        GIVEN,
        /** EntityFX's randomized launch from the given velocity (+-0.4 jitter, normalized, +0.1 up), scaled. */
        SPREAD,
        /** The launch from rest, scaled, plus the given velocity times {@code inherit} (smoke, crits). */
        SPREAD_ADD
    }

    public enum SizeCurve {
        CONSTANT,
        /** Grows to full size over the first 1/32 of life (smoke, crit, hearts). */
        POP_IN,
        /** {@code 1 - t² / 2} (flame). */
        SHRINK,
        /** {@code 1 - (1 - t)²}-style ease toward zero (portal). */
        FADE_OUT
    }

    private final String id;
    private final Sheet sheet;
    private final int firstCell;
    private final int cellCount;
    private final Frames frames;
    private final float lifeBase;
    private final float lifeMin;
    private final float lifeRandom;
    private final int lifeExtra;
    private final Launch launch;
    private final float velocityScale;
    private final float inherit;
    private final float gravity;
    private final float drag;
    private final float scaleMin;
    private final float scaleMax;
    private final SizeCurve sizeCurve;
    private final float grayMin;
    private final float grayMax;
    private final int tint;
    private final boolean fullBright;
    private final boolean additive;
    private final boolean collides;
    private final float groundDeath;
    private final boolean needsWater;

    private ParticleType(Builder builder) {
        id = builder.id;
        sheet = builder.sheet;
        firstCell = builder.firstCell;
        cellCount = builder.cellCount;
        frames = builder.frames;
        lifeBase = builder.lifeBase;
        lifeMin = builder.lifeMin;
        lifeRandom = builder.lifeRandom;
        lifeExtra = builder.lifeExtra;
        launch = builder.launch;
        velocityScale = builder.velocityScale;
        inherit = builder.inherit;
        gravity = builder.gravity;
        drag = builder.drag;
        scaleMin = builder.scaleMin;
        scaleMax = builder.scaleMax;
        sizeCurve = builder.sizeCurve;
        grayMin = builder.grayMin;
        grayMax = builder.grayMax;
        tint = builder.tint;
        fullBright = builder.fullBright;
        additive = builder.additive;
        collides = builder.collides;
        groundDeath = builder.groundDeath;
        needsWater = builder.needsWater;
        int cells = sheet.grid() * sheet.grid();
        if (firstCell < 0 || cellCount < 1 || firstCell + cellCount > cells) {
            throw new IllegalArgumentException("Particle cells must lie within the sheet: " + id);
        }
        if (!(lifeBase > 0f) || !(lifeMin > 0f) || lifeRandom < 0f || lifeExtra < 0) {
            throw new IllegalArgumentException("Particle lifetime parameters must be positive: " + id);
        }
        if (!(scaleMin > 0f) || scaleMax < scaleMin || grayMin < 0f || grayMax < grayMin || grayMax > 1f) {
            throw new IllegalArgumentException("Particle scale and color ranges must be ordered and valid: " + id);
        }
        if (!Float.isFinite(velocityScale) || !Float.isFinite(inherit) || !Float.isFinite(gravity)
                || !(drag >= 0f && drag <= 1.5f) || !(groundDeath >= 0f && groundDeath <= 1f)) {
            throw new IllegalArgumentException("Particle motion parameters must be finite: " + id);
        }
    }

    public static Builder builder(String id) {
        return new Builder(id);
    }

    public String id() { return id; }
    public Sheet sheet() { return sheet; }
    public int firstCell() { return firstCell; }
    public int cellCount() { return cellCount; }
    public Frames frames() { return frames; }
    /** Lifetime: {@code maxAge = lifeBase / (random * lifeRandom + lifeMin) + lifeExtra}. */
    public float lifeBase() { return lifeBase; }
    public float lifeMin() { return lifeMin; }
    public float lifeRandom() { return lifeRandom; }
    public int lifeExtra() { return lifeExtra; }
    public Launch launch() { return launch; }
    public float velocityScale() { return velocityScale; }
    public float inherit() { return inherit; }
    /** Downward acceleration in blocks per tick² (negative rises: smoke 0.004 up). */
    public float gravity() { return gravity; }
    /** Per-tick velocity multiplier. */
    public float drag() { return drag; }
    public float scaleMin() { return scaleMin; }
    public float scaleMax() { return scaleMax; }
    public SizeCurve sizeCurve() { return sizeCurve; }
    public float grayMin() { return grayMin; }
    public float grayMax() { return grayMax; }
    /** ARGB multiplied with the gray level. */
    public int tint() { return tint; }
    public boolean fullBright() { return fullBright; }
    public boolean additive() { return additive; }
    /** Whether the particle stops on solid blocks (vanilla's moveEntity; off for noClip particles). */
    public boolean collides() { return collides; }
    /** Chance per tick of dying once on the ground (rain splashes: 0.5). */
    public float groundDeath() { return groundDeath; }
    /** Bubbles die outside water. */
    public boolean needsWater() { return needsWater; }

    @Override
    public String toString() {
        return id;
    }

    public static final class Builder {
        private final String id;
        private Sheet sheet = Sheet.PARTICLES;
        private int firstCell;
        private int cellCount = 1;
        private Frames frames = Frames.FIXED;
        private float lifeBase = 4f;
        private float lifeMin = 0.1f;
        private float lifeRandom = 0.9f;
        private int lifeExtra;
        private Launch launch = Launch.SPREAD;
        private float velocityScale = 1f;
        private float inherit;
        private float gravity;
        private float drag = 0.98f;
        private float scaleMin = 1f;
        private float scaleMax = 1f;
        private SizeCurve sizeCurve = SizeCurve.CONSTANT;
        private float grayMin = 1f;
        private float grayMax = 1f;
        private int tint = 0xFFFFFFFF;
        private boolean fullBright;
        private boolean additive;
        private boolean collides = true;
        private float groundDeath;
        private boolean needsWater;

        private Builder(String id) {
            this.id = Objects.requireNonNull(id, "id");
        }

        public Builder sheet(Sheet value) { sheet = value; return this; }
        public Builder cell(int cell) { firstCell = cell; cellCount = 1; frames = Frames.FIXED; return this; }
        public Builder cells(int first, int count, Frames value) { firstCell = first; cellCount = count; frames = value; return this; }
        public Builder life(float base, float min, float random, int extra) {
            lifeBase = base; lifeMin = min; lifeRandom = random; lifeExtra = extra; return this;
        }
        public Builder launch(Launch value, float scale, float inheritValue) {
            launch = value; velocityScale = scale; inherit = inheritValue; return this;
        }
        public Builder gravity(float value) { gravity = value; return this; }
        public Builder drag(float value) { drag = value; return this; }
        public Builder scale(float min, float max) { scaleMin = min; scaleMax = max; return this; }
        public Builder size(SizeCurve value) { sizeCurve = value; return this; }
        public Builder gray(float min, float max) { grayMin = min; grayMax = max; return this; }
        public Builder tint(int argb) { tint = argb; return this; }
        public Builder fullBright() { fullBright = true; return this; }
        public Builder additive() { additive = true; return this; }
        public Builder noClip() { collides = false; return this; }
        public Builder groundDeath(float chance) { groundDeath = chance; return this; }
        public Builder needsWater() { needsWater = true; return this; }

        public ParticleType build() {
            return new ParticleType(this);
        }
    }
}
