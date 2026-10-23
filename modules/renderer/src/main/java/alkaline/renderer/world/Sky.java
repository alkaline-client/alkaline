package alkaline.renderer.world;

/**
 * Minecraft 1.8.9's sky, fog and celestial values for the overworld, computed the way World, WorldProvider,
 * BiomeGenBase and EntityRenderer compute them: sky color from the biome temperature and time of day, darkened
 * by rain and thunder; fog color with the sunrise tint toward the sun and its blend toward the sky color by render
 * distance; star brightness; the sunrise fan colors; sun brightness for the lightmap; and the moon phase. Colors
 * are vanilla's float RGB, which it uses as sRGB-encoded values.
 *
 * <p>Fill the inputs with {@link #update}, then read the fields. One instance per renderer; not thread-safe.
 */
public final class Sky {
    /** What fills the view where the eye is: air (linear fog), water or lava (exponential fog). */
    public enum Medium { AIR, WATER, LAVA }

    public float celestialAngle;
    /** The world's time of day in ticks and the eye's height, for custom sky layers. */
    public long worldTime;
    public double eyeY;
    /** The resource packs' custom colors and the eye's biome (id and rainfall) they are looked up for. */
    public CustomColors customColors = CustomColors.NONE;
    public int biome = 1;
    /**
     * The viewing entity's feet height, which vanilla reads the void fog from and CustomColors looks sky, fog and
     * underwater colormaps up at (posY + 1). NaN (the default) means the eye height minus 1.62.
     */
    public double feetY = Double.NaN;
    private double entityY;
    public float rainfall = 0.4F;
    private float temperature = 0.8F;
    /** Rain strength, and thunder strength already scaled by rain, of the last update. */
    public float rain;
    public float thunder;
    public final float[] skyColor = new float[3];
    public final float[] fogColor = new float[3];
    /** Sunrise or sunset fan color and alpha (index 3), or alpha 0 when none. */
    public final float[] sunrise = new float[4];
    /** Whether the sunrise fan is flipped (vanilla rotates it 180 degrees when the sun is in the other half). */
    public boolean sunriseFlipped;
    public float starBrightness;
    /** Alpha of the sun and moon: 1 minus rain strength. */
    public float celestialAlpha;
    public int moonPhase;
    /** For the lightmap: 0.2 at night .. 1 by day, reduced by rain and thunder. */
    public float sunBrightness;
    /** Fog: linear from {@code fogStart} to {@code fogEnd} (blocks, radial), or exponential with a density. */
    public Medium medium = Medium.AIR;
    public float fogStart;
    public float fogEnd;
    public float fogDensity;
    /** Ticks left of the viewer's Blindness, or -1 without it: fog closes to 5 blocks and the fog color goes dark. */
    public int blindness = -1;
    /** The viewer's Night Vision brightness (EntityRenderer.getNightVisionBrightness), 0 without it. */
    public float nightVision;
    /** Share of the render distance where linear fog starts: vanilla's 0.75, OptiFine's Fog Start option. */
    public float fogStartShare = 0.75F;
    /** OptiFine's Sky, Sun & Moon and Stars options: which parts of the sky are drawn. */
    public boolean upperSky = true;
    /**
     * Superflat worlds (level type "flat"): WorldProvider.getHorizon is 0 instead of 63 and getVoidFogYFactor 1
     * instead of 1/32, so the dark lower sky and the void fog start at the bottom of the world.
     */
    public boolean flat;
    public boolean sunMoon = true;
    public boolean stars = true;
    /** The render distance the planes and fog were computed for. */
    public int renderDistanceChunks = 8;
    /** Smoothed brightness of the eye's light level, which scales the fog color (EntityRenderer.fogColor1/2). */
    private float fogBrightness = 1.0F;
    private float previousFogBrightness = 1.0F;

    /**
     * Advances the fog brightness smoothing one game tick toward the eye's light: vanilla's {@code
     * fogColor1 += (brightness * (1 - r) + r - fogColor1) * 0.1} with r = render distance / 32.
     */
    public void tick(float eyeBrightness, int renderDistanceChunks) {
        previousFogBrightness = fogBrightness;
        float share = renderDistanceChunks / 32.0F;
        float target = eyeBrightness * (1.0F - share) + share;
        fogBrightness += (target - fogBrightness) * 0.1F;
    }

