package dk.via.partay.app.playlist;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Party playlists in memory. They are lost on restart. */
@Component
public class InMemoryPartyPlaylistStore implements PartyPlaylistStore {

    private final ConcurrentHashMap<Long, PartyPlaylist> playlists = new ConcurrentHashMap<>();

    @Override
    public void save(long partyId, PartyPlaylist playlist) {
        playlists.put(partyId, playlist);
    }

    @Override
    public Optional<PartyPlaylist> find(long partyId) {
        return Optional.ofNullable(playlists.get(partyId));
    }
}
