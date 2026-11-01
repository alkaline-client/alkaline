package alkaline.client.account;

import alkaline.config.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Microsoft account sign-in for Minecraft Java, step by step:
 *
 * <ol>
 *   <li>The player authorizes in their own browser; Microsoft redirects to a one-shot loopback listener with a
 *       code (the {@code state} value guards against foreign redirects).</li>
 *   <li>The code (later, the refresh token) is redeemed for a Microsoft access token.</li>
 *   <li>Xbox Live user authentication, then an XSTS token for Minecraft services.</li>
 *   <li>{@code login_with_xbox} yields the Minecraft access token; the profile endpoint yields name, UUID and
 *       skin, or 404 when the account does not own Minecraft Java.</li>
 * </ol>
 *
 * Endpoints follow Microsoft's and Mojang's published flow (see the Minecraft Wiki's "Microsoft authentication"
 * page). All calls are blocking; run them off the UI thread.
 */
public final class MicrosoftAuth {
    private static final String AUTHORIZE = "https://login.live.com/oauth20_authorize.srf";
    private static final String TOKEN = "https://login.live.com/oauth20_token.srf";
    private static final String XBL = "https://user.auth.xboxlive.com/user/authenticate";
    private static final String XSTS = "https://xsts.auth.xboxlive.com/xsts/authorize";
    private static final String LOGIN_WITH_XBOX = "https://api.minecraftservices.com/authentication/login_with_xbox";
    private static final String PROFILE = "https://api.minecraftservices.com/minecraft/profile";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final long BROWSER_WAIT_MINUTES = 5;

    private final OAuthClient client;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private final SecureRandom random = new SecureRandom();

    /** Result of a completed sign-in: the session and the refresh token to persist for the next launch. */
    public record Result(MinecraftSession session, String refreshToken) {
        public Result {
            Objects.requireNonNull(session, "session");
            Objects.requireNonNull(refreshToken, "refreshToken");
        }

        @Override
        public String toString() {
            return "Result[" + session + "]";
        }
    }

    /** A browser authorization in progress: open {@link #url()}, then wait on {@link #code()}. */
    public static final class Authorization implements AutoCloseable {
        private final HttpServer server;
        private final String url;
        private final CompletableFuture<String> code = new CompletableFuture<>();

        private Authorization(HttpServer server, String url) {
            this.server = server;
            this.url = url;
        }

        public String url() {
            return url;
        }

        /** Completes with the authorization code, or exceptionally when the player denied access. */
        public CompletableFuture<String> code() {
            return code;
        }

        @Override
        public void close() {
            server.stop(0);
            code.cancel(false);
        }
    }