    /** World.getCloudColour: white, grayed by rain and thunder, dimmed at night; sRGB rgb into {@code out}. */
    public void cloudColor(float[] out) {
        float daylight = clamp(VanillaMath.cos(celestialAngle * (float) Math.PI * 2.0F) * 2.0F + 0.5F);
        float red = 1.0F;
        float green = 1.0F;
        float blue = 1.0F;
        if (rain > 0.0F) {
            float gray = (red * 0.3F + green * 0.59F + blue * 0.11F) * 0.6F;
            float keep = 1.0F - rain * 0.95F;
            red = red * keep + gray * (1.0F - keep);
            green = green * keep + gray * (1.0F - keep);
            blue = blue * keep + gray * (1.0F - keep);
        }
        red *= daylight * 0.9F + 0.1F;
        green *= daylight * 0.9F + 0.1F;
        blue *= daylight * 0.85F + 0.15F;
        if (thunder > 0.0F) {
            float gray = (red * 0.3F + green * 0.59F + blue * 0.11F) * 0.2F;
            float keep = 1.0F - thunder * 0.95F;
            red = red * keep + gray * (1.0F - keep);
            green = green * keep + gray * (1.0F - keep);
            blue = blue * keep + gray * (1.0F - keep);
        }
        out[0] = red;
        out[1] = green;
        out[2] = blue;
    }

    /**
     * @param worldTime       the server's time of day (ticks)
     * @param temperature     the biome temperature at the eye (vanilla's getFloatTemperature)
     * @param lookX           the view direction, normalized (for the sunrise tint in the fog)
     * @param eyeY            the eye's height (the void fog uses {@link #feetY} instead)
     */
    public void update(long worldTime, float partialTicks, float rain, float thunder, float temperature,
                       float lookX, float lookY, float lookZ, double eyeY, int renderDistanceChunks, Medium inside) {
        celestialAngle = Lightmap.celestialAngle(worldTime, partialTicks);
        this.worldTime = worldTime;
        this.eyeY = eyeY;
        entityY = Double.isNaN(feetY) ? eyeY - 1.62 : feetY;
        this.temperature = temperature;
        float cosine = VanillaMath.cos(celestialAngle * (float) Math.PI * 2.0F);
        thunder *= rain;
        this.rain = rain;
        this.thunder = thunder;
        // World.getSkyColor.
        float daylight = clamp(cosine * 2.0F + 0.5F);
        int biomeSky = skyColorByTemperature(temperature);
        float red = (biomeSky >> 16 & 255) / 255.0F * daylight;
        float green = (biomeSky >> 8 & 255) / 255.0F * daylight;
        float blue = (biomeSky & 255) / 255.0F * daylight;
        if (rain > 0.0F) {
            float gray = (red * 0.3F + green * 0.59F + blue * 0.11F) * 0.6F;
            float keep = 1.0F - rain * 0.75F;
            red = red * keep + gray * (1.0F - keep);
            green = green * keep + gray * (1.0F - keep);
            blue = blue * keep + gray * (1.0F - keep);
        }
        if (thunder > 0.0F) {
            float gray = (red * 0.3F + green * 0.59F + blue * 0.11F) * 0.2F;
            float keep = 1.0F - thunder * 0.75F;
            red = red * keep + gray * (1.0F - keep);
            green = green * keep + gray * (1.0F - keep);
            blue = blue * keep + gray * (1.0F - keep);
        }
        CustomColors.Colormap custom = customColors.sky();
        if (custom != null) {
            // CustomColors.getSkyColor: the colormap's color scaled by the vanilla sky over plains' 0.5, 0.66275, 1.
            int color = custom.color(biome, temperature, rainfall, (int) Math.floor(entityY + 1.0));
            red = (color >> 16 & 255) / 255.0F * (red / 0.5F);
            green = (color >> 8 & 255) / 255.0F * (green / 0.66275F);
            blue = (color & 255) / 255.0F * blue;
        }
        skyColor[0] = red;
        skyColor[1] = green;
        skyColor[2] = blue;
        // WorldProvider.calcSunriseSunsetColors.
        sunrise[3] = 0.0F;
        if (cosine >= -0.4F && cosine <= 0.4F) {
            float t = cosine / 0.4F * 0.5F + 0.5F;
            float alpha = 1.0F - (1.0F - VanillaMath.sin(t * (float) Math.PI)) * 0.99F;
            sunrise[0] = t * 0.3F + 0.7F;
            sunrise[1] = t * t * 0.7F + 0.2F;
            sunrise[2] = 0.2F;
            sunrise[3] = alpha * alpha;
        }
        sunriseFlipped = VanillaMath.sin(celestialAngle * (float) Math.PI * 2.0F) < 0.0F;
        // World.getStarBrightness and getSunBrightness.
        float stars = clamp(1.0F - (cosine * 2.0F + 0.25F));
        starBrightness = stars * stars * 0.5F;
        float sun = 1.0F - clamp(1.0F - (cosine * 2.0F + 0.2F));
        sun = (float) (sun * (1.0D - rain * 5.0F / 16.0D));
        sun = (float) (sun * (1.0D - thunder * 5.0F / 16.0D));
        sunBrightness = sun * 0.8F + 0.2F;
        celestialAlpha = 1.0F - rain;
        moonPhase = (int) (worldTime / 24000L % 8L + 8L) % 8;
        updateFog(partialTicks, rain, thunder, lookX, lookY, lookZ, eyeY, renderDistanceChunks, inside, daylight);
    }

