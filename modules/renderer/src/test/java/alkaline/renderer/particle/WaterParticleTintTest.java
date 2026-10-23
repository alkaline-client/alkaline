package alkaline.renderer.particle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

/** Water particle colors against RenderGlobal.doSpawnParticle's CustomColors.updateWaterFX calls (OptiFine). */
class WaterParticleTintTest {
    private static final int BUBBLE = 4;
    private static final int SPLASH = 5;
    private static final int WAKE = 6;
    private static final int WATER_DROP = 39;
    private static final int PACK_TINT = 0x336699;

    private final ParticleSystem system = new ParticleSystem(256, 1);
    private final ParticleEffects effects = new ParticleEffects(system, new ParticleEffects.Blocks() {
        @Override
        public int particleLayer(int state) {
            return 0;
        }

        @Override
        public int particleTint(int state) {
            return 0xFFFFFF;
        }
    });
    private int asked;

    /** The RGB (0xRRGGBB) of the particle at {@code index}, as the instance buffer carries it. */
    private int rgb(int index) {
        ByteBuffer out = ByteBuffer.allocate(ParticleSystem.INSTANCE_BYTES * 256).order(ByteOrder.nativeOrder());
        system.writeInstances(out, 0, 256, 1f, 0, 0, 0, ParticleType.Sheet.PARTICLES, false, null);
        int rgba = out.getInt(index * ParticleSystem.INSTANCE_BYTES + 24);
        return (rgba & 0xFF) << 16 | (rgba >>> 8 & 0xFF) << 8 | rgba >>> 16 & 0xFF;
    }

    private void tinted() {
        effects.water((x, y, z) -> {
            asked++;
            return PACK_TINT;
        });
    }

    @Test
    void bubblesSplashesAndRainDropsTakeThePackColorAtSpawn() {
        tinted();
        effects.spawn(BUBBLE, true, 1, 2, 3, 0, 0, 0);
        effects.spawn(SPLASH, true, 1, 2, 3, 0, 0, 0);
        effects.spawn(WATER_DROP, true, 1, 2, 3, 0, 0, 0);
        assertEquals(3, system.count());
        assertEquals(PACK_TINT, rgb(0));
        assertEquals(PACK_TINT, rgb(1));
        assertEquals(PACK_TINT, rgb(2));
        assertEquals(3, asked);
    }

    @Test
    void particlesOptiFineLeavesAloneKeepTheirOwnColor() {
        tinted();
        effects.spawn(WAKE, true, 1, 2, 3, 0, 0, 0);
        assertEquals(1, system.count());
        assertEquals(0xFFFFFF, rgb(0));
        assertEquals(0, asked);
    }

    @Test
    void withoutAPackColorTheTypeKeepsItsColor() {
        effects.spawn(SPLASH, true, 1, 2, 3, 0, 0, 0);
        assertEquals(0xFFFFFF, rgb(0));
    }

    @Test
    void rainDropsTakeThePackColorToo() {
        tinted();
        effects.rain(new ParticleEffects.Rain() {
            @Override
            public int precipitationHeight(int x, int z) {
                return 64;
            }

            @Override
            public boolean rains(int x, int y, int z) {
                return true;
            }

            @Override
            public int state(int x, int y, int z) {
                return 1 << 4;
            }
        }, 1f, 7L, 0, 64, 0);
        assertTrue(system.count() > 0);
        for (int index = 0; index < system.count(); index++) {
            assertEquals(PACK_TINT, rgb(index));
        }
        assertEquals(system.count(), asked);
    }
}
