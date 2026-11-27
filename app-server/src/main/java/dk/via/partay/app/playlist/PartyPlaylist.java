package dk.via.partay.app.playlist;

/**
 * The Spotify playlist of one party.
 *
 * @param hostSessionId the host session whose Spotify account owns the playlist
 */
public record PartyPlaylist(String playlistId, String playlistUrl, String hostSessionId) {
}
