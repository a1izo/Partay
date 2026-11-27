package dk.via.partay.app.spotify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.via.partay.app.error.BadRequestException;
import dk.via.partay.app.error.ForbiddenException;
import dk.via.partay.app.error.UnauthorizedException;
import dk.via.partay.app.error.UpstreamException;
import dk.via.partay.app.spotify.SpotifyUserClient.UserProfile;
import dk.via.partay.app.spotify.SpotifyUserClient.UserTokens;
import dk.via.partay.app.trace.RequestTrace;
import dk.via.partay.app.trace.TraceHop;
import dk.via.partay.app.trace.TraceRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SpotifyUserClientTest {

    private static final String REDIRECT = "http://127.0.0.1:8080/api/auth/callback";

    private MockRestServiceServer server;
    private TraceRecorder traces;
    private SpotifyUserClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        traces = new TraceRecorder();
        SpotifyProperties props = new SpotifyProperties("live", "client123", "secret", "https://accounts.spotify.com",
                "https://api.spotify.com/v1", REDIRECT, "user-read-private");
        client = new SpotifyUserClient(builder.build(), props, traces);
    }

    @Test
    void exchangeCodeSendsThePkceFormWithoutAClientSecret() {
        server.expect(requestTo("https://accounts.spotify.com/api/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
                .andExpect(content().formDataContains(Map.of(
                        "grant_type", "authorization_code",
                        "code", "auth-code",
                        "redirect_uri", REDIRECT,
                        "client_id", "client123",
                        "code_verifier", "verifier-1")))
                .andRespond(withSuccess("{\"access_token\":\"a1\",\"refresh_token\":\"r1\",\"expires_in\":3600}",
                        MediaType.APPLICATION_JSON));

        traces.start("GET", "/api/auth/callback", null);
        UserTokens tokens = client.exchangeCode("auth-code", "verifier-1");
        RequestTrace trace = traces.finish(200);

        assertEquals(new UserTokens("a1", "r1", 3600), tokens);
        assertEquals(java.util.List.of("POST /api/token authorization_code"),
                trace.hops().stream().map(TraceHop::name).toList());
        assertEquals("SPOTIFY", trace.hops().get(0).type());
        assertEquals("200", trace.hops().get(0).status());
        server.verify();
    }

    @Test
    void aRejectedCodeIsBadRequest() {
        server.expect(requestTo("https://accounts.spotify.com/api/token"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"invalid_grant\",\"error_description\":\"Invalid authorization code\"}"));
        assertThrows(BadRequestException.class, () -> client.exchangeCode("bad", "verifier-1"));
    }

    @Test
    void refreshSendsTheRefreshFormAndKeepsNullWhenNoNewRefreshTokenComes() {
        server.expect(requestTo("https://accounts.spotify.com/api/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
                .andExpect(content().formDataContains(Map.of(
                        "grant_type", "refresh_token",
                        "refresh_token", "r1",
                        "client_id", "client123")))
                .andRespond(withSuccess("{\"access_token\":\"a2\",\"expires_in\":3600}", MediaType.APPLICATION_JSON));

        UserTokens tokens = client.refresh("r1");

        assertEquals(new UserTokens("a2", null, 3600), tokens);
        server.verify();
    }

    @Test
    void refreshReturnsANewRefreshTokenWhenSpotifySendsOne() {
        server.expect(requestTo("https://accounts.spotify.com/api/token"))
                .andRespond(withSuccess("{\"access_token\":\"a2\",\"refresh_token\":\"r2\",\"expires_in\":1800}",
                        MediaType.APPLICATION_JSON));
        assertEquals(new UserTokens("a2", "r2", 1800), client.refresh("r1"));
    }

    @Test
    void aRevokedRefreshTokenIsUnauthorized() {
        server.expect(requestTo("https://accounts.spotify.com/api/token"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"invalid_grant\",\"error_description\":\"Refresh token revoked\"}"));
        assertThrows(UnauthorizedException.class, () -> client.refresh("r1"));
    }

    @Test
    void meSendsTheBearerTokenAndReadsTheProfile() {
        server.expect(requestTo("https://api.spotify.com/v1/me"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer tok"))
                .andRespond(withSuccess("{\"id\":\"u1\",\"display_name\":\"Alex\",\"product\":\"premium\"}",
                        MediaType.APPLICATION_JSON));

        traces.start("GET", "/api/auth/callback", null);
        UserProfile profile = client.me("tok");
        RequestTrace trace = traces.finish(200);

        assertEquals(new UserProfile("u1", "Alex", "premium"), profile);
        assertEquals("GET /me", trace.hops().get(0).name());
        server.verify();
    }

    @Test
    void meWithoutProductOrDisplayNameFallsBack() {
        server.expect(requestTo("https://api.spotify.com/v1/me"))
                .andRespond(withSuccess("{\"id\":\"u1\",\"display_name\":null}", MediaType.APPLICATION_JSON));
        UserProfile profile = client.me("tok");
        assertEquals("u1", profile.displayName());
        assertNull(profile.product());
    }

    @Test
    void meForAnAccountNotInTheDashboardIsForbidden() {
        server.expect(requestTo("https://api.spotify.com/v1/me"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"status\":403,\"message\":\"User not registered in the Developer Dashboard\"}}"));
        ForbiddenException e = assertThrows(ForbiddenException.class, () -> client.me("tok"));
        assertEquals(true, e.getMessage().contains("User not registered in the Developer Dashboard"));
    }

    private static JsonNode jsonBody(org.springframework.http.client.ClientHttpRequest request) throws IOException {
        return new ObjectMapper().readTree(((MockClientHttpRequest) request).getBodyAsString());
    }

    @Test
    void createPlaylistSendsAPrivatePlaylistWithNameAndDescription() {
        server.expect(requestTo("https://api.spotify.com/v1/me/playlists"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer tok"))
                .andExpect(request -> {
                    JsonNode body = jsonBody(request);
                    assertEquals("Partay: Friday", body.get("name").asText());
                    assertEquals("Made with Partay. Join code K7QX2M", body.get("description").asText());
                    assertTrue(body.has("public"));
                    assertEquals(false, body.get("public").asBoolean(true));
                })
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"id\":\"pl123\",\"external_urls\":{\"spotify\":\"https://open.spotify.com/playlist/pl123\"}}"));

        traces.start("POST", "/api/parties", null);
        SpotifyUserClient.Playlist playlist = client.createPlaylist("tok", "Partay: Friday",
                "Made with Partay. Join code K7QX2M");
        RequestTrace trace = traces.finish(201);

        assertEquals(new SpotifyUserClient.Playlist("pl123", "https://open.spotify.com/playlist/pl123"), playlist);
        assertEquals("POST /me/playlists", trace.hops().get(0).name());
        assertEquals("201", trace.hops().get(0).status());
        server.verify();
    }

    @Test
    void createPlaylistWithAnExpiredTokenIsUnauthorized() {
        server.expect(requestTo("https://api.spotify.com/v1/me/playlists"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"status\":401,\"message\":\"The access token expired\"}}"));
        assertThrows(UnauthorizedException.class, () -> client.createPlaylist("tok", "n", "d"));
    }

    @Test
    void createPlaylistWhenRateLimitedIsUpstream503() {
        server.expect(requestTo("https://api.spotify.com/v1/me/playlists"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header(HttpHeaders.RETRY_AFTER, "7"));
        UpstreamException e = assertThrows(UpstreamException.class, () -> client.createPlaylist("tok", "n", "d"));
        assertEquals(503, e.getStatus());
        assertTrue(e.getMessage().contains("7"));
    }

    @Test
    void addTracksSendsTrackUrisToTheItemsEndpoint() {
        server.expect(requestTo("https://api.spotify.com/v1/playlists/pl123/items"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer tok"))
                .andExpect(request -> {
                    JsonNode uris = jsonBody(request).get("uris");
                    assertEquals(2, uris.size());
                    assertEquals("spotify:track:0VjIjW4GlUZAMYd2vXMi3b", uris.get(0).asText());
                    assertEquals("spotify:track:11dFghVXANMlKmJXsNCbNl", uris.get(1).asText());
                })
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"snapshot_id\":\"abc\"}"));

        client.addTracks("tok", "pl123", List.of("0VjIjW4GlUZAMYd2vXMi3b", "11dFghVXANMlKmJXsNCbNl"));

        server.verify();
    }

    @Test
    void addTracksSplitsMoreThan100TracksIntoSeveralRequests() {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            ids.add(String.format("%022d", i));
        }
        server.expect(requestTo("https://api.spotify.com/v1/playlists/pl123/items"))
                .andExpect(request -> assertEquals(100, jsonBody(request).get("uris").size()))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body("{}"));
        server.expect(requestTo("https://api.spotify.com/v1/playlists/pl123/items"))
                .andExpect(request -> assertEquals(1, jsonBody(request).get("uris").size()))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body("{}"));

        client.addTracks("tok", "pl123", ids);

        server.verify();
    }

    @Test
    void addTracksToAPlaylistOfAnotherAccountIsForbidden() {
        server.expect(requestTo("https://api.spotify.com/v1/playlists/pl123/items"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"status\":403,\"message\":\"Insufficient client scope\"}}"));
        ForbiddenException e = assertThrows(ForbiddenException.class,
                () -> client.addTracks("tok", "pl123", List.of("0VjIjW4GlUZAMYd2vXMi3b")));
        assertTrue(e.getMessage().contains("Insufficient client scope"));
    }

    @Test
    void addTracksRejectsAPlaylistIdThatIsNotAnId() {
        assertThrows(IllegalArgumentException.class,
                () -> client.addTracks("tok", "../me", List.of("0VjIjW4GlUZAMYd2vXMi3b")));
    }
}
