package dk.via.partay.app.auth;

import dk.via.partay.app.error.UnauthorizedException;
import dk.via.partay.app.spotify.SpotifyUserClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;

/**
 * Hands out a valid Spotify access token for a host session and refreshes it 60 seconds before it
 * expires. Later tasks (playlist creation, playback) call {@link #accessToken(String)}.
 */
@Service
public class UserTokenService {

    static final long REFRESH_MARGIN_SECONDS = 60;

    private final HostSessionStore sessions;
    private final SpotifyUserClient spotify;
    private final Clock clock;

    @Autowired
    public UserTokenService(HostSessionStore sessions, SpotifyUserClient spotify) {
        this(sessions, spotify, Clock.systemUTC());
    }

    UserTokenService(HostSessionStore sessions, SpotifyUserClient spotify, Clock clock) {
        this.sessions = sessions;
        this.spotify = spotify;
        this.clock = clock;
    }

    /** @throws UnauthorizedException when the session is unknown or Spotify no longer accepts its refresh token */
    public String accessToken(String hostSessionId) {
        HostSession session = sessions.find(hostSessionId)
                .orElseThrow(() -> new UnauthorizedException("Unknown host session"));
        synchronized (session) {
            Instant refreshAt = session.expiresAt().minusSeconds(REFRESH_MARGIN_SECONDS);
            if (!clock.instant().isBefore(refreshAt)) {
                refresh(session);
            }
            return session.accessToken();
        }
    }

    private void refresh(HostSession session) {
        try {
            SpotifyUserClient.UserTokens tokens = spotify.refresh(session.refreshToken());
            String refreshToken = tokens.refreshToken() != null ? tokens.refreshToken() : session.refreshToken();
            session.update(tokens.accessToken(), refreshToken, clock.instant().plusSeconds(tokens.expiresInSeconds()));
        } catch (UnauthorizedException e) {
            sessions.remove(session.id());
            throw e;
        }
    }
}
