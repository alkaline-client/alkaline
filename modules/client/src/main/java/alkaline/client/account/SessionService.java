package alkaline.client.account;

import alkaline.config.Json;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Mojang's session service join, the step an online-mode server verifies before letting a player in: the
 * client posts its access token, profile id and the server's hash to {@code session/minecraft/join}. The token
 * goes only to Mojang's session server, as the vanilla client sends it.
 */
public final class SessionService {
    private static final URI JOIN = URI.create("https://sessionserver.mojang.com/session/minecraft/join");
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    /** Records the join for {@code session} at the server identified by {@code serverHash}. */
    public void join(MinecraftSession session, String serverHash) throws IOException {
        Objects.requireNonNull(session, "session");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accessToken", session.accessToken());
        body.put("selectedProfile", session.profile().uuid().replace("-", ""));
        body.put("serverId", serverHash);
        HttpRequest request = HttpRequest.newBuilder(JOIN).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body))).build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IOException(switch (response.statusCode()) {
                    case 401, 403 -> "The session service rejected the sign-in; sign in again.";
                    case 429 -> "Too many sign-in attempts; try again in a minute.";
                    default -> "The session service answered " + response.statusCode() + ".";
                });
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Joining was interrupted.", interrupted);
        }
    }
}
