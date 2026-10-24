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
}
