package alkaline.renderer.particle;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.SplittableRandom;

/**
 * Fixed-capacity particle simulation stored as parallel primitive arrays. Ticks run at Minecraft's 20 Hz with
 * vanilla EntityFX motion (gravity, drag, collision with the block below, ground friction); rendering
 * interpolates between ticks. Dead particles are swap-removed, so nothing is allocated after construction.
 *
 * <p>Vanilla keeps one {@code EntityFX} object per particle in four lists capped at 4000 each and rebuilds
 * the vertex data through the tessellator every frame. Here a live particle costs about 100 bytes of arrays and
 * its per-frame GPU cost is one 32-byte instance record expanded to a quad by the vertex shader.
 */
public final class ParticleSystem {
    /** Bytes per instance written by {@link #writeInstances}. */
    public static final int INSTANCE_BYTES = 32;
    /** Pass as the color to keep the type's own tint. */
    public static final int TYPE_COLOR = 0;
    /** Full sky and block light ({@code sky << 4 | block}). */
    public static final int FULL_LIGHT = 0xFF;

    /** The world particles move in; all queries are block coordinates. */
    public interface World {
        /** Whether the block stops particles (a solid material). */
        boolean solid(int x, int y, int z);

        /** Whether the block is water (bubbles live only there). */
        boolean water(int x, int y, int z);

        /** Light as {@code sky << 4 | block}, vanilla's getCombinedLight without emission. */
        int light(int x, int y, int z);
    }

    private final int capacity;
    private final List<ParticleType> types = new ArrayList<>();
    private final SplittableRandom random;
    private final double[] x;
    private final double[] y;
    private final double[] z;
    private final double[] previousX;
    private final double[] previousY;
    private final double[] previousZ;
    private final float[] velocityX;
    private final float[] velocityY;
    private final float[] velocityZ;
    private final float[] size;
    private final int[] age;
    private final int[] maxAge;
    private final int[] color;
    private final short[] type;
    private final short[] cell;
    private final int[] layer;
    private final float[] jitterU;
    private final float[] jitterV;
    private final short[] light;
    private final boolean[] grounded;
    private int count;
    private int limit;
    private World world;

    public ParticleSystem(int capacity, long seed) {
        if (capacity < 1 || capacity > 1 << 20) {
            throw new IllegalArgumentException("Particle capacity must be between 1 and 1048576.");
        }
        this.capacity = capacity;
        this.limit = capacity;
        random = new SplittableRandom(seed);
        x = new double[capacity];
        y = new double[capacity];
        z = new double[capacity];
        previousX = new double[capacity];
        previousY = new double[capacity];
        previousZ = new double[capacity];
        velocityX = new float[capacity];
        velocityY = new float[capacity];
        velocityZ = new float[capacity];
        size = new float[capacity];
        age = new int[capacity];
        maxAge = new int[capacity];
        color = new int[capacity];
        type = new short[capacity];
        cell = new short[capacity];
        layer = new int[capacity];
        jitterU = new float[capacity];
        jitterV = new float[capacity];
        light = new short[capacity];
        grounded = new boolean[capacity];
    }

    public int count() {
        return count;
    }

    public int capacity() {
        return capacity;
    }

    /** Caps live particles at or below capacity (a user setting); excess particles expire naturally. */
    public void limit(int value) {
        limit = Math.max(0, Math.min(capacity, value));
    }

    /** The world particles collide with and take light from; null for none (full light, no collision). */
    public void world(World value) {
        world = value;
    }

    public void clear() {
        count = 0;
    }

    /** The random source effects use for their own spawn patterns, so a seeded system stays reproducible. */
    public SplittableRandom random() {
        return random;
    }

    public boolean spawn(ParticleType particle, double px, double py, double pz, double vx, double vy, double vz) {
        return spawn(particle, px, py, pz, vx, vy, vz, TYPE_COLOR, 0);
    }

