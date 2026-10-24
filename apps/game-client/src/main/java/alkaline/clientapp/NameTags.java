package alkaline.clientapp;

import alkaline.game.entity.Entity;
import alkaline.game.entity.EntityTypes;
import alkaline.game.state.PlayerList;
import alkaline.game.state.Scoreboard;

/**
 * Which entities carry a name tag and what it says: RendererLivingEntity.canRenderName and renderName, with
 * RenderPlayer's team rules and below-name score, RenderLiving's custom names and ArmorStandRenderer's always-visible
 * ones. Pure decisions over game state; {@link NameTagRenderer} draws what they ask for.
 */
final class NameTags {
    /** RendererLivingEntity.NAME_TAG_RANGE and NAME_TAG_RANGE_SNEAK. */
    static final float RANGE = 64f;
    static final float RANGE_SNEAK = 32f;
    /** RenderPlayer.renderOffsetLivingLabel shows the score only within 10 blocks (the squared distance below 100). */
    static final double SCORE_RANGE_SQUARED = 100.0;
    /** The name rises by the score line's height, 9 * 1.15 pixels at the label scale. */
    static final float SCORE_LIFT = 9f * 1.15f * NameTagRenderer.SCALE;

    /** What the local player and the world look like to the rules. */
    interface Viewer {
        Scoreboard scoreboard();

        PlayerList players();

        /** The entity under the crosshair, or null. */
        Entity pointed();

        /** The local player's entity id, name, game mode and feet position. */
        int selfId();

        String selfName();

        boolean spectator();

        double selfX();

        double selfY();

        double selfZ();

        /** Whether the F1 interface-hiding toggle is off (Minecraft.isGuiEnabled). */
        boolean guiEnabled();

        /** Whether another entity rides this one. */
        boolean ridden(Entity entity);
    }

    private NameTags() {
    }

    /** One tag to draw: its text, whether it takes the sneaking branch, and the score line under it (or null). */
    record Tag(String text, boolean sneaking, String score) {
    }

    /**
     * The tag an entity carries this frame, or null. {@code distanceSquared} is to the local player's feet.
     */
    static Tag of(Entity entity, Viewer viewer) {
        double dx = entity.x - viewer.selfX();
        double dy = entity.y - viewer.selfY();
        double dz = entity.z - viewer.selfZ();
        double distanceSquared = dx * dx + dy * dy + dz * dz;
        String text;
        switch (entity.category) {
            case PLAYER -> {
                PlayerList.Player listed = viewer.players().get(entity.uuid);
                if (listed == null || listed.name() == null || !playerVisible(entity, listed.name(), viewer)) {
                    return null;
                }
                boolean sneaking = entity.sneaking();
                float range = sneaking ? RANGE_SNEAK : RANGE;
                if (!(distanceSquared < range * range)) {
                    return null;
                }
                text = viewer.scoreboard().format(listed.name());
                String score = !sneaking && distanceSquared < SCORE_RANGE_SQUARED
                        ? viewer.scoreboard().belowName(listed.name()) : null;
                return new Tag(text, sneaking, score);
            }
            case MOB -> {
                boolean always = entity.metadata.getByte(3, 0) == 1;
                String custom = entity.metadata.getString(2, "");
                // RenderLiving: the usual exclusions, then always-visible names, or a custom name when pointed at.
                if (!shown(entity, viewer) || !(always || !custom.isEmpty() && entity == viewer.pointed())) {
                    return null;
                }
                if (custom.isEmpty() || !(distanceSquared < RANGE * RANGE)) {
                    return null;
                }
                return new Tag(custom, false, null);
            }
            default -> {
                // ArmorStandRenderer: its own flag only; the stand is named by its custom name.
                if (entity.type != EntityTypes.OBJECT_ARMOR_STAND || entity.metadata.getByte(3, 0) != 1) {
                    return null;
                }
                String custom = entity.metadata.getString(2, "");
                if (custom.isEmpty() || !(distanceSquared < RANGE * RANGE)) {
                    return null;
                }
                return new Tag(custom, false, null);
            }
        }
    }

    /** RendererLivingEntity.canRenderName for another player: the team's rule when it has one. */
    private static boolean playerVisible(Entity entity, String name, Viewer viewer) {
        if (entity.id == viewer.selfId()) {
            return false;
        }
        Scoreboard.Team team = viewer.scoreboard().teamOf(name);
        if (team != null) {
            Scoreboard.Team own = viewer.scoreboard().teamOf(viewer.selfName());
            return switch (team.nameTags()) {
                case ALWAYS -> true;
                case NEVER -> false;
                case HIDE_FOR_OTHER_TEAMS -> own == null || team == own;
                case HIDE_FOR_OWN_TEAM -> own == null || team != own;
            };
        }
        return shown(entity, viewer);
    }

    /** The interface is on, the entity is not the local player, not invisible to it and carries no rider. */
    private static boolean shown(Entity entity, Viewer viewer) {
        return viewer.guiEnabled() && entity.id != viewer.selfId() && (viewer.spectator() || !entity.invisible())
                && !viewer.ridden(entity);
    }

    /**
     * The label's height above the entity's feet: its height plus half a block, less half the height for a child
     * (RendererLivingEntity.renderName); players are 1.8 tall sneaking or not.
     */
    static float labelHeight(Entity entity) {
        float height = entity.category == Entity.Category.PLAYER ? 1.8f : EntityTypes.height(entity);
        return height + 0.5f - (entity.category == Entity.Category.MOB && EntityTypes.child(entity) ? height / 2f : 0f);
    }
}
