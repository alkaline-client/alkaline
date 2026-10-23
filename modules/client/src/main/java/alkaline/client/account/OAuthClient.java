package alkaline.client.account;

import java.util.Objects;

/**
 * The Microsoft OAuth application the sign-in runs as. Minecraft services accept Xbox tokens only from Azure
 * applications approved for Minecraft, and the authorization redirect must match one registered for the app.
 *
 * <p>Alkaline has no registration of its own yet. The default is the public desktop client used by the
 * reference sign-in this was modeled on (redirect {@code http://localhost:1919/login}); Microsoft's consent page
 * therefore names that application. Replace it with Alkaline's own approved registration through
 * {@code ALKALINE_MSA_CLIENT_ID} and {@code ALKALINE_MSA_REDIRECT_PORT} (the redirect path stays {@code /login}).
 */
public record OAuthClient(String clientId, int redirectPort, String scope) {
    private static final String DEFAULT_CLIENT_ID = "0add8caf-2cc6-4546-b798-c3d171217dd9";
    private static final int DEFAULT_PORT = 1919;

    public OAuthClient {
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(scope, "scope");
        if (redirectPort < 1 || redirectPort > 65535) {
            throw new IllegalArgumentException("Redirect port out of range.");
        }
    }

    public String redirectUri() {
        return "http://localhost:" + redirectPort + "/login";
    }

    public static OAuthClient fromEnvironment() {
        String id = System.getenv("ALKALINE_MSA_CLIENT_ID");
        String port = System.getenv("ALKALINE_MSA_REDIRECT_PORT");
        int redirectPort = DEFAULT_PORT;
        if (port != null && !port.isBlank()) {
            try {
                redirectPort = Integer.parseInt(port.strip());
            } catch (NumberFormatException invalid) {
                System.err.println("Ignoring ALKALINE_MSA_REDIRECT_PORT=" + port);
            }
        }
        return new OAuthClient(id == null || id.isBlank() ? DEFAULT_CLIENT_ID : id.strip(), redirectPort,
                "XboxLive.signin offline_access");
    }
}
