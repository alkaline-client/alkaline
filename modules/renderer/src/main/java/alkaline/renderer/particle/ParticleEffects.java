package alkaline.renderer.particle;

import java.util.Objects;
import java.util.Random;
import java.util.SplittableRandom;

/**
 * Turns what the game reports into particles as vanilla 1.8.9 does: the Particle packet's count and spread
 * (NetHandlerPlayClient.handleParticles), RenderGlobal.spawnEntityFX's particle setting and 16-block range, block
 * break debris (EffectRenderer.addBlockDestroyEffects), the Effect packet's particle events (smoke, potion
 * splashes, ender eyes, spawner flames, bone meal), critical hit emitters and rain splashes.
 */
public final class ParticleEffects {
    /** Vanilla's particle setting. */
    public enum Level { ALL, DECREASED, MINIMAL }

    /** Block appearance for debris particles. */
    public interface Blocks {
        /** The block texture layer for a state's particles, or -1 for none (air). */
        int particleLayer(int state);

        /** The particles' color multiplier for a state (0xRRGGBB). */
        int particleTint(int state);
    }

    /** The world around the camera for rain splashes. */
    public interface Rain {
        /** The y of the first air block above the column's top precipitation-stopping block. */
        int precipitationHeight(int x, int z);

        /** Whether rain (not snow) falls on the column at that height. */
        boolean rains(int x, int y, int z);

        /** The block state below the rain's landing point. */
        int state(int x, int y, int z);
    }

    /** The pack's color for water particles at a spawn point. */
    public interface Water {
        /** CustomColors.updateWaterFX's color (0xRRGGBB) for a particle spawned there, or -1 for the type's own. */
        int tint(double x, double y, double z);
    }

    /** The pack's color for redstone dust at a spawn point. */
    public interface Redstone {
        /** CustomColors.updateReddustFX's color (0xRRGGBB) for dust spawned there, or -1 for the packet's own. */
        int tint(double x, double y, double z);
    }

    private static final int EMITTER_TICKS = 3;
    private static final int MAX_EMITTERS = 64;

    private final ParticleSystem system;
    private final Blocks blocks;
    private final SplittableRandom random;
    private final Random rainRandom = new Random();
    private Level level = Level.ALL;
    private Water water = (x, y, z) -> -1;
    private Redstone redstone = (x, y, z) -> -1;
    private double cameraX;
    private double cameraY;
    private double cameraZ;
    // Critical hit emitters: position, half extents and remaining ticks.
    private final double[] emitterX = new double[MAX_EMITTERS];
    private final double[] emitterY = new double[MAX_EMITTERS];
    private final double[] emitterZ = new double[MAX_EMITTERS];
    private final float[] emitterWidth = new float[MAX_EMITTERS];
    private final float[] emitterHeight = new float[MAX_EMITTERS];
    private final int[] emitterTicks = new int[MAX_EMITTERS];
    private final boolean[] emitterMagic = new boolean[MAX_EMITTERS];
    private int emitters;

    public ParticleEffects(ParticleSystem system, Blocks blocks) {
        this.system = Objects.requireNonNull(system, "system");
        this.blocks = Objects.requireNonNull(blocks, "blocks");
        random = system.random();
    }

    public void level(Level value) {
        level = Objects.requireNonNull(value, "value");
    }

    /**
     * Where bubbles, splashes and rain drops take their color from (RenderGlobal.doSpawnParticle calls
     * CustomColors.updateWaterFX for exactly these three). Asked once per spawn, never per frame.
     */
    public void water(Water value) {
        water = Objects.requireNonNull(value, "value");
    }

    /** Where redstone dust takes its color from (RenderGlobal.spawnEntityFX calls updateReddustFX for it). */
    public void redstone(Redstone value) {
        redstone = Objects.requireNonNull(value, "value");
    }

    /** The viewer's position, for spawnEntityFX's range check. */
    public void camera(double x, double y, double z) {
        cameraX = x;
        cameraY = y;
        cameraZ = z;
    }

    /** One Particle packet. */
    public void packet(int id, boolean longDistance, double x, double y, double z, float offsetX, float offsetY,
                       float offsetZ, float speed, int count, int[] arguments) {
        if (count == 0) {
            spawn(id, longDistance, x, y, z, speed * offsetX, speed * offsetY, speed * offsetZ, arguments);
            return;
        }
        for (int index = 0; index < Math.min(count, 4096); index++) {
            spawn(id, longDistance, x + gaussian() * offsetX, y + gaussian() * offsetY, z + gaussian() * offsetZ,
                    gaussian() * speed, gaussian() * speed, gaussian() * speed, arguments);
        }
    }

