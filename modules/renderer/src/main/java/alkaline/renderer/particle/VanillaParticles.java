package alkaline.renderer.particle;

import alkaline.renderer.particle.ParticleType.Frames;
import alkaline.renderer.particle.ParticleType.Launch;
import alkaline.renderer.particle.ParticleType.Sheet;
import alkaline.renderer.particle.ParticleType.SizeCurve;

/**
 * Minecraft 1.8.9's particles (EnumParticleTypes, ids 0-41) as {@link ParticleType} values: their texture cells,
 * lifetimes, launches, gravity and drag from the EntityFX subclasses. Behaviors vanilla scripts per class (the
 * portal's pull, the lava pop's smoke trail) are approximated by the same parameters; ids without a drawable
 * look of their own (footsteps, item pickup, the elder guardian's face, barriers) have no type.
 */
public final class VanillaParticles {
    public static final String SHEET = "assets/minecraft/textures/particle/particles.png";
    public static final String EXPLOSION_SHEET = "assets/minecraft/textures/entity/explosion.png";

    public static final ParticleType EXPLODE = ParticleType.builder("explode")
            .cells(0, 8, Frames.AGE_REVERSED).life(16f, 0.2f, 0.8f, 2).launch(Launch.SPREAD_ADD, 0.125f, 1f)
            .gravity(-0.004f).drag(0.9f).scale(0.5f, 3.5f).gray(0.7f, 1f).build();
    public static final ParticleType LARGE_EXPLODE = ParticleType.builder("largeexplode").sheet(Sheet.EXPLOSION)
            .cells(0, 16, Frames.AGE).life(6f, 1f, 0f, 3).launch(Launch.GIVEN, 0f, 0f).drag(1f)
            .scale(10f, 10f).gray(0.4f, 1f).fullBright().noClip().build();
    public static final ParticleType FIREWORKS_SPARK = ParticleType.builder("fireworksSpark")
            .cells(160, 8, Frames.AGE_REVERSED).life(48f, 1f, 0.5f, 0).launch(Launch.GIVEN, 1f, 0f)
            .gravity(0.004f).drag(0.91f).scale(0.75f, 0.75f).fullBright().build();
    public static final ParticleType BUBBLE = ParticleType.builder("bubble").cell(32)
            .life(8f, 0.2f, 0.8f, 0).launch(Launch.GIVEN, 0.2f, 0f).gravity(-0.002f).drag(0.85f).scale(0.2f, 0.8f)
            .needsWater().build();
    public static final ParticleType SPLASH = ParticleType.builder("splash").cells(19, 4, Frames.RANDOM)
            .life(8f, 0.2f, 0.8f, 0).launch(Launch.SPREAD_ADD, 0.3f, 1f).gravity(0.04f).groundDeath(0.5f).build();
    public static final ParticleType WAKE = ParticleType.builder("wake").cells(19, 4, Frames.RANDOM)
            .life(8f, 0.2f, 0.8f, 0).launch(Launch.GIVEN, 1f, 0f).drag(0.98f).build();
    public static final ParticleType SUSPENDED = ParticleType.builder("suspended").cell(0)
            .life(16f, 0.2f, 0.8f, 0).launch(Launch.GIVEN, 0f, 0f).drag(1f).scale(0.2f, 0.8f)
            .tint(0xFF6666B2).noClip().needsWater().build();
    public static final ParticleType DEPTH_SUSPEND = ParticleType.builder("depthsuspend").cell(0)
            .life(16f, 0.2f, 0.8f, 0).launch(Launch.GIVEN, 0.02f, 0f).drag(0.99f).scale(0.1f, 0.3f)
            .gray(0.65f, 0.85f).noClip().build();
    public static final ParticleType CRIT = ParticleType.builder("crit").cell(65)
            .life(6f, 0.6f, 0.8f, 0).launch(Launch.SPREAD_ADD, 0.1f, 0.4f).gravity(0.02f).drag(0.7f)
            .scale(0.75f, 0.75f).size(SizeCurve.POP_IN).gray(0.6f, 0.9f).build();
    public static final ParticleType MAGIC_CRIT = ParticleType.builder("magicCrit").cell(66)
            .life(6f, 0.6f, 0.8f, 0).launch(Launch.SPREAD_ADD, 0.1f, 0.4f).gravity(0.02f).drag(0.7f)
            .scale(0.75f, 0.75f).size(SizeCurve.POP_IN).gray(0.6f, 0.9f).tint(0xFF4DCCFF).build();
    public static final ParticleType SMOKE = ParticleType.builder("smoke")
            .cells(0, 8, Frames.AGE_REVERSED).life(8f, 0.2f, 0.8f, 0).launch(Launch.SPREAD_ADD, 0.1f, 1f)
            .gravity(-0.004f).drag(0.96f).scale(0.75f, 0.75f).size(SizeCurve.POP_IN).gray(0f, 0.3f).build();
    public static final ParticleType LARGE_SMOKE = ParticleType.builder("largesmoke")
            .cells(0, 8, Frames.AGE_REVERSED).life(20f, 0.2f, 0.8f, 0).launch(Launch.SPREAD_ADD, 0.1f, 1f)
            .gravity(-0.004f).drag(0.96f).scale(1.875f, 1.875f).size(SizeCurve.POP_IN).gray(0f, 0.3f).build();
    public static final ParticleType SPELL = spell("spell", 128, 0xFFFFFFFF);
    public static final ParticleType INSTANT_SPELL = spell("instantSpell", 144, 0xFFFFFFFF);
    /** Mob spell colors come from the spawn velocity (the potion color); see {@link #colorFromVelocity}. */
    public static final ParticleType MOB_SPELL = spell("mobSpell", 128, 0xFFFFFFFF);
    public static final ParticleType MOB_SPELL_AMBIENT = spell("mobSpellAmbient", 128, 0x26FFFFFF);
    public static final ParticleType WITCH_MAGIC = spell("witchMagic", 144, 0xFFE666FF);
    public static final ParticleType DRIP_WATER = ParticleType.builder("dripWater").cell(113)
            .life(64f, 0.8f, 0.4f, 0).launch(Launch.GIVEN, 0f, 0f).gravity(0.024f).drag(0.98f).tint(0xFF3333FF)
            .groundDeath(1f).build();
    public static final ParticleType DRIP_LAVA = ParticleType.builder("dripLava").cell(113)
            .life(64f, 0.8f, 0.4f, 0).launch(Launch.GIVEN, 0f, 0f).gravity(0.024f).drag(0.98f).tint(0xFFFF4D0F)
            .fullBright().groundDeath(1f).build();
    public static final ParticleType ANGRY_VILLAGER = ParticleType.builder("angryVillager").cell(81)
            .life(16f, 1f, 0f, 0).launch(Launch.SPREAD_ADD, 0.01f, 0f).gravity(-0.01f).drag(0.86f)
            .scale(1.5f, 1.5f).size(SizeCurve.POP_IN).build();
    public static final ParticleType HAPPY_VILLAGER = ParticleType.builder("happyVillager").cell(82)
            .life(16f, 0.2f, 0.8f, 0).launch(Launch.GIVEN, 0.02f, 0f).drag(0.99f).scale(0.2f, 0.8f).build();
    public static final ParticleType TOWN_AURA = ParticleType.builder("townaura").cell(0)
            .life(16f, 0.2f, 0.8f, 0).launch(Launch.GIVEN, 0.02f, 0f).drag(0.99f).scale(0.1f, 0.3f)
            .gray(0.65f, 0.85f).build();
    /** Note colors come from the spawn velocity's x (the pitch, 0..1); see {@link #noteColor}. */
    public static final ParticleType NOTE = ParticleType.builder("note").cell(64)
            .life(6f, 1f, 0f, 0).launch(Launch.SPREAD_ADD, 0.01f, 0f).gravity(-0.2f * 0.34f).drag(0.66f)
            .scale(1.5f, 1.5f).size(SizeCurve.POP_IN).build();
    public static final ParticleType PORTAL = ParticleType.builder("portal").cells(0, 8, Frames.RANDOM)
            .life(40f, 1f, 0.25f, 0).launch(Launch.GIVEN, -0.025f, 0f).drag(1f).scale(0.25f, 0.35f)
            .size(SizeCurve.FADE_OUT).gray(0.4f, 1f).tint(0xFFE54DFF).noClip().build();
    public static final ParticleType ENCHANTMENT = ParticleType.builder("enchantmenttable")
            .cells(225, 26, Frames.RANDOM).life(30f, 1f, 0.33f, 0).launch(Launch.GIVEN, -0.03f, 0f).drag(1f)
            .scale(0.1f, 0.25f).size(SizeCurve.FADE_OUT).gray(0.6f, 1f).tint(0xFFE6E6FF).noClip().build();
    public static final ParticleType FLAME = ParticleType.builder("flame").cell(48)
            .life(8f, 0.2f, 0.8f, 4).launch(Launch.SPREAD_ADD, 0.01f, 1f).drag(0.96f).size(SizeCurve.SHRINK)
            .fullBright().noClip().build();
    public static final ParticleType LAVA = ParticleType.builder("lava").cell(49)
            .life(16f, 0.2f, 0.8f, 0).launch(Launch.SPREAD_ADD, 0.8f, 0f).gravity(0.03f).drag(0.999f)
            .scale(0.2f, 2.2f).size(SizeCurve.SHRINK).fullBright().build();
    public static final ParticleType CLOUD = ParticleType.builder("cloud")
            .cells(0, 8, Frames.AGE_REVERSED).life(8f, 0.3f, 0.8f, 0).launch(Launch.SPREAD_ADD, 0.1f, 1f)
            .gravity(-0.004f).drag(0.96f).scale(1.875f, 1.875f).size(SizeCurve.POP_IN).gray(0.7f, 1f).build();
    /** Redstone dust colors come from the spawn velocity; see {@link #colorFromVelocity}. */
    public static final ParticleType REDSTONE = ParticleType.builder("reddust")
            .cells(0, 8, Frames.AGE_REVERSED).life(8f, 0.2f, 0.8f, 0).launch(Launch.SPREAD_ADD, 0.1f, 0f)
            .drag(0.96f).scale(0.75f, 0.75f).size(SizeCurve.POP_IN).gray(0.8f, 1f).tint(0xFFFF2020).build();
    public static final ParticleType SNOW_SHOVEL = ParticleType.builder("snowshovel")
            .cells(0, 8, Frames.AGE_REVERSED).life(8f, 0.2f, 0.8f, 0).launch(Launch.SPREAD_ADD, 0.1f, 1f)
            .gravity(0.03f).drag(0.99f).scale(0.75f, 0.75f).size(SizeCurve.POP_IN).gray(0.7f, 1f).build();
    public static final ParticleType HEART = ParticleType.builder("heart").cell(80)
            .life(16f, 1f, 0f, 0).launch(Launch.SPREAD_ADD, 0.01f, 0f).gravity(-0.1f * 0.14f).drag(0.86f)
            .scale(1.5f, 1.5f).size(SizeCurve.POP_IN).build();
    /** Block and item fragments (blockcrack, blockdust, iconcrack, snowball and slime poofs) on the block sheet. */
    public static final ParticleType BLOCK_CRACK = ParticleType.builder("blockcrack").sheet(Sheet.BLOCKS).cell(0)
            .life(4f, 0.1f, 0.9f, 0).launch(Launch.SPREAD, 1f, 0f).gravity(0.04f).gray(0.6f, 0.6f).build();
    public static final ParticleType BLOCK_DUST = ParticleType.builder("blockdust").sheet(Sheet.BLOCKS).cell(0)
            .life(4f, 0.1f, 0.9f, 0).launch(Launch.GIVEN, 1f, 0f).gravity(0.04f).gray(0.6f, 0.6f).build();
    public static final ParticleType WATER_DROP = ParticleType.builder("droplet").cells(19, 4, Frames.RANDOM)
            .life(8f, 0.2f, 0.8f, 0).launch(Launch.SPREAD_ADD, 0.3f, 0f).gravity(0.06f).groundDeath(0.5f).build();

