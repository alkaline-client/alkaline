package alkaline.audio;

/**
 * The loudness rules of 1.8.9's SoundManager.playSound and the sound library under it (paulscode's linear
 * attenuation, which Minecraft selects for every positioned sound).
 *
 * <ul>
 *   <li>A sound fades linearly to silence over 16 blocks, or 16 times its volume when the volume exceeds 1: louder
 *   requests carry further rather than playing louder.</li>
 *   <li>The gain is the request's volume times the entry's, clamped to 0..1, times the category's volume (the
 *   master category counts as 1 there, the master volume applies to everything on top).</li>
 *   <li>The pitch is the request's times the entry's, clamped to 0.5..2.</li>
 * </ul>
 */
public final class Attenuation {
    /** SoundManager.playSound's base attenuation distance, in blocks. */
    public static final float BASE_RANGE = 16f;

    private Attenuation() {
    }

    /** Distance at which a sound requested at {@code volume} becomes silent. */
    public static float range(float volume) {
        return volume > 1f ? BASE_RANGE * volume : BASE_RANGE;
    }

    /** Linear attenuation at {@code distance} blocks for a sound reaching {@code range}: 1 at the source, 0 beyond. */
    public static float distanceGain(double distance, float range) {
        if (distance <= 0) {
            return 1f;
        }
        if (distance >= range) {
            return 0f;
        }
        return (float) (1.0 - distance / range);
    }

    /** SoundManager.getNormalizedVolume: request times entry volume, clamped to 0..1, times the category volume. */
    public static float sourceGain(float requestVolume, float entryVolume, float categoryVolume) {
        return Math.clamp(requestVolume * entryVolume, 0f, 1f) * categoryVolume;
    }

    /** SoundManager.getNormalizedPitch: request times entry pitch, clamped to 0.5..2. */
    public static float pitch(float requestPitch, float entryPitch) {
        return Math.clamp(requestPitch * entryPitch, 0.5f, 2f);
    }
}
