package alkaline.clientapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import alkaline.game.entity.Entity;
import alkaline.game.entity.EntityTypes;
import alkaline.game.net.ServerSession;
import alkaline.game.state.PlayerList;
import alkaline.game.state.Scoreboard;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NameTagsTest implements NameTags.Viewer {
    private final Scoreboard scoreboard = new Scoreboard();
    private final PlayerList players = new PlayerList();
    private final Set<Integer> ridden = new HashSet<>();
    private Entity pointed;
    private boolean spectator;
    private final UUID otherId = UUID.randomUUID();
    private Entity other;

    @BeforeEach
    void setUp() {
        players.apply(new ServerSession.PlayerList(0, List.of(
                new ServerSession.PlayerListEntry(otherId, "Ann", null, 0, 0, null))));
        other = new Entity(5, Entity.Category.PLAYER, 0, otherId, 3, 0, 0, 0, 0);
    }

    @Override public Scoreboard scoreboard() { return scoreboard; }
    @Override public PlayerList players() { return players; }
    @Override public Entity pointed() { return pointed; }
    @Override public int selfId() { return 1; }
    @Override public String selfName() { return "Me"; }
    @Override public boolean spectator() { return spectator; }
    @Override public double selfX() { return 0; }
    @Override public double selfY() { return 0; }
    @Override public double selfZ() { return 0; }
    @Override public boolean guiEnabled() { return true; }
    @Override public boolean ridden(Entity entity) { return ridden.contains(entity.id); }

    private void team(String name, String prefix, String visibility, String... members) {
        scoreboard.apply(new ServerSession.Team(name, 0, name, prefix, "", -1, List.of(members), 3, visibility));
    }

    @Test
    void playersShowTheirTeamFormattedName() {
        team("red", "§c[R] ", "always", "Ann");
        assertEquals("§c[R] Ann", NameTags.of(other, this).text());
    }

    @Test
    void rangeIs64AndSneakingTags32() {
        other.x = 63.9;
        assertNotNull(NameTags.of(other, this));
        other.x = 64.0;
        assertNull(NameTags.of(other, this));
        other.x = 31.9;
        other.metadata.setByte(0, 0x02);
        assertTrue(NameTags.of(other, this).sneaking());
        other.x = 32.0;
        assertNull(NameTags.of(other, this));
    }

    @Test
    void teamRulesDecideVisibilityBeforeInvisibility() {
        team("a", "", "never", "Ann");
        assertNull(NameTags.of(other, this));
        // hideForOtherTeams: shown to a viewer on no team, and to teammates, hidden from other teams.
        scoreboard.apply(new ServerSession.Team("a", 2, "a", "", "", -1, List.of(), 3, "hideForOtherTeams"));
        assertNotNull(NameTags.of(other, this));
        team("mine", "", "always", "Me");
        assertNull(NameTags.of(other, this));
        scoreboard.apply(new ServerSession.Team("mine", 4, null, null, null, -1, List.of("Me"), 0, null));
        scoreboard.apply(new ServerSession.Team("a", 3, null, null, null, -1, List.of("Me"), 0, null));
        assertNotNull(NameTags.of(other, this));
        // hideForOwnTeam: the opposite.
        scoreboard.apply(new ServerSession.Team("a", 2, "a", "", "", -1, List.of(), 3, "hideForOwnTeam"));
        assertNull(NameTags.of(other, this));
        // A team that says never wins over the viewer being a spectator who sees invisible players.
        other.metadata.setByte(0, 0x20);
        scoreboard.apply(new ServerSession.Team("a", 2, "a", "", "", -1, List.of(), 3, "always"));
        assertNotNull(NameTags.of(other, this), "the team's always overrides invisibility");
    }

    @Test
    void anInvisibleOrRiddenPlayerHasNoTagUnlessTheTeamSaysAlways() {
        other.metadata.setByte(0, 0x20);
        assertNull(NameTags.of(other, this));
        spectator = true;
        assertNotNull(NameTags.of(other, this));
        spectator = false;
        other.metadata.setByte(0, 0);
        ridden.add(other.id);
        assertNull(NameTags.of(other, this));
        team("t", "", "always", "Ann");
        assertNotNull(NameTags.of(other, this));
    }

    @Test
    void theScoreLineShowsOnlyWithinTenBlocksAndNotWhenSneaking() {
        scoreboard.apply(new ServerSession.ScoreboardObjective("hp", 0, "HP"));
        scoreboard.apply(new ServerSession.DisplayScoreboard(2, "hp"));
        scoreboard.apply(new ServerSession.UpdateScore("Ann", 0, "hp", 12));
        other.x = 9.9;
        assertEquals("12 HP", NameTags.of(other, this).score());
        other.x = 10.0;
        assertNull(NameTags.of(other, this).score());
        other.x = 3;
        other.metadata.setByte(0, 0x02);
        assertNull(NameTags.of(other, this).score());
    }

    @Test
    void mobsShowCustomNamesWhenAlwaysVisibleOrPointedAt() {
        Entity pig = new Entity(7, Entity.Category.MOB, EntityTypes.PIG, null, 2, 0, 0, 0, 0);
        assertNull(NameTags.of(pig, this));
        pig.metadata.merge(named("Wilbur", 0));
        assertNull(NameTags.of(pig, this));
        pointed = pig;
        assertEquals("Wilbur", NameTags.of(pig, this).text());
        pointed = null;
        pig.metadata.merge(named("Wilbur", 1));
        assertEquals("Wilbur", NameTags.of(pig, this).text());
        ridden.add(pig.id);
        assertNull(NameTags.of(pig, this));
    }

    @Test
    void armorStandsShowOnlyWhenTheirNameIsAlwaysVisible() {
        Entity stand = new Entity(8, Entity.Category.OBJECT, EntityTypes.OBJECT_ARMOR_STAND, null, 2, 0, 0, 0, 0);
        stand.metadata.merge(named("Stand", 0));
        assertNull(NameTags.of(stand, this));
        stand.metadata.merge(named("Stand", 1));
        assertEquals("Stand", NameTags.of(stand, this).text());
        stand.metadata.setByte(0, 0x20); // invisible stands keep their tags
        assertEquals("Stand", NameTags.of(stand, this).text());
    }

    @Test
    void theLabelSitsHalfABlockAboveTheHeadAndLowerOnChildren() {
        assertEquals(1.8f + 0.5f, NameTags.labelHeight(other), 1e-6);
        Entity pig = new Entity(7, Entity.Category.MOB, EntityTypes.PIG, null, 0, 0, 0, 0, 0);
        assertEquals(0.9f + 0.5f, NameTags.labelHeight(pig), 1e-6);
        pig.metadata.setByte(12, -1);
        assertEquals(0.45f / 2f + 0.5f, NameTags.labelHeight(pig), 1e-6);
    }

    private static alkaline.game.entity.Metadata named(String name, int always) {
        alkaline.game.entity.Metadata metadata = new alkaline.game.entity.Metadata();
        try {
            alkaline.game.net.PacketBuffer buffer = new alkaline.game.net.PacketBuffer();
            buffer.writeByte(4 << 5 | 2).writeString(name).writeByte(3).writeByte(always).writeByte(0x7F);
            metadata.merge(alkaline.game.entity.Metadata.read(
                    alkaline.game.net.PacketBuffer.wrap(buffer.toByteArray())));
        } catch (java.io.IOException failure) {
            throw new AssertionError(failure);
        }
        return metadata;
    }
}
