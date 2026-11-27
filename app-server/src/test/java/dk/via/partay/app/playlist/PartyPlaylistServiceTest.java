package dk.via.partay.app.playlist;

import dk.via.partay.app.auth.UserTokenService;
import dk.via.partay.app.error.ForbiddenException;
import dk.via.partay.app.error.NotFoundException;
import dk.via.partay.app.error.UnauthorizedException;
import dk.via.partay.app.error.UpstreamException;
import dk.via.partay.app.spotify.SpotifyUserClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PartyPlaylistServiceTest {

    private static final String SESSION = "host-session-1";
    private static final String TRACK_ID = "0VjIjW4GlUZAMYd2vXMi3b";

    private SpotifyUserClient spotify;
    private UserTokenService tokens;
    private InMemoryPartyPlaylistStore store;
    private PartyPlaylistService service;

    @BeforeEach
    void setUp() {
        spotify = mock(SpotifyUserClient.class);
        tokens = mock(UserTokenService.class);
        store = new InMemoryPartyPlaylistStore();
        service = new PartyPlaylistService(store, spotify, tokens);
        when(tokens.accessToken(SESSION)).thenReturn("tok");
    }

    @Test
    void createForMakesAPlaylistWithTheRightNameAndDescriptionAndStoresIt() {
        when(spotify.createPlaylist("tok", "Partay: Friday at Sam's", "Made with Partay. Join code K7QX2M"))
                .thenReturn(new SpotifyUserClient.Playlist("pl1", "https://open.spotify.com/playlist/pl1"));

        PartyPlaylist playlist = service.createFor(7, "Friday at Sam's", "K7QX2M", SESSION);

        assertEquals(new PartyPlaylist("pl1", "https://open.spotify.com/playlist/pl1", SESSION), playlist);
        assertEquals(playlist, store.find(7).orElseThrow());
        verify(spotify).createPlaylist("tok", "Partay: Friday at Sam's", "Made with Partay. Join code K7QX2M");
    }

    @Test
    void addTrackSendsTheTrackToThePartyPlaylistWithTheHostToken() {
        store.save(7, new PartyPlaylist("pl1", "url", SESSION));

        service.addTrack(7, TRACK_ID);

        verify(spotify).addTracks("tok", "pl1", List.of(TRACK_ID));
    }

    @Test
    void addTrackDoesNothingWhenThePartyHasNoPlaylist() {
        service.addTrack(7, TRACK_ID);

        verifyNoInteractions(spotify);
        verifyNoInteractions(tokens);
    }

    @Test
    void addTrackPassesASpotifyFailureOnToTheCaller() {
        store.save(7, new PartyPlaylist("pl1", "url", SESSION));
        org.mockito.Mockito.doThrow(new UpstreamException(502, "down")).when(spotify)
                .addTracks("tok", "pl1", List.of(TRACK_ID));
        assertThrows(UpstreamException.class, () -> service.addTrack(7, TRACK_ID));
    }

    @Test
    void requireForHostReturnsThePlaylistToItsHost() {
        PartyPlaylist saved = new PartyPlaylist("pl1", "url", SESSION);
        store.save(7, saved);
        assertEquals(saved, service.requireForHost(7, SESSION));
    }

    @Test
    void requireForHostIsNotFoundWithoutAPlaylist() {
        assertThrows(NotFoundException.class, () -> service.requireForHost(7, SESSION));
    }

    @Test
    void requireForHostIsForbiddenForAnotherHost() {
        store.save(7, new PartyPlaylist("pl1", "url", "someone-else"));
        assertThrows(ForbiddenException.class, () -> service.requireForHost(7, SESSION));
    }

    @Test
    void anUnknownHostSessionIsUnauthorized() {
        when(tokens.accessToken("nope")).thenThrow(new UnauthorizedException("Unknown host session"));
        assertThrows(UnauthorizedException.class, () -> service.requireHost("nope"));
        assertThrows(UnauthorizedException.class, () -> service.requireForHost(7, "nope"));
    }
}
