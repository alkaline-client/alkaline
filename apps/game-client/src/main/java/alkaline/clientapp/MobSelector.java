package alkaline.clientapp;

import alkaline.game.entity.Entity;
import alkaline.game.entity.EntityTypes;
import alkaline.renderer.model.EntityModels;

/**
 * Picks the model, texture and per-mob render settings for the mobs {@link EntityView}'s main switch does not
 * name, reading the variant from the entity's metadata the way each 1.8.9 {@code Render*} class does. Writes into
 * a reused {@link Choice}; nothing allocates per call.
 */
final class MobSelector {
    /** What to draw for one mob. */
    static final class Choice {
        EntityModels.Kind kind;
        /** Texture path under {@code textures/}. */
        String texture;
        /** The renderer's preRenderCallback scale. */
        float scale;
        /** Blocks added to the feet height (RenderBat's bob). */
        float lift;
        /** RendererLivingEntity.getDeathMaxRotation. */
        float deathRotation;
        /** Biped mobs also draw armor and a held item. */
        boolean biped;
        boolean zombieArms;

        void reset() {
            kind = null;
            texture = null;
            scale = 1f;
            lift = 0f;
            deathRotation = 90f;
            biped = zombieArms = false;
        }
    }

    private static final String[] RABBITS = {"brown", "white", "black", "white_splotched", "gold", "salt"};
    private static final String[] CATS = {"ocelot", "black", "red", "siamese"};

    private static final String[] COATS = {"white", "creamy", "chestnut", "brown", "black", "gray", "darkbrown"};
    private static final String[] MARKINGS = {null, "white", "whitefield", "whitedots", "blackdots"};
    private static final String[] ARMORS = {null, "iron", "gold", "diamond"};
    /** Layered horse texture keys by variant and armor, so a frame builds no strings. */
    private static final java.util.Map<Integer, String> HORSE_TEXTURES = new java.util.HashMap<>();

    private MobSelector() {
    }

    /**
     * RenderHorse: metadata 19 is the type (horse, donkey, mule, zombie, skeleton), 20 the coat and marking, 22
     * the armor, 16 the flags. A horse's coat, marking and armor are one {@code +}-joined texture key that
     * {@link ImageLayers} composites, as vanilla's LayeredTexture does.
     */
    private static boolean selectHorse(Entity entity, EntityModels.Motion motion, Choice choice) {
        int type = entity.metadata.getByte(19, 0);
        int flags = entity.metadata.getInt(16, 0);
        choice.kind = EntityModels.Kind.HORSE;
        switch (type) {
            case 1 -> choice.texture = "entity/horse/donkey";
            case 2 -> choice.texture = "entity/horse/mule";
            case 3 -> choice.texture = "entity/horse/horse_zombie";
            case 4 -> choice.texture = "entity/horse/horse_skeleton";
            default -> {
                int variant = entity.metadata.getInt(20, 0);
                int armor = entity.metadata.getInt(22, 0);
                int coat = variant & 0xFF;
                int marking = (variant & 0xFF00) >> 8;
                if (coat >= COATS.length || marking >= MARKINGS.length || armor < 0 || armor >= ARMORS.length) {
                    return false;
                }
                choice.texture = HORSE_TEXTURES.computeIfAbsent(variant << 2 | armor, key -> {
                    StringBuilder text = new StringBuilder("entity/horse/horse_").append(COATS[coat]);
                    if (MARKINGS[marking] != null) {
                        text.append("+entity/horse/horse_markings_").append(MARKINGS[marking]);
                    }
                    if (ARMORS[armor] != null) {
                        text.append("+entity/horse/armor/horse_armor_").append(ARMORS[armor]);
                    }
                    return text.toString();
                });
            }
        }
        choice.scale = type == 1 ? 0.87f : type == 2 ? 0.92f : 1f;
        motion.foal = EntityTypes.child(entity);
        motion.saddled = (flags & 4) != 0;
        motion.chested = (flags & 8) != 0;
        motion.longEars = type == 1 || type == 2;
        motion.grazing = (flags & 32) != 0 ? 1f : 0f;
        motion.rearing = (flags & 64) != 0 ? 1f : 0f;
        return true;
    }

    /** Fills {@code choice} and the variant-driven fields of {@code motion}; false when the type has no model. */
    static boolean select(Entity entity, float partialTick, EntityModels.Motion motion, Choice choice) {
        choice.reset();
        switch (entity.type) {
            case EntityTypes.WITCH -> {
                choice.kind = EntityModels.Kind.WITCH;
                choice.texture = "entity/witch";
                choice.scale = 0.9375f;
            }
            case EntityTypes.BAT -> {
                choice.kind = EntityModels.Kind.BAT;
                choice.texture = "entity/bat";
                choice.scale = 0.35f;
                motion.hanging = (entity.metadata.getByte(16, 0) & 1) != 0;
                float age = entity.ticksExisted + partialTick;
                choice.lift = motion.hanging ? -0.1f : (float) Math.cos(age * 0.3f) * 0.1f;
            }
            case EntityTypes.RABBIT -> {
                choice.kind = EntityModels.Kind.RABBIT;
                int type = entity.metadata.getByte(18, 0);
                choice.texture = type == 99 ? "entity/rabbit/caerbannog"
                        : "Toast".equals(entity.metadata.getString(2, "")) ? "entity/rabbit/toast"
                        : "entity/rabbit/" + RABBITS[type >= 0 && type < RABBITS.length ? type : 0];
                // The hop pose peaks mid-jump; without the server's jump timer a rabbit that is moving vertically
                // holds it.
                motion.jump = Math.abs(entity.y - entity.previousY) > 1e-3 ? 0.5f : 0f;
            }
            case EntityTypes.OCELOT -> {
                choice.kind = EntityModels.Kind.OCELOT;
                int skin = entity.metadata.getByte(18, 0);
                choice.texture = "entity/cat/" + CATS[skin >= 0 && skin < CATS.length ? skin : 0];
                int tameable = entity.metadata.getByte(16, 0);
                motion.sitting = (tameable & 1) != 0;
                motion.sprinting = entity.sprinting();
                choice.scale = (tameable & 4) != 0 ? 0.8f : 1f;
            }
            case EntityTypes.SILVERFISH -> {
                choice.kind = EntityModels.Kind.SILVERFISH;
                choice.texture = "entity/silverfish";
                choice.deathRotation = 180f;
            }
            case EntityTypes.ENDERMITE -> {
                choice.kind = EntityModels.Kind.ENDERMITE;
                choice.texture = "entity/endermite";
                choice.deathRotation = 180f;
            }
            case EntityTypes.MAGMA_CUBE -> {
                choice.kind = EntityModels.Kind.MAGMA_CUBE;
                choice.texture = "entity/slime/magmacube";
                choice.scale = Math.max(1, entity.metadata.getByte(16, 1));
            }
            case EntityTypes.SNOW_GOLEM -> {
                choice.kind = EntityModels.Kind.SNOW_GOLEM;
                choice.texture = "entity/snowman";
            }
            case EntityTypes.HORSE -> {
                if (!selectHorse(entity, motion, choice)) {
                    return false;
                }
            }
            default -> {
                return false;
            }
        }
        return true;
    }
}
