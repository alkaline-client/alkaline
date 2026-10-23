package alkaline.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class VoicePolicyTest {
    @Test
    void theOldestOfTheQuieterSoundsGivesWay() {
        float[] audible = {0.9f, 0.1f, 0.05f, 0.1f};
        long[] started = {1, 5, 9, 3};
        // 0.1 and 0.05 and 0.1 are no louder than 0.2; the oldest of them started at 3.
        assertEquals(3, VoicePolicy.victim(audible, started, 4, 0.2f));
    }

    @Test
    void aSoundQuieterThanEverythingIsDropped() {
        float[] audible = {0.9f, 0.4f, 0.3f};
        long[] started = {1, 2, 3};
        assertEquals(-1, VoicePolicy.victim(audible, started, 3, 0.1f));
    }

    @Test
    void equalLoudnessFallsBackToVanillasOldestFirst() {
        float[] audible = {0.5f, 0.5f, 0.5f};
        long[] started = {7, 2, 4};
        assertEquals(1, VoicePolicy.victim(audible, started, 3, 0.5f));
    }

    @Test
    void inaudibleSoundsGoBeforeAudibleOnes() {
        // A step out of range (0) is cut for a distant sound (0), never the hit nearby.
        float[] audible = {0.8f, 0f};
        long[] started = {1, 2};
        assertEquals(1, VoicePolicy.victim(audible, started, 2, 0f));
    }
}
