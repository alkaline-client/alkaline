package alkaline.renderer.particle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

/** EffectRenderer.addBlockHitEffects: debris from the face being dug. */
class BlockHitParticlesTest {
    private static final int STONE = 1 << 4;
    private final ParticleSystem system = new ParticleSystem(128, 7);
    private final ParticleEffects effects = new ParticleEffects(system, new ParticleEffects.Blocks() {
        @Override
        public int particleLayer(int state) {
            return state == 0 ? -1 : 5;
        }

        @Override
        public int particleTint(int state) {
            return 0xFFFFFF;
        }
    });

    private float[][] hits(int face, int count) {
        system.clear();
        for (int index = 0; index < count; index++) {
            effects.blockHit(10, 64, -3, STONE, face);
        }
        ByteBuffer out = ByteBuffer.allocate(ParticleSystem.INSTANCE_BYTES * count).order(ByteOrder.nativeOrder());
        assertEquals(count, system.writeInstances(out, 0, count, 0f, 0, 0, 0, ParticleType.Sheet.BLOCKS, false, null));
        float[][] positions = new float[count][];
        for (int index = 0; index < count; index++) {
            int at = index * ParticleSystem.INSTANCE_BYTES;
            positions[index] = new float[]{out.getFloat(at), out.getFloat(at + 4), out.getFloat(at + 8)};
        }
        return positions;
    }

    @Test
    void debrisAppearsATenthOfABlockOutsideTheDugFace() {
        for (float[] at : hits(1, 20)) { // top
            assertEquals(65.1f, at[1], 1e-4f);
            assertTrue(at[0] >= 10.1f && at[0] <= 10.9f, "x " + at[0]);
            assertTrue(at[2] >= -2.9f && at[2] <= -2.1f, "z " + at[2]);
        }
    }

    @Test
    void eachFaceOffsetsItsOwnAxis() {
        assertEquals(63.9f, hits(0, 1)[0][1], 1e-4f);
        assertEquals(-3.1f, hits(2, 2)[1][2], 1e-4f);
        assertEquals(-1.9f, hits(3, 3)[2][2], 1e-4f);
        assertEquals(10.0f - 0.1f, hits(4, 4)[3][0], 1e-4f);
        assertEquals(11.1f, hits(5, 5)[4][0], 1e-4f);
    }

    @Test
    void airMakesNoDebris() {
        effects.blockHit(0, 0, 0, 0, 1);
        assertEquals(0, system.count());
    }

    @Test
    void hitDebrisIsSlowAndSmallerThanBreakDebris() {
        system.clear();
        effects.blockHit(0, 0, 0, STONE, 1);
        effects.blockDestroyed(0, 0, 0, STONE);
        assertEquals(1 + 64, system.count());
        ByteBuffer out = ByteBuffer.allocate(ParticleSystem.INSTANCE_BYTES * 65).order(ByteOrder.nativeOrder());
        system.writeInstances(out, 0, 65, 0f, 0, 0, 0, ParticleType.Sheet.BLOCKS, false, null);
        float hit = out.getFloat(12);
        float smallestBreak = Float.MAX_VALUE;
        float largestBreak = 0f;
        for (int index = 1; index < 65; index++) {
            float scale = out.getFloat(index * ParticleSystem.INSTANCE_BYTES + 12);
            smallestBreak = Math.min(smallestBreak, scale);
            largestBreak = Math.max(largestBreak, scale);
        }
        // Break debris is 0.05..0.1 blocks (a random 1..2, times 0.1, halved for digging particles); a hit is 0.6 of it.
        assertTrue(hit >= 0.03f - 1e-6f && hit <= 0.06f + 1e-6f, "hit " + hit);
        assertTrue(largestBreak <= 0.1f + 1e-6f && smallestBreak >= 0.05f - 1e-6f);
    }

    @Test
    void shrinkLastScalesMotionAboutTheUpwardKickAndSize() {
        ParticleType given = ParticleType.builder("test:given").sheet(ParticleType.Sheet.BLOCKS).cell(0)
                .life(100f, 1f, 0f, 0).launch(ParticleType.Launch.GIVEN, 1f, 0f).drag(1f).build();
        system.spawn(given, 0, 0, 0, 1.0, 0.5, -1.0);
        system.shrinkLast(0.2f, 0.6f);
        system.tick();
        ByteBuffer out = ByteBuffer.allocate(ParticleSystem.INSTANCE_BYTES).order(ByteOrder.nativeOrder());
        system.writeInstances(out, 0, 1, 1f, 0, 0, 0, ParticleType.Sheet.BLOCKS, false, null);
        assertEquals(0.2f, out.getFloat(0), 1e-5f);
        assertEquals((0.5f - 0.1f) * 0.2f + 0.1f, out.getFloat(4), 1e-5f);
        assertEquals(-0.2f, out.getFloat(8), 1e-5f);
    }
}