    public MicrosoftAuth(OAuthClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    /** Starts the loopback listener and returns the authorization URL to open in the player's browser. */
    public Authorization authorize() throws AuthException {
        byte[] nonce = new byte[16];
        random.nextBytes(nonce);
        String state = HexFormat.of().formatHex(nonce);
        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), client.redirectPort()), 0);
        } catch (IOException busy) {
            throw new AuthException("Port " + client.redirectPort() + " is in use; close the other sign-in and retry.", busy);
        }
        String url = AUTHORIZE + "?client_id=" + encode(client.clientId())
                + "&response_type=code"
                + "&redirect_uri=" + encode(client.redirectUri())
                + "&scope=" + encode(client.scope())
                + "&state=" + state
                + "&prompt=select_account";
        Authorization authorization = new Authorization(server, url);
        server.createContext("/login", exchange -> handleRedirect(exchange, state, authorization.code));
        server.setExecutor(command -> Thread.ofVirtual().name("alkaline-oauth-redirect").start(command));
        server.start();
        return authorization;
    }

    private static void handleRedirect(HttpExchange exchange, String state, CompletableFuture<String> code) throws IOException {
        Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
        String message;
        int status;
        if (!state.equals(query.get("state"))) {
            status = 400;
            message = "This sign-in link is not from Alkaline. You can close this tab.";
        } else if (query.containsKey("code")) {
            status = 200;
            message = "Signed in. You can close this tab and return to Alkaline.";
            code.complete(query.get("code"));
        } else {
            status = 400;
            String reason = query.getOrDefault("error_description", query.getOrDefault("error", "No code was returned."));
            message = "Sign-in did not finish: " + reason;
            code.completeExceptionally(new AuthException(reason.contains("denied") || "access_denied".equals(query.get("error"))
                    ? "Sign-in was cancelled in the browser." : "Microsoft did not return a code: " + reason));
        }
        byte[] body = ("<!doctype html><meta charset=utf-8><title>Alkaline</title>"
                + "<body style=\"font:16px system-ui;margin:4em;text-align:center\">" + escapeHtml(message))
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    /** Waits for the browser, then completes the sign-in. Blocking. */
    public Result finish(Authorization authorization) throws AuthException {
        String code;
        try {
            code = authorization.code().get(BROWSER_WAIT_MINUTES, TimeUnit.MINUTES);
        } catch (TimeoutException timeout) {
            throw new AuthException("Sign-in timed out waiting for the browser.");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AuthException("Sign-in was cancelled.");
        } catch (java.util.concurrent.CancellationException cancelled) {
            throw new AuthException("Sign-in was cancelled.");
        } catch (java.util.concurrent.ExecutionException failed) {
            throw failed.getCause() instanceof AuthException auth ? auth : new AuthException("Sign-in failed.", failed.getCause());
        } finally {
            authorization.close();
        }
        Map<?, ?> tokens = postForm(TOKEN, Map.of(
                "client_id", client.clientId(),
                "code", code,
                "grant_type", "authorization_code",
                "redirect_uri", client.redirectUri()));
        return minecraft(tokens);
    }

    /** Signs in again from a stored refresh token (which Microsoft rotates). Blocking. */
    public Result refresh(String refreshToken) throws AuthException {
        Map<?, ?> tokens = postForm(TOKEN, Map.of(
                "client_id", client.clientId(),
                "refresh_token", refreshToken,
                "grant_type", "refresh_token",
                "scope", client.scope(),
                "redirect_uri", client.redirectUri()));
        return minecraft(tokens);
    }

    private Result minecraft(Map<?, ?> tokens) throws AuthException {
        String microsoftToken = string(tokens, "access_token", "Microsoft returned no access token.");
        String refreshToken = string(tokens, "refresh_token", "Microsoft returned no refresh token.");

        Map<String, Object> xblProperties = new LinkedHashMap<>();
        xblProperties.put("AuthMethod", "RPS");
        xblProperties.put("SiteName", "user.auth.xboxlive.com");
        xblProperties.put("RpsTicket", "d=" + microsoftToken);
        Map<?, ?> xbl = postJson(XBL, Map.of("Properties", xblProperties,
                "RelyingParty", "http://auth.xboxlive.com", "TokenType", "JWT"), null);
        String xblToken = string(xbl, "Token", "Xbox Live returned no token.");
        String userHash = userHash(xbl);

        Map<String, Object> xstsProperties = new LinkedHashMap<>();
        xstsProperties.put("SandboxId", "RETAIL");
        xstsProperties.put("UserTokens", List.of(xblToken));
        Map<?, ?> xsts = postJson(XSTS, Map.of("Properties", xstsProperties,
                "RelyingParty", "rp://api.minecraftservices.com/", "TokenType", "JWT"), null);
        String xstsToken = string(xsts, "Token", "Xbox Live returned no XSTS token.");

        Map<?, ?> login = postJson(LOGIN_WITH_XBOX,
                Map.of("identityToken", "XBL3.0 x=" + userHash + ";" + xstsToken), null);
        String accessToken = string(login, "access_token", "Minecraft services returned no access token.");
        long expiresIn = login.get("expires_in") instanceof Number number ? number.longValue() : 86_400L;

        MinecraftProfile profile = profile(accessToken);
        return new Result(new MinecraftSession(profile, accessToken, System.currentTimeMillis() + expiresIn * 1000L),
                refreshToken);
    }

    /** The profile behind a Minecraft access token: name, UUID and skin. Blocking. */
    MinecraftProfile profile(String accessToken) throws AuthException {
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(PROFILE)).timeout(TIMEOUT)
                .header("Authorization", "Bearer " + accessToken).GET().build());
        if (response.statusCode() == 404) {
            throw new AuthException("This Microsoft account does not own Minecraft: Java Edition.");
        }
        Map<?, ?> body = parse(response, "Minecraft profile");
        String skin = null;
        if (body.get("skins") instanceof List<?> skins) {
            for (Object entry : skins) {
                if (entry instanceof Map<?, ?> map && "ACTIVE".equals(map.get("state")) && map.get("url") instanceof String url) {
                    skin = url;
                    break;
                }
            }
        }
        return new MinecraftProfile(string(body, "id", "Profile has no id."), string(body, "name", "Profile has no name."), skin);
    }

    private static String userHash(Map<?, ?> xbl) throws AuthException {
        if (xbl.get("DisplayClaims") instanceof Map<?, ?> claims && claims.get("xui") instanceof List<?> xui
                && !xui.isEmpty() && xui.getFirst() instanceof Map<?, ?> first && first.get("uhs") instanceof String hash) {
            return hash;
        }
        throw new AuthException("Xbox Live returned no user hash.");
    }

    private Map<?, ?> postForm(String url, Map<String, String> fields) throws AuthException {
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> field : fields.entrySet()) {
            if (!body.isEmpty()) {
                body.append('&');
            }
            body.append(encode(field.getKey())).append('=').append(encode(field.getValue()));
        }
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build());
        if (response.statusCode() == 400 && response.body().contains("invalid_grant")) {
            throw new AuthException("Your saved sign-in expired. Sign in again.");
        }
        return parse(response, "Microsoft sign-in");
    }

    private Map<?, ?> postJson(String url, Map<String, Object> json, String bearer) throws AuthException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT)
                .header("Content-Type", "application/json").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(json)));
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        HttpResponse<String> response = send(request.build());
        if (url.equals(XSTS) && response.statusCode() == 401) {
            throw new AuthException(xstsError(response.body()));
        }
        if (url.equals(LOGIN_WITH_XBOX) && response.statusCode() == 403) {
            // Minecraft services accept only Azure applications Mojang approved; see OAuthClient.
            throw new AuthException("Minecraft does not accept this sign-in application (" + client.clientId()
                    + "). Sign in through Sulfur, or set ALKALINE_MSA_CLIENT_ID to an approved one.");
        }
        return parse(response, url.equals(LOGIN_WITH_XBOX) ? "Minecraft services" : "Xbox Live");
    }

    /** Player-facing explanations for documented XSTS refusals. */
    static String xstsError(String body) {
        if (body.contains("2148916233")) {
            return "This Microsoft account has no Xbox profile. Create one at xbox.com, then sign in again.";
        }
        if (body.contains("2148916235")) {
            return "Xbox Live is not available in this account's country or region.";
        }
        if (body.contains("2148916236") || body.contains("2148916237")) {
            return "This account needs adult verification on xbox.com before it can play.";
        }
        if (body.contains("2148916238")) {
            return "This is a child account. An adult must add it to a Microsoft family to play.";
        }
        return "Xbox Live refused the sign-in.";
    }

    private HttpResponse<String> send(HttpRequest request) throws AuthException {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException failure) {
            throw new AuthException("Network error during sign-in: " + failure.getMessage(), failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AuthException("Sign-in was cancelled.");
        }
    }

    private static Map<?, ?> parse(HttpResponse<String> response, String service) throws AuthException {
        if (response.statusCode() / 100 != 2) {
            throw new AuthException(service + " refused the sign-in (HTTP " + response.statusCode() + ").");
        }
        try {
            if (Json.parse(response.body()) instanceof Map<?, ?> map) {
                return map;
            }
        } catch (IllegalArgumentException malformed) {
            throw new AuthException(service + " sent an unreadable reply.", malformed);
        }
        throw new AuthException(service + " sent an unexpected reply.");
    }

    private static String string(Map<?, ?> map, String key, String missing) throws AuthException {
        if (map.get(key) instanceof String value && !value.isEmpty()) {
            return value;
        }
        throw new AuthException(missing);
    }

    static Map<String, String> parseQuery(String raw) {
        Map<String, String> result = new LinkedHashMap<>();
        if (raw == null) {
            return result;
        }
        for (String pair : raw.split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0) {
                result.put(URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
            }
        }
        return result;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
