package alkaline.client.servers;

import java.util.List;

/**
 * A server's answer to a status ping, or the reason it gave none.
 *
 * @param motd     message of the day as legacy {@code §}-formatted text (chat components are flattened)
 * @param favicon  the server's 64x64 PNG icon bytes, or null
 * @param sample   a few player names the server chose to list
 */
public record ServerStatus(boolean online, String error, long latencyMillis, String versionName, int protocol,
                           int playersOnline, int playersMax, String motd, byte[] favicon, List<String> sample) {
    /** The protocol this client speaks: Minecraft 1.8.x. */
    public static final int CLIENT_PROTOCOL = 47;

    public static ServerStatus failed(String error) {
        return new ServerStatus(false, error, -1, "", -1, 0, 0, "", null, List.of());
    }

    /** True when the server reports the same protocol as this client, so a join would be accepted. */
    public boolean compatible() {
        return online && protocol == CLIENT_PROTOCOL;
    }
}
