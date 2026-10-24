package alkaline.clientapp;

import alkaline.game.entity.Entity;
import alkaline.game.state.PlayerList;
import alkaline.game.state.Scoreboard;
import java.util.HashSet;
import java.util.Set;

/** The local game as {@link NameTags} sees it. {@link #refresh} once a frame finds which entities carry riders. */
final class PlayViewer implements NameTags.Viewer {
    private final PlaySession play;
    private final Set<Integer> vehicles = new HashSet<>();

    PlayViewer(PlaySession play) {
        this.play = play;
    }

    /** Collects the entities something rides. */
    void refresh() {
        vehicles.clear();
        for (Entity entity : play.entities().all()) {
            if (entity.vehicle >= 0) {
                vehicles.add(entity.vehicle);
            }
        }
        if (play.vehicle() >= 0) {
            vehicles.add(play.vehicle());
        }
    }

    @Override
    public Scoreboard scoreboard() {
        return play.scoreboard();
    }

    @Override
    public PlayerList players() {
        return play.players();
    }

    @Override
    public Entity pointed() {
        return play.pointed();
    }

    @Override
    public int selfId() {
        return play.entities().selfId();
    }

    @Override
    public String selfName() {
        return play.name();
    }

    @Override
    public boolean spectator() {
        return play.gameMode() == 3;
    }

    @Override
    public double selfX() {
        return play.player().x;
    }

    @Override
    public double selfY() {
        return play.player().y;
    }

    @Override
    public double selfZ() {
        return play.player().z;
    }

    @Override
    public boolean guiEnabled() {
        return true;
    }

    @Override
    public boolean ridden(Entity entity) {
        return vehicles.contains(entity.id);
    }
}