    /**
     * Spawns one particle. The velocity becomes motion as the type's {@link ParticleType#launch()} says.
     *
     * @param argb a color replacing the type's tint and gray (redstone, spells, notes), or {@link #TYPE_COLOR}
     * @param textureLayer the block texture layer, for {@link ParticleType.Sheet#BLOCKS} particles
     * @return false when the live limit is reached (vanilla drops the oldest instead; dropping new particles
     *     keeps the cost bounded without shifting arrays)
     */
    public boolean spawn(ParticleType particle, double px, double py, double pz, double vx, double vy, double vz,
                         int argb, int textureLayer) {
        Objects.requireNonNull(particle, "particle");
        if (count >= limit) {
            return false;
        }
        int index = count++;
        x[index] = previousX[index] = px;
        y[index] = previousY[index] = py;
        z[index] = previousZ[index] = pz;
        double mx = vx;
        double my = vy;
        double mz = vz;
        switch (particle.launch()) {
            case GIVEN -> {
                mx *= particle.velocityScale();
                my *= particle.velocityScale();
                mz *= particle.velocityScale();
            }
            case SPREAD -> {
                double[] launched = launch(vx, vy, vz);
                mx = launched[0] * particle.velocityScale();
                my = launched[1] * particle.velocityScale();
                mz = launched[2] * particle.velocityScale();
            }
            case SPREAD_ADD -> {
                double[] launched = launch(0, 0, 0);
                mx = launched[0] * particle.velocityScale() + vx * particle.inherit();
                my = launched[1] * particle.velocityScale() + vy * particle.inherit();
                mz = launched[2] * particle.velocityScale() + vz * particle.inherit();
            }
        }
        velocityX[index] = (float) mx;
        velocityY[index] = (float) my;
        velocityZ[index] = (float) mz;
        // EntityFX base size (random * 0.5 + 0.5) * 2, times the type's own range.
        float base = (random.nextFloat() * 0.5f + 0.5f) * 2f;
        size[index] = base * (particle.scaleMin() + random.nextFloat() * (particle.scaleMax() - particle.scaleMin()));
        age[index] = 0;
        maxAge[index] = Math.max(1, (int) (particle.lifeBase()
                / (random.nextFloat() * particle.lifeRandom() + particle.lifeMin())) + particle.lifeExtra());
        float gray = particle.grayMin() + random.nextFloat() * (particle.grayMax() - particle.grayMin());
        color[index] = argb != TYPE_COLOR ? rgba(argb) : shade(particle.tint(), gray);
        type[index] = (short) typeIndex(particle);
        cell[index] = (short) (particle.frames() == ParticleType.Frames.RANDOM
                ? particle.firstCell() + random.nextInt(particle.cellCount()) : particle.firstCell());
        layer[index] = textureLayer;
        jitterU[index] = random.nextFloat() * 3f;
        jitterV[index] = random.nextFloat() * 3f;
        light[index] = (short) sampleLight(px, py, pz);
        grounded[index] = false;
        return true;
    }

    /** EntityFX's randomized launch: jitter, normalize to a random speed, and add 0.1 upward. */
    private double[] launch(double vx, double vy, double vz) {
        vx += (random.nextDouble() * 2.0 - 1.0) * 0.4;
        vy += (random.nextDouble() * 2.0 - 1.0) * 0.4;
        vz += (random.nextDouble() * 2.0 - 1.0) * 0.4;
        double speed = (random.nextDouble() + random.nextDouble() + 1.0) * 0.15;
        double length = Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (length > 1e-9) {
            vx = vx / length * speed * 0.4;
            vy = vy / length * speed * 0.4 + 0.1;
            vz = vz / length * speed * 0.4;
        }
        return new double[]{vx, vy, vz};
    }

