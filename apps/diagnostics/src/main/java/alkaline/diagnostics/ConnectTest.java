package alkaline.diagnostics;

import alkaline.game.net.ServerSession;
import alkaline.game.world.ClientWorld;
import java.io.IOException;

/**
 * Logs in to a server in offline mode without a window, reports what arrives for a few seconds (join, position,
 * chunks, a sample of block states around the spawn), then disconnects. Proves the protocol path headless.
 */
final class ConnectTest {
    private ConnectTest() {
    }

    static int run(String address, String username, int seconds) {
        String host = address;
        int port = 25565;
        int colon = address.lastIndexOf(':');
        if (colon > 0) {
            host = address.substring(0, colon);
            port = Integer.parseInt(address.substring(colon + 1));
        }
        try (ServerSession session = ServerSession.connect(host, port, host, username, null)) {
            System.out.println("Logged in as " + session.profile().username() + " (" + session.profile().uuid() + ")");
            session.sendClientSettings("en_US", 6);
            long end = System.nanoTime() + seconds * 1_000_000_000L;
            double[] position = new double[3];
            boolean[] placed = new boolean[1];
            while (System.nanoTime() < end && !session.isClosed()) {
                session.drainEvents(event -> {
                    switch (event) {
                        case ServerSession.JoinGame join -> System.out.println("Join: " + join);
                        case ServerSession.Teleport teleport -> {
                            position[0] = teleport.x();
                            position[1] = teleport.y();
                            position[2] = teleport.z();
                            placed[0] = true;
                            System.out.println("Teleport: " + teleport);
                            session.sendPositionLook(teleport.x(), teleport.y(), teleport.z(), teleport.yaw(),
                                    teleport.pitch(), false);
                        }
                        case ServerSession.Disconnected gone -> System.out.println("Disconnected: " + gone.reason());
                        case ServerSession.Chat chat -> System.out.println("Chat: " + chat.json());
                        default -> {
                        }
                    }
                });
                if (placed[0]) {
                    session.sendPosition(position[0], position[1], position[2], true);
                }
                try {
                    Thread.sleep(50);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            ClientWorld world = session.world();
            System.out.println("Loaded columns: " + world.loadedColumns());
            int x = (int) Math.floor(position[0]);
            int z = (int) Math.floor(position[2]);
            StringBuilder column = new StringBuilder("Column at spawn, top down:");
            for (int y = (int) position[1] + 2; y >= (int) position[1] - 6; y--) {
                int state = world.state(x, y, z);
                int light = world.light(x, y, z);
                column.append(String.format(" y%d=%d:%d(sky %d, block %d)", y, state >> 4, state & 15, light >> 4,
                        light & 15));
            }
            System.out.println(column);
            return world.loadedColumns() > 0 ? 0 : 1;
        } catch (IOException failure) {
            System.err.println("Connection failed: " + failure.getMessage());
            return 1;
        }
    }
}
