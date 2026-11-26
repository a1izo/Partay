package dk.via.partay.app.web.dto;

/**
 * The logged-in host.
 *
 * @param product premium, or null when Spotify does not report it (Development Mode)
 */
public record HostProfileResponse(String hostSessionId, String spotifyUserId, String displayName, String product) {
}
