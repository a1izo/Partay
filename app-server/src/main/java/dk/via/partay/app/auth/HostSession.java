package dk.via.partay.app.auth;

import java.time.Instant;

/**
 * A logged-in host. Holds the Spotify tokens, so it has no toString and must never be logged.
 * Token fields change on refresh; callers lock on the session object (see {@link UserTokenService}).
 */
public final class HostSession {

    private final String id;
    private final String spotifyUserId;
    private final String displayName;
    private final String product;

    private String accessToken;
    private String refreshToken;
    private Instant expiresAt;

    HostSession(String id, String spotifyUserId, String displayName, String product,
                String accessToken, String refreshToken, Instant expiresAt) {
        this.id = id;
        this.spotifyUserId = spotifyUserId;
        this.displayName = displayName;
        this.product = product;
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.expiresAt = expiresAt;
    }

    public String id() {
        return id;
    }

    public String spotifyUserId() {
        return spotifyUserId;
    }

    public String displayName() {
        return displayName;
    }

    /** premium, or null when Spotify did not say. */
    public String product() {
        return product;
    }

    String accessToken() {
        return accessToken;
    }

    String refreshToken() {
        return refreshToken;
    }

    Instant expiresAt() {
        return expiresAt;
    }

    void update(String accessToken, String refreshToken, Instant expiresAt) {
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.expiresAt = expiresAt;
    }
}
