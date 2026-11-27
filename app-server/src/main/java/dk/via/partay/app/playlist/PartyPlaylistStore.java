package dk.via.partay.app.playlist;

import java.util.Optional;

/**
 * Maps a party to its Spotify playlist. In memory until S2-19 adds playlist_id and spotify_user_id to
 * the schema. The swap to gRPC is one new implementation of this interface.
 */
public interface PartyPlaylistStore {

    void save(long partyId, PartyPlaylist playlist);

    Optional<PartyPlaylist> find(long partyId);
}
