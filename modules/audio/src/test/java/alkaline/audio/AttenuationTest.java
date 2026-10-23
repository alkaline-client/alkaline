package alkaline.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AttenuationTest {
    @Test
    void rangeIsSixteenBlocksUpToVolumeOneThenScales() {
        assertEquals(16f, Attenuation.range(0.15f));
        assertEquals(16f, Attenuation.range(1f));
        // Records (PositionedSoundRecord.create) play at 4: heard over 64 blocks.
        assertEquals(64f, Attenuation.range(4f));
        // Ghast fireballs at 10, lightning at 10000.
        assertEquals(160f, Attenuation.range(10f));
    }

    @Test
    void gainFallsLinearlyToSilenceAtTheRange() {
        assertEquals(1f, Attenuation.distanceGain(0, 16f));
        assertEquals(0.75f, Attenuation.distanceGain(4, 16f), 1e-6f);
        assertEquals(0.5f, Attenuation.distanceGain(8, 16f), 1e-6f);
        assertEquals(0.0625f, Attenuation.distanceGain(15, 16f), 1e-6f);
        assertEquals(0f, Attenuation.distanceGain(16, 16f));
        assertEquals(0f, Attenuation.distanceGain(40, 16f));
        assertEquals(0.5f, Attenuation.distanceGain(32, 64f), 1e-6f);
    }

    @Test
    void sourceGainClampsTheProductBeforeTheCategory() {
        // A footstep: step volume 1 * 0.15, entry 1, Friendly creatures at 100%.
        assertEquals(0.15f, Attenuation.sourceGain(0.15f, 1f, 1f), 1e-6f);
        // Loud requests reach further but never play above full volume.
        assertEquals(1f, Attenuation.sourceGain(4f, 1f, 1f));
        assertEquals(0.5f, Attenuation.sourceGain(4f, 1f, 0.5f));
        // Entry volumes in sounds.json scale the request.
        assertEquals(0.25f, Attenuation.sourceGain(0.5f, 0.5f, 1f), 1e-6f);
        assertEquals(0f, Attenuation.sourceGain(1f, 1f, 0f));
    }

    @Test
    void pitchMultipliesAndClampsToHalfAndDouble() {
        assertEquals(1.2f, Attenuation.pitch(1.2f, 1f), 1e-6f);
        assertEquals(0.5f, Attenuation.pitch(0.45f, 1f));
        // random.successful_hit asks for 0.45 and plays at 0.5.
        assertEquals(2f, Attenuation.pitch(2.6f, 1f));
        assertEquals(1.5f, Attenuation.pitch(0.75f, 2f), 1e-6f);
    }
}