    /** EntityRenderer.updateFogColor and setupFog (the void fog from the entity's feet height, not its eye). */
    private void updateFog(float partialTicks, float rain, float thunder, float lookX, float lookY, float lookZ,
                           double eyeY, int renderDistanceChunks, Medium inside, float ignoredDaylight) {
        float blend = 1.0F - (float) Math.pow(0.25F + 0.75F * renderDistanceChunks / 32.0F, 0.25D);
        float cosine = VanillaMath.cos(celestialAngle * (float) Math.PI * 2.0F);
        float fog = clamp(cosine * 2.0F + 0.5F);
        float red = 0.7529412F * (fog * 0.94F + 0.06F);
        float green = 0.84705883F * (fog * 0.94F + 0.06F);
        float blue = 1.0F * (fog * 0.91F + 0.09F);
        CustomColors.Colormap customFog = customColors.fog();
        if (customFog != null) {
            // CustomColors.getFogColor: the colormap's color scaled by the vanilla fog over its 0.753, 0.8471, 1.
            int color = customFog.color(biome, temperature, rainfall, (int) Math.floor(entityY + 1.0));
            red = (color >> 16 & 255) / 255.0F * (red / 0.753F);
            green = (color >> 8 & 255) / 255.0F * (green / 0.8471F);
            blue = (color & 255) / 255.0F * blue;
        }
        if (renderDistanceChunks >= 4 && sunrise[3] > 0.0F) {
            // Looking toward the sun (or the setting sun) tints the fog with the sunrise color.
            float facing = sunriseFlipped ? lookX : -lookX;
            if (facing > 0.0F) {
                facing *= sunrise[3];
                red = red * (1.0F - facing) + sunrise[0] * facing;
                green = green * (1.0F - facing) + sunrise[1] * facing;
                blue = blue * (1.0F - facing) + sunrise[2] * facing;
            }
        }
        red += (skyColor[0] - red) * blend;
        green += (skyColor[1] - green) * blend;
        blue += (skyColor[2] - blue) * blend;
        if (rain > 0.0F) {
            float dim = 1.0F - rain * 0.5F;
            red *= dim;
            green *= dim;
            blue *= 1.0F - rain * 0.4F;
        }
        if (thunder > 0.0F) {
            float dim = 1.0F - thunder * 0.5F;
            red *= dim;
            green *= dim;
            blue *= dim;
        }
        medium = inside;
        if (inside == Medium.WATER) {
            red = 0.02F;
            green = 0.02F;
            blue = 0.2F;
            CustomColors.Colormap customWater = customColors.underwater();
            if (customWater != null) {
                int color = customWater.color(biome, temperature, rainfall, (int) Math.floor(entityY + 1.0));
                red = (color >> 16 & 255) / 255.0F;
                green = (color >> 8 & 255) / 255.0F;
                blue = (color & 255) / 255.0F;
            }
        } else if (inside == Medium.LAVA) {
            red = 0.6F;
            green = 0.1F;
            blue = 0.0F;
        }
        float brightness = previousFogBrightness + (fogBrightness - previousFogBrightness) * partialTicks;
        red *= brightness;
        green *= brightness;
        blue *= brightness;
        double voidFactor = entityY * (flat ? 1.0D : 0.03125D);
        if (blindness >= 0) {
            voidFactor = blindness < 20 ? voidFactor * (1.0F - blindness / 20.0F) : 0.0D;
        }
        if (voidFactor < 1.0D) {
            voidFactor = Math.max(0.0D, voidFactor);
            voidFactor *= voidFactor;
            red = (float) (red * voidFactor);
            green = (float) (green * voidFactor);
            blue = (float) (blue * voidFactor);
        }
        if (nightVision > 0.0F) {
            float scale = Math.min(1.0F / red, Math.min(1.0F / green, 1.0F / blue));
            red = red * (1.0F - nightVision) + red * scale * nightVision;
            green = green * (1.0F - nightVision) + green * scale * nightVision;
            blue = blue * (1.0F - nightVision) + blue * scale * nightVision;
        }
        fogColor[0] = red;
        fogColor[1] = green;
        fogColor[2] = blue;
        this.renderDistanceChunks = renderDistanceChunks;
        float far = renderDistanceChunks * 16.0F;
        fogStart = far * fogStartShare;
        fogEnd = far;
        fogDensity = inside == Medium.WATER ? 0.1F : inside == Medium.LAVA ? 2.0F : 0.0F;
        if (blindness >= 0) {
            // EntityRenderer.setupFog: Blindness wins over every other fog, linear to 5 blocks (fading in).
            float reach = blindness < 20 ? 5.0F + (far - 5.0F) * (1.0F - blindness / 20.0F) : 5.0F;
            fogStart = reach * 0.25F;
            fogEnd = reach;
            fogDensity = 0.0F;
        }
    }