    /** Advances every particle by one 20 Hz tick. */
    public void tick() {
        int index = 0;
        while (index < count) {
            ParticleType particle = types.get(type[index]);
            previousX[index] = x[index];
            previousY[index] = y[index];
            previousZ[index] = z[index];
            if (age[index]++ >= maxAge[index]) {
                removeAt(index);
                continue;
            }
            velocityY[index] -= particle.gravity();
            double nextY = y[index] + velocityY[index];
            x[index] += velocityX[index];
            z[index] += velocityZ[index];
            grounded[index] = false;
            if (world != null && particle.collides() && velocityY[index] < 0f) {
                int bx = (int) Math.floor(x[index]);
                int by = (int) Math.floor(nextY);
                int bz = (int) Math.floor(z[index]);
                if (world.solid(bx, by, bz) && !world.solid(bx, by + 1, bz) && y[index] >= by + 1) {
                    nextY = by + 1;
                    velocityY[index] = 0f;
                    grounded[index] = true;
                }
            }
            y[index] = nextY;
            velocityX[index] *= particle.drag();
            velocityY[index] *= particle.drag();
            velocityZ[index] *= particle.drag();
            if (grounded[index]) {
                velocityX[index] *= 0.7f;
                velocityZ[index] *= 0.7f;
                if (particle.groundDeath() > 0f && random.nextFloat() < particle.groundDeath()) {
                    removeAt(index);
                    continue;
                }
            }
            if (particle.needsWater() && world != null && !world.water((int) Math.floor(x[index]),
                    (int) Math.floor(y[index]), (int) Math.floor(z[index]))) {
                removeAt(index);
                continue;
            }
            if (particle.frames() == ParticleType.Frames.AGE_REVERSED) {
                int frame = particle.cellCount() - 1 - age[index] * particle.cellCount() / maxAge[index];
                cell[index] = (short) (particle.firstCell() + Math.max(0, frame));
            } else if (particle.frames() == ParticleType.Frames.AGE) {
                int frame = Math.min(particle.cellCount() - 1, age[index] * particle.cellCount() / maxAge[index]);
                cell[index] = (short) (particle.firstCell() + frame);
            }
            light[index] = (short) sampleLight(x[index], y[index], z[index]);
            index++;
        }
    }

    private int sampleLight(double px, double py, double pz) {
        return world == null ? FULL_LIGHT
                : world.light((int) Math.floor(px), (int) Math.floor(py), (int) Math.floor(pz));
    }

    /**
     * Writes interpolated instances of one sheet and blend family relative to the camera origin (keeps float
     * precision far from 0,0,0). Records: center xyz f32, half-size f32, uv min unorm16x2, uv max unorm16x2,
     * color rgba8 (already lit), texture layer u32.
     *
     * @param lightmap 256 lightmap texels (0xRRGGBB, index sky * 16 + block), or null for full light
     * @return instances written, at most {@code maxInstances}
     */
    public int writeInstances(ByteBuffer target, int offset, int maxInstances, float partialTick,
                              double cameraX, double cameraY, double cameraZ, ParticleType.Sheet sheet,
                              boolean additive, int[] lightmap) {
        int written = 0;
        for (int index = 0; index < count && written < maxInstances; index++) {
            ParticleType particle = types.get(type[index]);
            if (particle.additive() != additive || particle.sheet() != sheet) {
                continue;
            }
            float life = (age[index] + partialTick) / maxAge[index];
            float scale = size[index] * curve(particle.sizeCurve(), life) * 0.1f;
            if (sheet == ParticleType.Sheet.BLOCKS) {
                scale *= 0.5f; // EntityDiggingFX halves particleScale.
            }
            if (scale <= 0f) {
                continue;
            }
            int position = offset + written * INSTANCE_BYTES;
            target.putFloat(position, (float) (previousX[index] + (x[index] - previousX[index]) * partialTick - cameraX));
            target.putFloat(position + 4, (float) (previousY[index] + (y[index] - previousY[index]) * partialTick - cameraY));
            target.putFloat(position + 8, (float) (previousZ[index] + (z[index] - previousZ[index]) * partialTick - cameraZ));
            target.putFloat(position + 12, scale);
            float u0;
            float v0;
            float u1;
            float v1;
            if (sheet == ParticleType.Sheet.BLOCKS) {
                // A quarter of the block texture at the particle's random offset.
                u0 = jitterU[index] / 4f;
                v0 = jitterV[index] / 4f;
                u1 = u0 + 0.249f;
                v1 = v0 + 0.249f;
            } else {
                int grid = sheet.grid();
                float span = 1f / grid;
                int cellIndex = cell[index];
                u0 = (cellIndex % grid) * span;
                v0 = (cellIndex / grid) * span;
                u1 = u0 + span * 0.999f;
                v1 = v0 + span * 0.999f;
            }
            target.putInt(position + 16, unorm16(u0) | unorm16(v0) << 16);
            target.putInt(position + 20, unorm16(u1) | unorm16(v1) << 16);
            int rgba = color[index];
            if (!particle.fullBright() && lightmap != null) {
                rgba = modulate(rgba, lightmap[(light[index] >> 4 & 15) * 16 + (light[index] & 15)]);
            }
            target.putInt(position + 24, rgba);
            target.putInt(position + 28, layer[index]);
            written++;
        }
        return written;
    }

