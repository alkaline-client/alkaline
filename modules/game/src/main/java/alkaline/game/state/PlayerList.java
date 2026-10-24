package alkaline.game.state;

import alkaline.game.net.ServerSession;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The server's player list (tab list): names, skins, game modes, latency and display names, by UUID, as Player
 * List Item packets maintain it. Game-thread state.
 */
public final class PlayerList {
    /** One listed player. {@code textures} is the base64 "textures" profile property, or null. */
    public record Player(UUID uuid, String name, String textures, int gameMode, int ping, String displayNameJson) {
    }

    private final Map<UUID, Player> players = new LinkedHashMap<>();
    private int version;

    public int version() {
        return version;
    }

    public Player get(UUID uuid) {
        return uuid == null ? null : players.get(uuid);
    }

    public Collection<Player> all() {
        return players.values();
    }

    public void clear() {
        players.clear();
        version++;
    }

    /** Applies a Player List Item event; returns false for other events. */
    public boolean apply(ServerSession.Event event) {
        if (!(event instanceof ServerSession.PlayerList list)) {
            return false;
        }
        for (ServerSession.PlayerListEntry entry : list.entries()) {
            Player current = players.get(entry.uuid());
            switch (list.action()) {
                case 0 -> players.put(entry.uuid(), new Player(entry.uuid(), entry.name(), entry.textures(),
                        entry.gameMode(), entry.ping(), entry.displayNameJson()));
                case 1 -> {
                    if (current != null) {
                        players.put(entry.uuid(), new Player(current.uuid(), current.name(), current.textures(),
                                entry.gameMode(), current.ping(), current.displayNameJson()));
                    }
                }
                case 2 -> {
                    if (current != null) {
                        players.put(entry.uuid(), new Player(current.uuid(), current.name(), current.textures(),
                                current.gameMode(), entry.ping(), current.displayNameJson()));
                    }
                }
                case 3 -> {
                    if (current != null) {
                        players.put(entry.uuid(), new Player(current.uuid(), current.name(), current.textures(),
                                current.gameMode(), current.ping(), entry.displayNameJson()));
                    }
                }
                case 4 -> players.remove(entry.uuid());
                default -> {
                }
            }
        }
        version++;
        return true;
    }

    /**
     * Players sorted as GuiPlayerTabOverlay's comparator does: spectators last, then by the registered name of their
     * team (players without one first), then by name; both comparisons are case-sensitive, as ComparisonChain's.
     */
    public List<Player> sorted(Scoreboard scoreboard) {
        List<Player> sorted = new ArrayList<>(players.values());
        sorted.sort((a, b) -> {
            int spectator = Boolean.compare(a.gameMode() == 3, b.gameMode() == 3);
            if (spectator != 0) {
                return spectator;
            }
            int team = teamName(scoreboard, a).compareTo(teamName(scoreboard, b));
            return team != 0 ? team : String.valueOf(a.name()).compareTo(String.valueOf(b.name()));
        });
        return sorted;
    }

    private static String teamName(Scoreboard scoreboard, Player player) {
        Scoreboard.Team team = scoreboard == null ? null : scoreboard.teamOf(player.name());
        return team == null ? "" : team.name();
    }
}