    /** BiomeGenBase.getSkyColorByTemp. */
    public static int skyColorByTemperature(float temperature) {
        float t = Math.max(-1.0F, Math.min(1.0F, temperature / 3.0F));
        return hsvToRgb(0.62222224F - t * 0.05F, 0.5F + t * 0.1F, 1.0F);
    }

    /** MathHelper.func_181758_c (HSV to RGB, as 0xRRGGBB). */
    static int hsvToRgb(float hue, float saturation, float value) {
        int sector = (int) (hue * 6.0F) % 6;
        float fraction = hue * 6.0F - sector;
        float p = value * (1.0F - saturation);
        float q = value * (1.0F - fraction * saturation);
        float t = value * (1.0F - (1.0F - fraction) * saturation);
        float r;
        float g;
        float b;
        switch (sector) {
            case 0 -> {
                r = value;
                g = t;
                b = p;
            }
            case 1 -> {
                r = q;
                g = value;
                b = p;
            }
            case 2 -> {
                r = p;
                g = value;
                b = t;
            }
            case 3 -> {
                r = p;
                g = q;
                b = value;
            }
            case 4 -> {
                r = t;
                g = p;
                b = value;
            }
            default -> {
                r = value;
                g = p;
                b = q;
            }
        }
        return clampByte((int) (r * 255.0F)) << 16 | clampByte((int) (g * 255.0F)) << 8 | clampByte((int) (b * 255.0F));
    }

    /**
     * World.calculateSkylightSubtracted: how many levels the sky light loses at this time and weather (0 by day,
     * 11 at midnight).
     */
    public static int skylightSubtracted(long worldTime, float rain, float thunder) {
        float angle = Lightmap.celestialAngle(worldTime, 1.0F);
        float level = 1.0F - clamp(1.0F - (VanillaMath.cos(angle * (float) Math.PI * 2.0F) * 2.0F + 0.5F));
        level = (float) (level * (1.0D - rain * 5.0F / 16.0D));
        level = (float) (level * (1.0D - thunder * rain * 5.0F / 16.0D));
        return (int) ((1.0F - level) * 11.0F);
    }

    private static float clamp(float value) {
        return Math.max(0.0F, Math.min(1.0F, value));
    }

    private static int clampByte(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
