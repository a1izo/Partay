package dk.via.partay.app.playlist;

import dk.via.partay.app.auth.UserTokenService;
import dk.via.partay.app.error.ForbiddenException;
import dk.via.partay.app.error.NotFoundException;
import dk.via.partay.app.spotify.SpotifyUserClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * The Spotify playlist of a party, kept on the host's account. The playlist is private and the host
 * keeps it after the party. The queue in Partay stays the source of truth.
 */
@Service
public class PartyPlaylistService {

    private static final Logger log = LoggerFactory.getLogger(PartyPlaylistService.class);

    private final PartyPlaylistStore store;
    private final SpotifyUserClient spotify;
    private final UserTokenService tokens;

    public PartyPlaylistService(PartyPlaylistStore store, SpotifyUserClient spotify, UserTokenService tokens) {
        this.store = store;
        this.spotify = spotify;
        this.tokens = tokens;
    }

    /** @throws dk.via.partay.app.error.UnauthorizedException when the host session is unknown or expired for good */
    public void requireHost(String hostSessionId) {
        tokens.accessToken(hostSessionId);
    }

    /** Creates the private playlist on the host's account and remembers it for the party. */
    public PartyPlaylist createFor(long partyId, String partyName, String joinCode, String hostSessionId) {
        String sessionId = hostSessionId.trim();
        String accessToken = tokens.accessToken(sessionId);
        SpotifyUserClient.Playlist created = spotify.createPlaylist(accessToken, "Partay: " + partyName,
                "Made with Partay. Join code " + joinCode);
        PartyPlaylist playlist = new PartyPlaylist(created.id(), created.url(), sessionId);
        store.save(partyId, playlist);
        log.info("[T2] playlist {} created for party {}", created.id(), partyId);
        return playlist;
    }

    /** Does nothing when the party has no playlist (mock mode, or the party was created without a host login). */
    public void addTrack(long partyId, String trackId) {
        PartyPlaylist playlist = store.find(partyId).orElse(null);
        if (playlist == null) {
            return;
        }
        String accessToken = tokens.accessToken(playlist.hostSessionId());
        spotify.addTracks(accessToken, playlist.playlistId(), List.of(trackId));
    }

    /**
     * The playlist of a party for its host.
     *
     * @throws dk.via.partay.app.error.UnauthorizedException host session unknown
     * @throws NotFoundException                              the party has no playlist
     * @throws ForbiddenException                             the session is not the host of this playlist
     */
    public PartyPlaylist requireForHost(long partyId, String hostSessionId) {
        requireHost(hostSessionId);
        PartyPlaylist playlist = store.find(partyId)
                .orElseThrow(() -> new NotFoundException("This party has no Spotify playlist"));
        if (!playlist.hostSessionId().equals(hostSessionId.trim())) {
            throw new ForbiddenException("Only the host of this party can read its playlist");
        }
        return playlist;
    }
}