    /** Live particles of a sheet and blend family (to size the stream allocation). */
    public int count(ParticleType.Sheet sheet, boolean additive) {
        int matching = 0;
        for (int index = 0; index < count; index++) {
            ParticleType particle = types.get(type[index]);
            if (particle.sheet() == sheet && particle.additive() == additive) {
                matching++;
            }
        }
        return matching;
    }

    private static float curve(ParticleType.SizeCurve curve, float life) {
        return switch (curve) {
            case CONSTANT -> 1f;
            case POP_IN -> Math.min(1f, Math.max(0f, life * 32f));
            case SHRINK -> 1f - life * life * 0.5f;
            case FADE_OUT -> {
                float remaining = 1f - Math.min(1f, life);
                yield 1f - (1f - remaining) * (1f - remaining);
            }
        };
    }

    private void removeAt(int index) {
        int last = --count;
        if (index == last) {
            return;
        }
        x[index] = x[last];
        y[index] = y[last];
        z[index] = z[last];
        previousX[index] = previousX[last];
        previousY[index] = previousY[last];
        previousZ[index] = previousZ[last];
        velocityX[index] = velocityX[last];
        velocityY[index] = velocityY[last];
        velocityZ[index] = velocityZ[last];
        size[index] = size[last];
        age[index] = age[last];
        maxAge[index] = maxAge[last];
        color[index] = color[last];
        type[index] = type[last];
        cell[index] = cell[last];
        layer[index] = layer[last];
        jitterU[index] = jitterU[last];
        jitterV[index] = jitterV[last];
        light[index] = light[last];
        grounded[index] = grounded[last];
    }

    private int typeIndex(ParticleType particle) {
        for (int index = 0; index < types.size(); index++) {
            if (types.get(index) == particle) {
                return index;
            }
        }
        if (types.size() == Short.MAX_VALUE) {
            throw new IllegalStateException("Too many particle types.");
        }
        types.add(particle);
        return types.size() - 1;
    }

    /** ARGB tint scaled by gray, packed as little-endian RGBA8. */
    private static int shade(int argb, float gray) {
        int red = Math.round(((argb >>> 16) & 0xFF) * gray);
        int green = Math.round(((argb >>> 8) & 0xFF) * gray);
        int blue = Math.round((argb & 0xFF) * gray);
        return red | green << 8 | blue << 16 | (argb >>> 24) << 24;
    }

    private static int rgba(int argb) {
        return (argb >>> 16 & 0xFF) | (argb & 0xFF00) | (argb & 0xFF) << 16 | (argb >>> 24) << 24;
    }

    /** RGBA8 times a 0xRRGGBB light, channel by channel. */
    private static int modulate(int rgba, int rgb) {
        int red = (rgba & 0xFF) * (rgb >>> 16 & 0xFF) / 255;
        int green = (rgba >>> 8 & 0xFF) * (rgb >>> 8 & 0xFF) / 255;
        int blue = (rgba >>> 16 & 0xFF) * (rgb & 0xFF) / 255;
        return red | green << 8 | blue << 16 | rgba & 0xFF000000;
    }

    private static int unorm16(float value) {
        return Math.round(Math.max(0f, Math.min(1f, value)) * 65535f) & 0xFFFF;
    }
}
