package alkaline.audio;

/**
 * Which playing sound gives way when every voice is busy. Vanilla's sound library steals voices in the order
 * they started, so a new sound always plays and the oldest is cut. Alkaline keeps that order but spares what the
 * player can hear: only sounds no louder at the listener than the new one may be cut, and among those the oldest
 * goes. When every playing sound is louder, the new one is dropped instead, so a distant step never cuts a hit.
 */
final class VoicePolicy {
    private VoicePolicy() {
    }

    /**
     * The voice to stop for a new sound of audible gain {@code incoming}, or -1 to drop the new sound.
     *
     * @param audible each busy voice's gain at the listener now
     * @param started each busy voice's start order (smaller is older)
     */
    static int victim(float[] audible, long[] started, int count, float incoming) {
        int victim = -1;
        for (int voice = 0; voice < count; voice++) {
            if (audible[voice] <= incoming && (victim < 0 || started[voice] < started[victim])) {
                victim = voice;
            }
        }
        return victim;
    }
}