    /**
     * RenderGlobal.spawnEntityFX: long-distance particles always spawn; others only within 16 blocks and as the
     * particle setting allows (Decreased skips a third, Minimal all).
     */
    public void spawn(int id, boolean ignoreRange, double x, double y, double z, double vx, double vy, double vz,
                      int... arguments) {
        if (!ignoreRange) {
            Level effective = level;
            if (effective == Level.DECREASED && random.nextInt(3) == 0) {
                effective = Level.MINIMAL;
            }
            double dx = cameraX - x;
            double dy = cameraY - y;
            double dz = cameraZ - z;
            if (dx * dx + dy * dy + dz * dz > 256.0 || effective == Level.MINIMAL) {
                return;
            }
        }
        if (id == 2) {
            hugeExplosion(x, y, z);
            return;
        }
        ParticleType type = VanillaParticles.byId(id);
        if (type == null) {
            return;
        }
        int color = ParticleSystem.TYPE_COLOR;
        int layer = 0;
        if (VanillaParticles.colorFromVelocity(id)) {
            // Mob spells and redstone dust carry their color as the velocity (redstone: 0 red means default).
            float red = id == 30 && vx == 0 ? 1f : (float) vx;
            float alpha = id == 16 ? 0.15f : 1f;
            float shade = id == 30 ? (float) (random.nextDouble() * 0.4 + 0.6) : 1f;
            color = VanillaParticles.argb(red * shade, (float) vy * shade, (float) vz * shade, alpha);
            vx = vy = vz = 0;
            int custom = id == 30 ? redstone.tint(x, y, z) : -1;
            if (custom >= 0) {
                // OptiFine replaces the whole color, shade included.
                color = 0xFF000000 | custom;
            }
        } else if (id == 23) {
            color = VanillaParticles.noteColor(vx);
            vx = vy = vz = 0;
        } else if (id == 33) {
            color = 0xFF7EB862; // slime ball green (the item texture is not on a particle sheet)
        } else if (type == VanillaParticles.BUBBLE || type == VanillaParticles.SPLASH
                || type == VanillaParticles.WATER_DROP) {
            color = waterColor(x, y, z);
        } else if (type.sheet() == ParticleType.Sheet.BLOCKS) {
            int state = id == 36 ? itemState(arguments) : arguments.length > 0 ? arguments[0] : 0;
            layer = blocks.particleLayer(state);
            if (layer < 0) {
                return;
            }
            int tint = blocks.particleTint(state);
            color = VanillaParticles.argb(0.6f * (tint >> 16 & 0xFF) / 255f, 0.6f * (tint >> 8 & 0xFF) / 255f,
                    0.6f * (tint & 0xFF) / 255f, 1f);
        }
        system.spawn(type, x, y, z, vx, vy, vz, color, layer);
    }

    private int waterColor(double x, double y, double z) {
        int tint = water.tint(x, y, z);
        return tint >= 0 ? 0xFF000000 | tint : ParticleSystem.TYPE_COLOR;
    }

    /** Item crack of a block item (id below 256) breaks like the block; other items have no block texture. */
    private static int itemState(int[] arguments) {
        if (arguments.length < 1 || arguments[0] <= 0 || arguments[0] >= 256) {
            return 0;
        }
        return arguments[0] << 4 | (arguments.length > 1 ? arguments[1] & 15 : 0);
    }

    /** EntityHugeExplodeFX: eight large explosions scattered around the center (all at once here). */
    private void hugeExplosion(double x, double y, double z) {
        for (int index = 0; index < 8; index++) {
            system.spawn(VanillaParticles.LARGE_EXPLODE, x + (random.nextDouble() - random.nextDouble()) * 4.0,
                    y + (random.nextDouble() - random.nextDouble()) * 4.0,
                    z + (random.nextDouble() - random.nextDouble()) * 4.0, 0, 0, 0);
        }
    }

