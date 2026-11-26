package dk.via.partay.app.auth;

import dk.via.partay.app.error.BadRequestException;
import dk.via.partay.app.error.ForbiddenException;
import dk.via.partay.app.error.UnauthorizedException;
import dk.via.partay.app.error.UpstreamException;
import dk.via.partay.app.spotify.SpotifyProperties;
import dk.via.partay.app.spotify.SpotifyUserClient;
import dk.via.partay.app.spotify.SpotifyUserClient.UserProfile;
import dk.via.partay.app.spotify.SpotifyUserClient.UserTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

/** The Spotify HTTP layer is a mock: SpotifyUserClient. Its own HTTP behaviour is in SpotifyUserClientTest. */
class AuthServiceTest {

    private static final String CLIENT_ID = "client123";
    private static final String REDIRECT = "http://127.0.0.1:8080/api/auth/callback";
    private static final String SCOPES = "user-read-private playlist-modify-private user-modify-playback-state";

    private SpotifyUserClient spotify;
    private MutableClock clock;
    private PkceGenerator pkce;
    private HostSessionStore sessions;
    private AuthService auth;
    private UserTokenService tokens;

    @BeforeEach
    void setUp() {
        spotify = mock(SpotifyUserClient.class);
        clock = new MutableClock(Instant.parse("2026-11-26T10:00:00Z"));
        pkce = new PkceGenerator();
        sessions = new HostSessionStore();
        auth = newAuthService(CLIENT_ID);
        tokens = new UserTokenService(sessions, spotify, clock);
    }

    private AuthService newAuthService(String clientId) {
        SpotifyProperties props = new SpotifyProperties("live", clientId, "secret", "https://accounts.spotify.com",
                "https://api.spotify.com/v1", REDIRECT, SCOPES);
        return new AuthService(props, pkce, new PendingLoginStore(clock), sessions, spotify, clock);
    }

    private static MultiValueMap<String, String> params(URI uri) {
        return UriComponentsBuilder.fromUri(uri).build().getQueryParams();
    }

    private static String decoded(URI uri, String name) {
        return UriUtils.decode(params(uri).getFirst(name), StandardCharsets.UTF_8);
    }

    private String startLogin() {
        return params(auth.loginUri()).getFirst("state");
    }

    private HostSession login(String product, long expiresInSeconds) {
        when(spotify.exchangeCode(eq("auth-code"), anyString()))
                .thenReturn(new UserTokens("access-1", "refresh-1", expiresInSeconds));
        when(spotify.me("access-1")).thenReturn(new UserProfile("user1", "Alex", product));
        return auth.completeLogin("auth-code", startLogin(), null);
    }

    @Test
    void loginUriCarriesTheAuthorizationParameters() {
        URI uri = auth.loginUri();
        assertEquals("accounts.spotify.com", uri.getHost());
        assertEquals("/authorize", uri.getPath());
        assertEquals(CLIENT_ID, decoded(uri, "client_id"));
        assertEquals("code", decoded(uri, "response_type"));
        assertEquals(REDIRECT, decoded(uri, "redirect_uri"));
        assertEquals("S256", decoded(uri, "code_challenge_method"));
        assertEquals(SCOPES, decoded(uri, "scope"));
        assertEquals(43, decoded(uri, "code_challenge").length());
        assertEquals(43, decoded(uri, "state").length());
    }

    @Test
    void loginWithoutClientIdIsServiceUnavailable() {
        UpstreamException e = assertThrows(UpstreamException.class, () -> newAuthService(" ").loginUri());
        assertEquals(503, e.getStatus());
    }

    @Test
    void callbackSendsTheVerifierBehindTheChallenge() {
        URI uri = auth.loginUri();
        when(spotify.exchangeCode(eq("auth-code"), anyString())).thenReturn(new UserTokens("access-1", "refresh-1", 3600));
        when(spotify.me("access-1")).thenReturn(new UserProfile("user1", "Alex", "premium"));

        auth.completeLogin("auth-code", decoded(uri, "state"), null);

        ArgumentCaptor<String> verifier = ArgumentCaptor.forClass(String.class);
        verify(spotify).exchangeCode(eq("auth-code"), verifier.capture());
        assertEquals(decoded(uri, "code_challenge"), pkce.codeChallenge(verifier.getValue()));
    }

    @Test
    void premiumHostGetsASession() {
        HostSession session = login("premium", 3600);
        assertNotNull(session.id());
        assertEquals("user1", session.spotifyUserId());
        assertEquals("Alex", session.displayName());
        assertEquals("premium", session.product());
        assertEquals(session.id(), auth.session(session.id()).id());
    }

