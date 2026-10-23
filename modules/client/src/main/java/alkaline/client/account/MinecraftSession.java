package alkaline.client.account;

import java.util.Objects;

/**
 * A signed-in player: the profile and the Minecraft services access token a game session presents to servers.
 * The access token lives only in memory; it is never written to disk or logged.
 */
public record MinecraftSession(MinecraftProfile profile, String accessToken, long expiresAtMillis) {
    public MinecraftSession {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(accessToken, "accessToken");
    }

    @Override
    public String toString() {
        return "MinecraftSession[" + profile.name() + "]";
    }
}
