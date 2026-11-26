package dk.via.partay.app.spotify;

import com.fasterxml.jackson.databind.JsonNode;
import dk.via.partay.app.error.BadRequestException;
import dk.via.partay.app.error.ForbiddenException;
import dk.via.partay.app.error.UnauthorizedException;
import dk.via.partay.app.error.UpstreamException;
import dk.via.partay.app.trace.TraceRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Spotify calls made on behalf of a logged-in host (Authorization Code with PKCE): the code exchange,
 * the token refresh and GET /me. No client secret is sent. Tokens are never logged.
 */
public class SpotifyUserClient {

    private static final Logger log = LoggerFactory.getLogger(SpotifyUserClient.class);

    /** @param refreshToken null when Spotify did not send a new one on a refresh */
    public record UserTokens(String accessToken, String refreshToken, long expiresInSeconds) {
    }

    /** @param product premium, free and so on. Null when Spotify does not report it. */
    public record UserProfile(String id, String displayName, String product) {
    }

    private record Response(int status, String retryAfter, JsonNode body) {
    }

    private final RestClient restClient;
    private final SpotifyProperties props;
    private final TraceRecorder traces;

    public SpotifyUserClient(RestClient restClient, SpotifyProperties props, TraceRecorder traces) {
        this.restClient = restClient;
        this.props = props;
        this.traces = traces;
    }

    public UserTokens exchangeCode(String code, String codeVerifier) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("redirect_uri", props.redirectUri());
        form.add("client_id", props.clientId());
        form.add("code_verifier", codeVerifier);
        Response r = postToken("authorization_code", form);
        String error = r.body().path("error").asText("");
        if (r.status() == 400 && "invalid_grant".equals(error)) {
            throw new BadRequestException("Spotify rejected the login code. Start again at /api/auth/login");
        }
        if (r.status() != 200 || !r.body().hasNonNull("access_token") || !r.body().hasNonNull("refresh_token")) {
            throw new UpstreamException(502, "Spotify token request failed: " + r.status() + " " + error);
        }
        return new UserTokens(r.body().get("access_token").asText(), r.body().get("refresh_token").asText(),
                r.body().path("expires_in").asLong(3600));
    }

    /** Spotify may send a new refresh token. The result holds it, or null when the old one stays valid. */
    public UserTokens refresh(String refreshToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", refreshToken);
        form.add("client_id", props.clientId());
        Response r = postToken("refresh_token", form);
        String error = r.body().path("error").asText("");
        if ((r.status() == 400 || r.status() == 401) && "invalid_grant".equals(error)) {
            throw new UnauthorizedException("Spotify no longer accepts this host session. Log in again at /api/auth/login");
        }
        if (r.status() != 200 || !r.body().hasNonNull("access_token")) {
            throw new UpstreamException(502, "Spotify token refresh failed: " + r.status() + " " + error);
        }
        String newRefresh = r.body().hasNonNull("refresh_token") ? r.body().get("refresh_token").asText() : null;
        return new UserTokens(r.body().get("access_token").asText(), newRefresh,
                r.body().path("expires_in").asLong(3600));
    }

    public UserProfile me(String accessToken) {
        long start = System.nanoTime();
        Response r;
        try {
            r = restClient.get()
                    .uri(props.apiUrl() + "/me")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .exchange((req, res) -> new Response(res.getStatusCode().value(),
                            res.getHeaders().getFirst(HttpHeaders.RETRY_AFTER), LiveSpotifyClient.readJson(res)));
        } catch (ResourceAccessException e) {
            traces.hop("SPOTIFY", "GET /me", "ERROR", (System.nanoTime() - start) / 1_000_000);
            throw new UpstreamException(503, "Spotify unreachable: " + e.getMessage());
        }
        long ms = (System.nanoTime() - start) / 1_000_000;
        log.info("[T2] HTTP -> Spotify GET /me {} ({} ms)", r.status(), ms);
        traces.hop("SPOTIFY", "GET /me", String.valueOf(r.status()), ms);

        String message = r.body().path("error").path("message").asText("");
        if (r.status() == 403) {
            throw new ForbiddenException("Spotify refused this account" + (message.isEmpty() ? "" : ": " + message)
                    + ". In Development Mode the account must be added under User Management in the Spotify dashboard.");
        }
        if (r.status() == 429) {
            String wait = r.retryAfter() == null ? "?" : r.retryAfter();
            throw new UpstreamException(503, "Spotify rate limit, retry in " + wait + " s");
        }
        if (r.status() != 200 || !r.body().hasNonNull("id")) {
            throw new UpstreamException(502, "Spotify returned " + r.status() + " for /me"
                    + (message.isEmpty() ? "" : ": " + message));
        }
        String id = r.body().get("id").asText();
        String displayName = r.body().hasNonNull("display_name") ? r.body().get("display_name").asText() : id;
        String product = r.body().hasNonNull("product") && !r.body().get("product").asText().isBlank()
                ? r.body().get("product").asText() : null;
        return new UserProfile(id, displayName, product);
    }

    private Response postToken(String grantType, MultiValueMap<String, String> form) {
        String hop = "POST /api/token " + grantType;
        long start = System.nanoTime();
        Response r;
        try {
            r = restClient.post()
                    .uri(props.accountsUrl() + "/api/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .exchange((req, res) -> new Response(res.getStatusCode().value(), null, LiveSpotifyClient.readJson(res)));
        } catch (ResourceAccessException e) {
            traces.hop("SPOTIFY", hop, "ERROR", (System.nanoTime() - start) / 1_000_000);
            throw new UpstreamException(503, "Spotify unreachable: " + e.getMessage());
        }
        long ms = (System.nanoTime() - start) / 1_000_000;
        log.info("[T2] HTTP -> Spotify {} {} ({} ms)", hop, r.status(), ms);
        traces.hop("SPOTIFY", hop, String.valueOf(r.status()), ms);
        return r;
    }
}
