package alkaline.game.state;

import alkaline.game.net.ServerSession;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The server's scoreboard as the client sees it: objectives, scores, teams, and which objective the sidebar
 * shows. Game-thread state; {@link #sidebar()} lists the lines vanilla's GuiIngame.renderScoreboard draws.
 */
public final class Scoreboard {
    /** One sidebar line: the entry formatted with its team's prefix and suffix, and its score. */
    public record Line(String text, int score) {
    }

    /** The sidebar's title and lines, top to bottom; null when no sidebar objective is shown. */
    public record Sidebar(String title, List<Line> lines) {
    }

    private record TeamInfo(String prefix, String suffix) {
    }

    private final Map<String, String> objectives = new HashMap<>();
    private final Map<String, Map<String, Integer>> scores = new HashMap<>();
    private final Map<String, TeamInfo> teams = new HashMap<>();
    private final Map<String, String> teamOf = new HashMap<>();
    private String sidebarObjective;
    private int version;

    /** Bumped on every change, so the HUD rebuilds its lines only when something changed. */
    public int version() {
        return version;
    }

    public void clear() {
        objectives.clear();
        scores.clear();
        teams.clear();
        teamOf.clear();
        sidebarObjective = null;
        version++;
    }

    /** Applies a scoreboard event; returns false for other events. */
    public boolean apply(ServerSession.Event event) {
        switch (event) {
            case ServerSession.ScoreboardObjective objective -> {
                if (objective.mode() == 1) {
                    objectives.remove(objective.name());
                    scores.remove(objective.name());
                    if (objective.name().equals(sidebarObjective)) {
                        sidebarObjective = null;
                    }
                } else {
                    objectives.put(objective.name(), objective.displayName());
                }
            }
            case ServerSession.UpdateScore score -> {
                if (score.action() == 1) {
                    // An empty objective name removes the entry from every objective.
                    if (score.objective().isEmpty()) {
                        for (Map<String, Integer> values : scores.values()) {
                            values.remove(score.entry());
                        }
                    } else {
                        Map<String, Integer> values = scores.get(score.objective());
                        if (values != null) {
                            values.remove(score.entry());
                        }
                    }
                } else {
                    scores.computeIfAbsent(score.objective(), ignored -> new HashMap<>()).put(score.entry(),
                            score.value());
                }
            }
            case ServerSession.DisplayScoreboard display -> {
                if (display.position() == 1) {
                    sidebarObjective = display.objective().isEmpty() ? null : display.objective();
                }
            }
            case ServerSession.Team team -> applyTeam(team);
            default -> {
                return false;
            }
        }
        version++;
        return true;
    }

    private void applyTeam(ServerSession.Team team) {
        switch (team.mode()) {
            case 0, 2 -> {
                teams.put(team.name(), new TeamInfo(team.prefix(), team.suffix()));
                if (team.mode() == 0) {
                    for (String player : team.players()) {
                        teamOf.put(player, team.name());
                    }
                }
            }
            case 1 -> {
                teams.remove(team.name());
                teamOf.values().removeIf(team.name()::equals);
            }
            case 3 -> {
                for (String player : team.players()) {
                    teamOf.put(player, team.name());
                }
            }
            case 4 -> {
                for (String player : team.players()) {
                    teamOf.remove(player, team.name());
                }
            }
            default -> {
            }
        }
    }

    /** ScorePlayerTeam.formatPlayerName: the team's prefix and suffix around a name (with § codes). */
    public String format(String name) {
        String team = teamOf.get(name);
        TeamInfo info = team == null ? null : teams.get(team);
        return info == null ? name : info.prefix() + name + info.suffix();
    }

    /**
     * The sidebar as vanilla draws it: entries not starting with '#', the 15 highest by Score.scoreComparator
     * (score, then name in reverse), highest first.
     */
    public Sidebar sidebar() {
        if (sidebarObjective == null || !objectives.containsKey(sidebarObjective)) {
            return null;
        }
        List<Map.Entry<String, Integer>> entries = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : scores.getOrDefault(sidebarObjective, Map.of()).entrySet()) {
            if (!entry.getKey().startsWith("#")) {
                entries.add(entry);
            }
        }
        entries.sort((a, b) -> a.getValue() > b.getValue() ? 1 : a.getValue() < b.getValue() ? -1
                : b.getKey().compareToIgnoreCase(a.getKey()));
        List<Line> lines = new ArrayList<>();
        for (int index = entries.size() - 1; index >= Math.max(0, entries.size() - 15); index--) {
            Map.Entry<String, Integer> entry = entries.get(index);
            lines.add(new Line(format(entry.getKey()), entry.getValue()));
        }
        String title = objectives.get(sidebarObjective);
        return new Sidebar(title == null ? sidebarObjective : title, List.copyOf(lines));
    }
}
