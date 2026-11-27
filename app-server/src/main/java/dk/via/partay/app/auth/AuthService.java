package dk.via.partay.app.auth;

import dk.via.partay.app.error.BadRequestException;
import dk.via.partay.app.error.ForbiddenException;
import dk.via.partay.app.error.UnauthorizedException;
import dk.via.partay.app.error.UpstreamException;
import dk.via.partay.app.spotify.SpotifyProperties;
import dk.via.partay.app.spotify.SpotifyUserClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;

/** Host login with Spotify: Authorization Code with PKCE. */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final SpotifyProperties props;
    private final PkceGenerator pkce;
    private final PendingLoginStore pendingLogins;
    private final HostSessionStore sessions;
    private final SpotifyUserClient spotify;
    private final Clock clock;

    @Autowired
    public AuthService(SpotifyProperties props, PkceGenerator pkce, PendingLoginStore pendingLogins,
                       HostSessionStore sessions, SpotifyUserClient spotify) {
        this(props, pkce, pendingLogins, sessions, spotify, Clock.systemUTC());
    }

    AuthService(SpotifyProperties props, PkceGenerator pkce, PendingLoginStore pendingLogins,
                HostSessionStore sessions, SpotifyUserClient spotify, Clock clock) {
        this.props = props;
        this.pkce = pkce;
        this.pendingLogins = pendingLogins;
        this.sessions = sessions;
        this.spotify = spotify;
        this.clock = clock;
    }

    /** Starts a login: stores a new verifier under a new state and returns the Spotify consent URL. */
    public URI loginUri() {
        if (props.clientId() == null || props.clientId().isBlank()) {
            throw new UpstreamException(503, "Spotify client id is not configured (SPOTIFY_CLIENT_ID)");
        }
        String verifier = pkce.codeVerifier();
        String state = pkce.state();
        pendingLogins.put(state, verifier);
        // encode() before expanding: the values are encoded strictly, so the spaces in scope become %20.
        return UriComponentsBuilder.fromUriString(props.accountsUrl() + "/authorize")
                .queryParam("client_id", "{clientId}")
                .queryParam("response_type", "code")
                .queryParam("redirect_uri", "{redirectUri}")
                .queryParam("code_challenge_method", "S256")
                .queryParam("code_challenge", "{challenge}")
                .queryParam("state", "{state}")
                .queryParam("scope", "{scope}")
                .encode()
                .buildAndExpand(Map.of(
                        "clientId", props.clientId(),
                        "redirectUri", props.redirectUri(),
                        "challenge", pkce.codeChallenge(verifier),
                        "state", state,
                        "scope", props.scopes()))
                .toUri();
    }

    /**
     * Finishes a login. The state is used up even when Spotify reports an error, so a state never works twice.
     *
     * @throws BadRequestException  unknown, expired or used state, a missing code, or a login Spotify did not complete
     * @throws ForbiddenException   the account is not Premium
     */
    public HostSession completeLogin(String code, String state, String error) {
        Optional<String> verifier = state == null || state.isBlank() ? Optional.empty() : pendingLogins.consume(state);
        if (error != null && !error.isBlank()) {
            throw new BadRequestException("Spotify login was not completed: " + error);
        }
        if (verifier.isEmpty()) {
            throw new BadRequestException("Unknown, expired or already used login state. Start again at /api/auth/login");
        }
        if (code == null || code.isBlank()) {
            throw new BadRequestException("Query parameter code is required");
        }

        SpotifyUserClient.UserTokens tokens = spotify.exchangeCode(code, verifier.get());
        SpotifyUserClient.UserProfile profile = spotify.me(tokens.accessToken());

        // Spotify's February 2026 Development Mode changes removed "product" from GET /me. When it is
        // missing we cannot check, so the login goes through: the app owner must be Premium in
        // Development Mode anyway. When it is there and is not premium, the host is rejected.
        if (profile.product() != null && !"premium".equalsIgnoreCase(profile.product())) {
            throw new ForbiddenException("Spotify Premium is required to host");
        }
        if (profile.product() == null) {
            log.warn("[T2] Spotify did not report a product for {}, Premium not verified", profile.id());
        }

        HostSession session = sessions.create(profile.id(), profile.displayName(), profile.product(),
                tokens.accessToken(), tokens.refreshToken(), clock.instant().plusSeconds(tokens.expiresInSeconds()));
        log.info("[T2] Host logged in: Spotify user {} ({})", profile.id(),
                profile.product() == null ? "product unknown" : profile.product());
        return session;
    }

    /** @throws UnauthorizedException when the host session id is missing or unknown */
    public HostSession session(String hostSessionId) {
        return sessions.find(hostSessionId).orElseThrow(() -> new UnauthorizedException("Unknown host session"));
    }
}