    /** A potion splash's spell particles (RenderGlobal's Effect 2002), thrown out along their given velocity. */
    public static final ParticleType POTION_SPLASH = ParticleType.builder("potionSplash")
            .cells(128, 8, Frames.AGE_REVERSED).life(8f, 0.2f, 0.8f, 0).launch(Launch.GIVEN, 1f, 0f)
            .gravity(-0.004f).drag(0.96f).scale(0.75f, 0.75f).size(SizeCurve.POP_IN).build();

    private static final ParticleType[] BY_ID = {
            EXPLODE, LARGE_EXPLODE, LARGE_EXPLODE, FIREWORKS_SPARK, BUBBLE, SPLASH, WAKE, SUSPENDED, DEPTH_SUSPEND,
            CRIT, MAGIC_CRIT, SMOKE, LARGE_SMOKE, SPELL, INSTANT_SPELL, MOB_SPELL, MOB_SPELL_AMBIENT, WITCH_MAGIC,
            DRIP_WATER, DRIP_LAVA, ANGRY_VILLAGER, HAPPY_VILLAGER, TOWN_AURA, NOTE, PORTAL, ENCHANTMENT, FLAME, LAVA,
            null, CLOUD, REDSTONE, SNOW_SHOVEL, SNOW_SHOVEL, SNOW_SHOVEL, HEART, null, BLOCK_CRACK, BLOCK_CRACK,
            BLOCK_DUST, WATER_DROP, null, null,
    };

