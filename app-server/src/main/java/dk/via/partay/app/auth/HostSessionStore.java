package dk.via.partay.app.auth;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Host sessions in memory. They are lost on restart. S2-19 moves the refresh token to the database
 * (spotify_user_id, playlist_id, refresh token stored server-side).
 */
@Component
public class HostSessionStore {

    private final ConcurrentHashMap<String, HostSession> sessions = new ConcurrentHashMap<>();

    public HostSession create(String spotifyUserId, String displayName, String product,
                              String accessToken, String refreshToken, Instant expiresAt) {
        HostSession session = new HostSession(UUID.randomUUID().toString(), spotifyUserId, displayName, product,
                accessToken, refreshToken, expiresAt);
        sessions.put(session.id(), session);
        return session;
    }

    public Optional<HostSession> find(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(sessions.get(id.trim()));
    }

    public void remove(String id) {
        sessions.remove(id);
    }

    public int size() {
        return sessions.size();
    }
}
