package alkaline.renderer.world;

/**
 * The three 1.8.9 dimensions as their WorldProvider draws them: the celestial angle, the light level brightness
 * table, the base fog color, and whether there is a surface sky with clouds (isSurfaceWorld).
 */
public enum Dimension {
    OVERWORLD,
    /** WorldProviderHell: midnight forever, a light table lifted to 0.1, red fog close to the eye. */
    NETHER,
    /** WorldProviderEnd: noon forever, its own lightmap, the end sky box and dim purple fog close to the eye. */
    END;

    /** The dimension of a JoinGame or Respawn id: -1 the Nether, 1 the End, anything else the Overworld. */
    public static Dimension of(int id) {
        return id == -1 ? NETHER : id == 1 ? END : OVERWORLD;
    }

    /** WorldProvider.calculateCelestialAngle. */
    public float celestialAngle(long worldTime, float partialTicks) {
        return switch (this) {
            case OVERWORLD -> Lightmap.celestialAngle(worldTime, partialTicks);
            case NETHER -> 0.5F;
            case END -> 0.0F;
        };
    }

    /** WorldProvider.lightBrightnessTable: the Nether's has a floor of 0.1, the others none. */
    public float levelBrightness(int level) {
        float brightness = Lightmap.levelBrightness(level);
        return this == NETHER ? brightness * 0.9F + 0.1F : brightness;
    }

    /** isSurfaceWorld: the sky planes, sun, moon, stars and clouds are drawn. */
    public boolean surface() {
        return this == OVERWORLD;
    }
}