    /** EffectRenderer.addBlockDestroyEffects: a 4x4x4 grid of fragments flying out from the center. */
    public void blockDestroyed(int x, int y, int z, int state) {
        int layer = blocks.particleLayer(state);
        if (layer < 0) {
            return;
        }
        int tint = blocks.particleTint(state);
        int color = VanillaParticles.argb(0.6f * (tint >> 16 & 0xFF) / 255f, 0.6f * (tint >> 8 & 0xFF) / 255f,
                0.6f * (tint & 0xFF) / 255f, 1f);
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                for (int k = 0; k < 4; k++) {
                    double px = x + (i + 0.5) / 4;
                    double py = y + (j + 0.5) / 4;
                    double pz = z + (k + 0.5) / 4;
                    system.spawn(VanillaParticles.BLOCK_CRACK, px, py, pz, px - x - 0.5, py - y - 0.5, pz - z - 0.5,
                            color, layer);
                }
            }
        }
    }

    /** RenderGlobal.playAuxSFX's particle events. */
    public void worldEffect(int type, int x, int y, int z, int data) {
        switch (type) {
            case 2000 -> {
                int dx = data % 3 - 1;
                int dz = data / 3 % 3 - 1;
                double cx = x + dx * 0.6 + 0.5;
                double cy = y + 0.5;
                double cz = z + dz * 0.6 + 0.5;
                for (int index = 0; index < 10; index++) {
                    double speed = random.nextDouble() * 0.2 + 0.01;
                    spawn(11, false, cx + dx * 0.01 + (random.nextDouble() - 0.5) * dz * 0.5,
                            cy + (random.nextDouble() - 0.5) * 0.5,
                            cz + dz * 0.01 + (random.nextDouble() - 0.5) * dx * 0.5,
                            dx * speed + gaussian() * 0.01, -0.03 + gaussian() * 0.01, dz * speed + gaussian() * 0.01);
                }
            }
            case 2001 -> blockDestroyed(x, y, z, (data & 4095) << 4 | (data >> 12 & 15));
            case 2002 -> {
                int rgb = potionColor(data);
                for (int index = 0; index < 100; index++) {
                    double reach = random.nextDouble() * 4.0;
                    double angle = random.nextDouble() * Math.PI * 2.0;
                    double vx = Math.cos(angle) * reach;
                    double vz = Math.sin(angle) * reach;
                    float shade = 0.75f + random.nextFloat() * 0.25f;
                    if (withinRange(x, y, z)) {
                        system.spawn(VanillaParticles.POTION_SPLASH, x + vx * 0.1, y + 0.3, z + vz * 0.1, vx * 0.05,
                                (0.01 + random.nextDouble() * 0.5) * 0.2,
                                vz * 0.05, VanillaParticles.argb((rgb >> 16 & 0xFF) / 255f * shade,
                                        (rgb >> 8 & 0xFF) / 255f * shade, (rgb & 0xFF) / 255f * shade, 1f), 0);
                    }
                }
            }
            case 2003 -> {
                for (double angle = 0; angle < Math.PI * 2; angle += Math.PI / 20) {
                    spawn(24, false, x + 0.5 + Math.cos(angle) * 5, y - 0.4, z + 0.5 + Math.sin(angle) * 5,
                            Math.cos(angle) * -5, 0, Math.sin(angle) * -5);
                    spawn(24, false, x + 0.5 + Math.cos(angle) * 5, y - 0.4, z + 0.5 + Math.sin(angle) * 5,
                            Math.cos(angle) * -7, 0, Math.sin(angle) * -7);
                }
            }
            case 2004 -> {
                for (int index = 0; index < 20; index++) {
                    double px = x + 0.5 + (random.nextFloat() - 0.5) * 2.0;
                    double py = y + 0.5 + (random.nextFloat() - 0.5) * 2.0;
                    double pz = z + 0.5 + (random.nextFloat() - 0.5) * 2.0;
                    spawn(11, false, px, py, pz, 0, 0, 0);
                    spawn(26, false, px, py, pz, 0, 0, 0);
                }
            }
            case 2005 -> {
                // ItemDye.spawnBonemealParticles: happy villager sparkles over the block.
                int amount = data == 0 ? 15 : data;
                for (int index = 0; index < amount; index++) {
                    spawn(21, false, x + random.nextFloat(), y + random.nextFloat(), z + random.nextFloat(),
                            gaussian() * 0.02, gaussian() * 0.02, gaussian() * 0.02);
                }
            }
            default -> {
            }
        }
    }

    private boolean withinRange(double x, double y, double z) {
        double dx = cameraX - x;
        double dy = cameraY - y;
        double dz = cameraZ - z;
        return dx * dx + dy * dy + dz * dz <= 256.0 && level != Level.MINIMAL;
    }

    /**
     * A critical hit on an entity (Animation 4, or 5 for enchanted damage): EntityParticleEmitter sprays 16
     * particles a tick for three ticks from its middle.
     */
    public void critical(double x, double feetY, double z, float width, float height, boolean magic) {
        if (emitters == MAX_EMITTERS) {
            return;
        }
        emitterX[emitters] = x;
        emitterY[emitters] = feetY;
        emitterZ[emitters] = z;
        emitterWidth[emitters] = width;
        emitterHeight[emitters] = height;
        emitterTicks[emitters] = EMITTER_TICKS;
        emitterMagic[emitters] = magic;
        emitters++;
    }

    /** Runs emitters for this 20 Hz tick; call before {@link ParticleSystem#tick()}. */
    public void tick() {
        int index = 0;
        while (index < emitters) {
            for (int spray = 0; spray < 16; spray++) {
                double dx = random.nextFloat() * 2f - 1f;
                double dy = random.nextFloat() * 2f - 1f;
                double dz = random.nextFloat() * 2f - 1f;
                if (dx * dx + dy * dy + dz * dz <= 1.0) {
                    spawn(emitterMagic[index] ? 10 : 9, false, emitterX[index] + dx * emitterWidth[index] / 4.0,
                            emitterY[index] + emitterHeight[index] / 2.0 + dy * emitterHeight[index] / 4.0,
                            emitterZ[index] + dz * emitterWidth[index] / 4.0, dx, dy + 0.2, dz);
                }
            }
            if (--emitterTicks[index] <= 0) {
                emitters--;
                emitterX[index] = emitterX[emitters];
                emitterY[index] = emitterY[emitters];
                emitterZ[index] = emitterZ[emitters];
                emitterWidth[index] = emitterWidth[emitters];
                emitterHeight[index] = emitterHeight[emitters];
                emitterTicks[index] = emitterTicks[emitters];
                emitterMagic[index] = emitterMagic[emitters];
            } else {
                index++;
            }
        }
    }

    /**
     * EntityRenderer.addRainParticles for one tick: up to 100 x strength² drops on the ground within 10 blocks
     * (halved on Decreased, none on Minimal); smoke where rain lands on lava.
     *
     * @param tick the renderer tick, seeding the columns as vanilla does
     */
    public void rain(Rain world, float strength, long tick, double eyeX, double eyeY, double eyeZ) {
        if (strength <= 0f || level == Level.MINIMAL) {
            return;
        }
        int drops = (int) (100f * strength * strength);
        if (level == Level.DECREASED) {
            drops >>= 1;
        }
        rainRandom.setSeed(tick * 312987231L);
        int originX = (int) Math.floor(eyeX);
        int originY = (int) Math.floor(eyeY);
        int originZ = (int) Math.floor(eyeZ);
        for (int index = 0; index < drops; index++) {
            int x = originX + rainRandom.nextInt(10) - rainRandom.nextInt(10);
            int z = originZ + rainRandom.nextInt(10) - rainRandom.nextInt(10);
            int y = world.precipitationHeight(x, z);
            if (y > originY + 10 || y < originY - 10 || !world.rains(x, y, z)) {
                continue;
            }
            double ox = rainRandom.nextDouble();
            double oz = rainRandom.nextDouble();
            int below = world.state(x, y - 1, z) >> 4;
            if (below == 10 || below == 11) {
                system.spawn(VanillaParticles.SMOKE, x + ox, y + 0.1, z + oz, 0, 0, 0);
            } else if (below != 0) {
                system.spawn(VanillaParticles.WATER_DROP, x + ox, y + 0.1, z + oz, 0, 0, 0,
                        waterColor(x + ox, y + 0.1, z + oz), 0);
            }
        }
    }

    /** PotionHelper's liquid color for common potion damage values (the effect's color; water otherwise). */
    private static int potionColor(int damage) {
        return switch (damage & 15) {
            case 1 -> 0xCD5CAB; // regeneration
            case 2 -> 0x7CAFC6; // swiftness
            case 3 -> 0xE49A3A; // fire resistance
            case 4 -> 0x4E9331; // poison
            case 5 -> 0xF82423; // instant health
            case 6 -> 0x1F1FA1; // night vision
            case 8 -> 0x484D48; // weakness
            case 9 -> 0x932423; // strength
            case 10 -> 0x5A6C81; // slowness
            case 12 -> 0x430A09; // instant damage
            case 13 -> 0x2E5299; // water breathing
            case 14 -> 0x7F8392; // invisibility
            default -> 0x385DC6; // water
        };
    }

    private double gaussian() {
        // Marsaglia polar method on the system's seeded source.
        double u;
        double v;
        double s;
        do {
            u = random.nextDouble() * 2 - 1;
            v = random.nextDouble() * 2 - 1;
            s = u * u + v * v;
        } while (s >= 1 || s == 0);
        return u * Math.sqrt(-2 * Math.log(s) / s);
    }
}