    private VanillaParticles() {
    }

    /** The type for an EnumParticleTypes id, or null for ids without a look of their own (or unknown ids). */
    public static ParticleType byId(int id) {
        return id >= 0 && id < BY_ID.length ? BY_ID[id] : null;
    }

    /** Whether the id's spawn velocity is a color (mob spells and redstone dust take an RGB triple there). */
    public static boolean colorFromVelocity(int id) {
        return id == 15 || id == 16 || id == 30;
    }

    /** EntityNoteFX's color for a pitch in 0..1 (the spawn velocity's x). */
    public static int noteColor(double pitch) {
        float red = (float) Math.sin((pitch + 0.0) * Math.PI * 2.0) * 0.65f + 0.35f;
        float green = (float) Math.sin((pitch + 0.33333334) * Math.PI * 2.0) * 0.65f + 0.35f;
        float blue = (float) Math.sin((pitch + 0.6666667) * Math.PI * 2.0) * 0.65f + 0.35f;
        return argb(red, green, blue, 1f);
    }

    public static int argb(float red, float green, float blue, float alpha) {
        return channel(alpha) << 24 | channel(red) << 16 | channel(green) << 8 | channel(blue);
    }

    private static int channel(float value) {
        return Math.round(Math.max(0f, Math.min(1f, value)) * 255f);
    }

    /** EntitySpellParticleFX: rising, slowed, eight frames from {@code base} backwards. */
    private static ParticleType spell(String id, int base, int tint) {
        return ParticleType.builder(id).cells(base, 8, Frames.AGE_REVERSED).life(8f, 0.2f, 0.8f, 0)
                .launch(Launch.SPREAD_ADD, 0.2f, 0f).gravity(-0.004f).drag(0.96f).scale(0.75f, 0.75f)
                .size(SizeCurve.POP_IN).tint(tint).build();
    }
}