    @Test
    void nonPremiumIsForbiddenAndGetsNoSession() {
        ForbiddenException e = assertThrows(ForbiddenException.class, () -> login("free", 3600));
        assertEquals("Spotify Premium is required to host", e.getMessage());
        assertEquals(0, sessions.size());
    }

    @Test
    void missingProductIsAllowedBecauseSpotifyDoesNotReportItInDevelopmentMode() {
        HostSession session = login(null, 3600);
        assertNull(session.product());
        assertEquals(1, sessions.size());
    }

    @Test
    void unknownStateIsBadRequestAndNothingIsSentToSpotify() {
        assertThrows(BadRequestException.class, () -> auth.completeLogin("auth-code", "made-up", null));
        assertThrows(BadRequestException.class, () -> auth.completeLogin("auth-code", null, null));
        verifyNoInteractions(spotify);
    }

    @Test
    void aStateWorksOnlyOnce() {
        when(spotify.exchangeCode(eq("auth-code"), anyString())).thenReturn(new UserTokens("access-1", "refresh-1", 3600));
        when(spotify.me("access-1")).thenReturn(new UserProfile("user1", "Alex", "premium"));
        String state = startLogin();

        auth.completeLogin("auth-code", state, null);
        assertThrows(BadRequestException.class, () -> auth.completeLogin("auth-code", state, null));
        verify(spotify, times(1)).exchangeCode(anyString(), anyString());
    }

    @Test
    void anExpiredStateIsBadRequest() {
        String state = startLogin();
        clock.advance(Duration.ofMinutes(10).plusSeconds(1));
        assertThrows(BadRequestException.class, () -> auth.completeLogin("auth-code", state, null));
        verifyNoInteractions(spotify);
    }

    @Test
    void aDeniedLoginIsBadRequestAndBurnsTheState() {
        String state = startLogin();
        BadRequestException e = assertThrows(BadRequestException.class,
                () -> auth.completeLogin(null, state, "access_denied"));
        assertTrue(e.getMessage().contains("access_denied"));
        assertThrows(BadRequestException.class, () -> auth.completeLogin("auth-code", state, null));
        verifyNoInteractions(spotify);
    }

    @Test
    void aMissingCodeIsBadRequest() {
        String state = startLogin();
        assertThrows(BadRequestException.class, () -> auth.completeLogin(" ", state, null));
        verifyNoInteractions(spotify);
    }

    @Test
    void unknownOrMissingHostSessionIsUnauthorized() {
        assertThrows(UnauthorizedException.class, () -> auth.session("nope"));
        assertThrows(UnauthorizedException.class, () -> auth.session(null));
    }

    @Test
    void tokenIsReusedBeforeTheRefreshWindow() {
        HostSession session = login("premium", 3600);
        clock.advance(Duration.ofSeconds(3539));

        assertEquals("access-1", tokens.accessToken(session.id()));
        verify(spotify, never()).refresh(anyString());
    }

    @Test
    void tokenIsRefreshedNearExpiryAndTheNewRefreshTokenIsKept() {
        HostSession session = login("premium", 3600);
        when(spotify.refresh("refresh-1")).thenReturn(new UserTokens("access-2", "refresh-2", 3600));
        when(spotify.refresh("refresh-2")).thenReturn(new UserTokens("access-3", "refresh-3", 3600));

        clock.advance(Duration.ofSeconds(3541));
        assertEquals("access-2", tokens.accessToken(session.id()));
        assertEquals("access-2", tokens.accessToken(session.id()));
        verify(spotify, times(1)).refresh("refresh-1");

        clock.advance(Duration.ofSeconds(3541));
        assertEquals("access-3", tokens.accessToken(session.id()));
        verify(spotify).refresh("refresh-2");
    }

    @Test
    void theOldRefreshTokenStaysWhenSpotifyDoesNotSendANewOne() {
        HostSession session = login("premium", 3600);
        when(spotify.refresh("refresh-1")).thenReturn(new UserTokens("access-2", null, 3600));

        clock.advance(Duration.ofSeconds(3541));
        tokens.accessToken(session.id());
        clock.advance(Duration.ofSeconds(3541));
        tokens.accessToken(session.id());

        verify(spotify, times(2)).refresh("refresh-1");
    }

    @Test
    void aRejectedRefreshTokenEndsTheSession() {
        HostSession session = login("premium", 3600);
        when(spotify.refresh("refresh-1")).thenThrow(new UnauthorizedException("Spotify no longer accepts this host session"));

        clock.advance(Duration.ofSeconds(3541));
        assertThrows(UnauthorizedException.class, () -> tokens.accessToken(session.id()));
        assertTrue(sessions.find(session.id()).isEmpty());
    }

    @Test
    void accessTokenForAnUnknownSessionIsUnauthorized() {
        assertThrows(UnauthorizedException.class, () -> tokens.accessToken("nope"));
    }
}
