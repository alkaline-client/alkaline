package alkaline.renderer.particle;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParticleSystemTest {
    private static final ParticleType STILL = ParticleType.builder("test:still").cell(48).life(4f, 1f, 0f, 0)
            .launch(ParticleType.Launch.GIVEN, 1f, 0f).drag(1f).build();

    @Test
    void particlesExpireAfterTheirLifetime() {
        ParticleSystem system = new ParticleSystem(8, 1);
        assertTrue(system.spawn(STILL, 0, 0, 0, 0, 0, 0));
        for (int tick = 0; tick < 4; tick++) {
            system.tick();
            assertEquals(1, system.count());
        }
        system.tick();
        assertEquals(0, system.count());
    }

    @Test
    void limitBoundsLiveParticles() {
        ParticleSystem system = new ParticleSystem(8, 1);
        system.limit(3);
        for (int index = 0; index < 5; index++) {
            system.spawn(STILL, 0, 0, 0, 0, 0, 0);
        }
        assertEquals(3, system.count());
        assertFalse(system.spawn(STILL, 0, 0, 0, 0, 0, 0));
    }

    @Test
    void gravityDragAndFloorFollowVanillaOrder() {
        ParticleType falling = ParticleType.builder("test:falling").cell(0).life(100f, 1f, 0f, 0)
                .launch(ParticleType.Launch.GIVEN, 1f, 0f).gravity(0.04f).drag(0.98f).build();
        ParticleSystem system = new ParticleSystem(4, 1);
        // Solid ground fills y < -1: the top of block -2 at y = -1 stops the particle.
        system.world(new ParticleSystem.World() {
            @Override public boolean solid(int x, int y, int z) { return y < -1; }
            @Override public boolean water(int x, int y, int z) { return false; }
            @Override public int light(int x, int y, int z) { return ParticleSystem.FULL_LIGHT; }
        });
        system.spawn(falling, 0, 0, 0, 0, 0, 0);
        system.tick();
        ByteBuffer out = ByteBuffer.allocate(ParticleSystem.INSTANCE_BYTES).order(ByteOrder.nativeOrder());
        system.writeInstances(out, 0, 1, 1f, 0, 0, 0, ParticleType.Sheet.PARTICLES, false, null);
        assertEquals(-0.04f, out.getFloat(4), 1e-6f);
        for (int tick = 0; tick < 20; tick++) {
            system.tick();
        }
        system.writeInstances(out, 0, 1, 1f, 0, 0, 0, ParticleType.Sheet.PARTICLES, false, null);
        // Falling keeps accelerating until the ground at y = -1 stops it.
        assertEquals(-1f, out.getFloat(4), 1e-6f);
    }

    @Test
    void instancesInterpolateAndUseTheVanillaCellInset() {
        ParticleSystem system = new ParticleSystem(4, 1);
        system.spawn(STILL, 1, 2, 3, 0, 0, 0);
        ByteBuffer out = ByteBuffer.allocate(64).order(ByteOrder.nativeOrder());
        assertEquals(1, system.writeInstances(out, 0, 4, 0.5f, 1, 2, 3, ParticleType.Sheet.PARTICLES, false, null));
        assertEquals(0f, out.getFloat(0));
        int uvMin = out.getInt(16);
        int uvMax = out.getInt(20);
        // Cell 48 is column 0, row 3.
        assertEquals(0, uvMin & 0xFFFF);
        assertEquals(Math.round(3 / 16f * 65535f), uvMin >>> 16);
        assertEquals(Math.round(0.0624375f * 65535f), uvMax & 0xFFFF);
        assertEquals(0, system.writeInstances(out, 0, 4, 0.5f, 0, 0, 0, ParticleType.Sheet.PARTICLES, true, null));
        assertEquals(0, system.writeInstances(out, 0, 4, 0.5f, 0, 0, 0, ParticleType.Sheet.BLOCKS, false, null));
    }
}
