package dk.via.partay.app.spotify;

import dk.via.partay.app.error.BadRequestException;
import dk.via.partay.app.error.ForbiddenException;
import dk.via.partay.app.error.UnauthorizedException;
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
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
}
