package alkaline.game.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import alkaline.game.net.ServerSession;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScoreboardTest {
    @Test
    void sidebarListsHighestFirstWithTeamFormattingAndHidesHashEntries() {
        Scoreboard board = new Scoreboard();
        assertNull(board.sidebar());
        board.apply(new ServerSession.ScoreboardObjective("game", 0, "§eBEDWARS"));
        board.apply(new ServerSession.DisplayScoreboard(1, "game"));
        board.apply(new ServerSession.UpdateScore("Red", 0, "game", 3));
        board.apply(new ServerSession.UpdateScore("Blue", 0, "game", 7));
        board.apply(new ServerSession.UpdateScore("#hidden", 0, "game", 9));
        board.apply(new ServerSession.Team("t1", 0, "", "§c", " ✔", 12, List.of("Red"), 0, "always"));
        Scoreboard.Sidebar sidebar = board.sidebar();
        assertEquals("§eBEDWARS", sidebar.title());
        assertEquals(List.of(new Scoreboard.Line("Blue", 7), new Scoreboard.Line("§cRed ✔", 3)), sidebar.lines());
        board.apply(new ServerSession.UpdateScore("Blue", 1, "game", 0));
        assertEquals(1, board.sidebar().lines().size());
        board.apply(new ServerSession.ScoreboardObjective("game", 1, null));
        assertNull(board.sidebar());
    }

    private static ServerSession.Team team(String name, String prefix, String suffix, String visibility,
                                           String... players) {
        return new ServerSession.Team(name, 0, name, prefix, suffix, -1, List.of(players), 3, visibility);
    }

    @Test
    void teamsFormatNamesAndKeepTheirNameTagRule() {
        Scoreboard board = new Scoreboard();
        board.apply(team("red", "\u00a7c[R] ", "", "hideForOtherTeams", "Ann"));
        board.apply(team("blue", "", "!", "hideForOwnTeam", "Bob"));
        assertEquals("\u00a7c[R] Ann", board.format("Ann"));
        assertEquals("Bob!", board.format("Bob"));
        assertEquals("Cy", board.format("Cy"));
        assertEquals(Scoreboard.Visibility.HIDE_FOR_OTHER_TEAMS, board.teamOf("Ann").nameTags());
        assertEquals(Scoreboard.Visibility.HIDE_FOR_OWN_TEAM, board.teamOf("Bob").nameTags());
        assertNull(board.teamOf("Cy"));
        assertEquals(true, board.sameTeam("Ann", "Ann"));
        assertEquals(false, board.sameTeam("Ann", "Bob"));
        assertEquals(false, board.sameTeam("Cy", "Cy"));
        // Modes: update info, remove a player, then the team.
        board.apply(new ServerSession.Team("red", 2, "red", "", "?", -1, List.of(), 0, "never"));
        assertEquals("Ann?", board.format("Ann"));
        assertEquals(Scoreboard.Visibility.NEVER, board.teamOf("Ann").nameTags());
        board.apply(new ServerSession.Team("red", 4, null, null, null, -1, List.of("Ann"), 0, null));
        assertNull(board.teamOf("Ann"));
        board.apply(new ServerSession.Team("blue", 1, null, null, null, -1, List.of(), 0, null));
        assertEquals("Bob", board.format("Bob"));
    }

    @Test
    void anUnknownVisibilityMeansAlways() {
        Scoreboard board = new Scoreboard();
        board.apply(team("t", "", "", "bogus", "X"));
        assertEquals(Scoreboard.Visibility.ALWAYS, board.teamOf("X").nameTags());
    }

    @Test
    void belowNameShowsTheScoreAndObjectiveName() {
        Scoreboard board = new Scoreboard();
        assertNull(board.belowName("Ann"));
        board.apply(new ServerSession.ScoreboardObjective("hp", 0, "Health"));
        board.apply(new ServerSession.DisplayScoreboard(2, "hp"));
        board.apply(new ServerSession.UpdateScore("Ann", 0, "hp", 7));
        assertEquals("7 Health", board.belowName("Ann"));
        // A player with no score reads as zero, as getValueFromObjective creates one.
        assertEquals("0 Health", board.belowName("Bob"));
        board.apply(new ServerSession.ScoreboardObjective("hp", 1, null));
        assertNull(board.belowName("Ann"));
    }

    @Test
    void theTabListSortsSpectatorsLastThenTeamThenCaseSensitiveName() {
        Scoreboard board = new Scoreboard();
        board.apply(team("b", "", "", "always", "Zed"));
        board.apply(team("a", "", "", "always", "Amy"));
        PlayerList list = new PlayerList();
        java.util.UUID[] ids = new java.util.UUID[6];
        String[] names = {"Zed", "Amy", "bob", "Carl", "Dee", "Spec"};
        java.util.List<ServerSession.PlayerListEntry> entries = new java.util.ArrayList<>();
        for (int index = 0; index < names.length; index++) {
            ids[index] = java.util.UUID.nameUUIDFromBytes(names[index].getBytes());
            entries.add(new ServerSession.PlayerListEntry(ids[index], names[index], null, index == 5 ? 3 : 0, 0, null));
        }
        list.apply(new ServerSession.PlayerList(0, entries));
        // No team first (Carl, Dee, bob: 'b' sorts after uppercase), then team a (Amy), team b (Zed), spectators last.
        assertEquals(List.of("Carl", "Dee", "bob", "Amy", "Zed", "Spec"),
                list.sorted(board).stream().map(PlayerList.Player::name).toList());
    }
}
