package alkaline.game.entity;

/**
 * Minecraft 1.8.9's entity type ids (EntityList and the Spawn Object ids) with the hitbox sizes the client needs
 * to pick entities and place name tags. Sizes are the constructors' setSize values; mobs that do not call it
 * keep Entity's 0.6 x 1.8.
 */
public final class EntityTypes {
    public static final int CREEPER = 50;
    public static final int SKELETON = 51;
    public static final int SPIDER = 52;
    public static final int GIANT = 53;
    public static final int ZOMBIE = 54;
    public static final int SLIME = 55;
    public static final int GHAST = 56;
    public static final int PIG_ZOMBIE = 57;
    public static final int ENDERMAN = 58;
    public static final int CAVE_SPIDER = 59;
    public static final int SILVERFISH = 60;
    public static final int BLAZE = 61;
    public static final int MAGMA_CUBE = 62;
    public static final int ENDER_DRAGON = 63;
    public static final int WITHER = 64;
    public static final int BAT = 65;
    public static final int WITCH = 66;
    public static final int ENDERMITE = 67;
    public static final int GUARDIAN = 68;
    public static final int PIG = 90;
    public static final int SHEEP = 91;
    public static final int COW = 92;
    public static final int CHICKEN = 93;
    public static final int SQUID = 94;
    public static final int WOLF = 95;
    public static final int MOOSHROOM = 96;
    public static final int SNOW_GOLEM = 97;
    public static final int OCELOT = 98;
    public static final int IRON_GOLEM = 99;
    public static final int HORSE = 100;
    public static final int RABBIT = 101;
    public static final int VILLAGER = 120;

    /** Spawn Object ids. */
    public static final int OBJECT_BOAT = 1;
    public static final int OBJECT_ITEM = 2;
    public static final int OBJECT_MINECART = 10;
    public static final int OBJECT_TNT = 50;
    public static final int OBJECT_ENDER_CRYSTAL = 51;
    public static final int OBJECT_ARROW = 60;
    public static final int OBJECT_SNOWBALL = 61;
    public static final int OBJECT_EGG = 62;
    public static final int OBJECT_FIREBALL = 63;
    public static final int OBJECT_SMALL_FIREBALL = 64;
    public static final int OBJECT_ENDER_PEARL = 65;
    public static final int OBJECT_WITHER_SKULL = 66;
    public static final int OBJECT_ENDER_EYE = 72;
    public static final int OBJECT_EXP_BOTTLE = 75;
    public static final int OBJECT_FIREWORK = 76;
    public static final int OBJECT_FALLING_BLOCK = 70;
    public static final int OBJECT_ITEM_FRAME = 71;
    public static final int OBJECT_POTION = 73;
    public static final int OBJECT_ARMOR_STAND = 78;
    public static final int OBJECT_FISHING_HOOK = 90;
    /** Objects with spawn packets of their own, as OBJECT entities with types no Spawn Object uses. */
    public static final int OBJECT_PAINTING = -1;
    public static final int OBJECT_XP_ORB = -2;
    public static final int OBJECT_LIGHTNING = -3;

    private EntityTypes() {
    }

    /** Hitbox width in blocks. */
    public static float width(Entity entity) {
        return dimension(entity, false);
    }

    /** Hitbox height in blocks. */
    public static float height(Entity entity) {
        return dimension(entity, true);
    }

    /** Whether a child (Ageable's metadata 12 below zero, or the zombie's metadata 12 flag). */
    public static boolean child(Entity entity) {
        return entity.category == Entity.Category.MOB && entity.metadata.getByte(12, 0) != 0
                && (entity.type == ZOMBIE || entity.type == PIG_ZOMBIE || entity.metadata.getByte(12, 0) < 0);
    }

    /** One hitbox dimension, without allocating: renderers ask for it per entity per frame. */
    private static float dimension(Entity entity, boolean height) {
        return switch (entity.category) {
            case PLAYER -> height ? (entity.sneaking() ? 1.65f : 1.8f) : 0.6f;
            case OBJECT -> objectDimension(entity.type, height);
            case MOB -> mobDimension(entity, height) * (child(entity) ? 0.5f : 1f);
        };
    }

    private static float mobDimension(Entity entity, boolean height) {
        return switch (entity.type) {
            case SKELETON -> entity.metadata.getByte(13, 0) == 1 ? (height ? 2.535f : 0.72f) : (height ? 1.95f : 0.6f);
            case SPIDER -> height ? 0.9f : 1.4f;
            case GIANT -> height ? 10.8f : 3.6f;
            case ZOMBIE, PIG_ZOMBIE, WITCH -> height ? 1.95f : 0.6f;
            case SLIME, MAGMA_CUBE -> 0.51000005f * Math.max(1, entity.metadata.getByte(16, 1));
            case GHAST -> 4f;
            case ENDERMAN -> height ? 2.9f : 0.6f;
            case CAVE_SPIDER -> height ? 0.5f : 0.7f;
            case SILVERFISH, ENDERMITE -> height ? 0.3f : 0.4f;
            case ENDER_DRAGON -> height ? 8f : 16f;
            case WITHER -> height ? 3.5f : 0.9f;
            case BAT -> height ? 0.9f : 0.5f;
            case GUARDIAN -> (entity.metadata.getInt(16, 0) & 4) != 0 ? 1.9975f : 0.85f;
            case PIG -> 0.9f;
            case SHEEP, COW, MOOSHROOM -> height ? 1.3f : 0.9f;
            case CHICKEN -> height ? 0.7f : 0.4f;
            case SQUID -> 0.95f;
            case WOLF -> height ? 0.8f : 0.6f;
            case SNOW_GOLEM -> height ? 1.9f : 0.7f;
            case OCELOT, RABBIT -> height ? 0.7f : 0.6f;
            case IRON_GOLEM -> height ? 2.9f : 1.4f;
            case HORSE -> height ? 1.6f : 1.4f;
            default -> height ? 1.8f : 0.6f;
        };
    }

    private static float objectDimension(int type, boolean height) {
        return switch (type) {
            case OBJECT_BOAT -> height ? 0.6f : 1.5f;
            case OBJECT_MINECART -> height ? 0.7f : 0.98f;
            case OBJECT_TNT, OBJECT_FALLING_BLOCK -> 0.98f;
            case OBJECT_ARROW, OBJECT_ITEM_FRAME -> 0.5f;
            case OBJECT_ARMOR_STAND -> height ? 1.975f : 0.5f;
            case OBJECT_ENDER_CRYSTAL -> 2f;
            default -> 0.25f;
        };
    }
}
