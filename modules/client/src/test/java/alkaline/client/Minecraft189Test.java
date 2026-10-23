package alkaline.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Minecraft189Test {
    @Test
    void descriptorMatchesTheOnlySupportedLegacyVersion() {
        assertEquals("1.8.9", Minecraft189.VERSION);
        assertEquals(47, Minecraft189.PROTOCOL);
    }
}
