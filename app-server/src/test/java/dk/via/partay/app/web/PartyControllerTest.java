package dk.via.partay.app.web;

import dk.via.partay.app.error.ForbiddenException;
import dk.via.partay.app.error.UnauthorizedException;
import dk.via.partay.app.error.UpstreamException;
import dk.via.partay.app.grpc.DataClient;
import dk.via.partay.app.model.GuestView;
import dk.via.partay.app.model.PartyView;
import dk.via.partay.app.playlist.PartyPlaylist;
import dk.via.partay.app.playlist.PartyPlaylistService;
import dk.via.partay.app.service.PartyService;
import dk.via.partay.app.web.dto.CreatePartyRequest;
import dk.via.partay.app.web.dto.PartyResponse;
import dk.via.partay.app.web.dto.PlaylistResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PartyControllerTest {

    private static final String SESSION = "host-session-1";
    private static final CreatePartyRequest REQUEST = new CreatePartyRequest("Friday at Sam's", "Alex");
    private static final PartyView PARTY = new PartyView(7, "K7QX2M", "Friday at Sam's", Instant.now());
    private static final GuestView HOST = new GuestView("1a250000-0000-0000-0000-000000000002", 7, "Alex", "HOST",
            Instant.now());

    private PartyService parties;
    private PartyPlaylistService playlists;
    private PartyController controller;

    @BeforeEach
    void setUp() {
        parties = mock(PartyService.class);
        playlists = mock(PartyPlaylistService.class);
        controller = new PartyController(parties, playlists);
        when(parties.createParty("Friday at Sam's", "Alex")).thenReturn(new DataClient.CreatedParty(PARTY, HOST));
    }

    @Test
    void withoutTheHeaderThePartyIsCreatedAsBeforeAndNoPlaylistIsTouched() {
        PartyResponse r = controller.create(REQUEST, null);

        assertEquals("K7QX2M", r.joinCode());
        assertEquals("Alex", r.host().nickname());
        assertNull(r.playlistId());
        assertNull(r.playlistUrl());
        verifyNoInteractions(playlists);
    }

    @Test
    void withAValidHeaderThePlaylistIsCreatedAndReturned() {
        when(playlists.createFor(7, "Friday at Sam's", "K7QX2M", SESSION))
                .thenReturn(new PartyPlaylist("pl1", "https://open.spotify.com/playlist/pl1", SESSION));

        PartyResponse r = controller.create(REQUEST, SESSION);

        assertEquals("pl1", r.playlistId());
        assertEquals("https://open.spotify.com/playlist/pl1", r.playlistUrl());
        assertEquals("K7QX2M", r.joinCode());
    }

    @Test
    void anUnknownHeaderIsUnauthorizedAndNoPartyIsCreated() {
        doThrow(new UnauthorizedException("Unknown host session")).when(playlists).requireHost("nope");

        assertThrows(UnauthorizedException.class, () -> controller.create(REQUEST, "nope"));

        verify(parties, never()).createParty(anyString(), anyString());
    }

    @Test
    void aSpotifyFailureKeepsTheCreatedPartyWithoutPlaylistFields() {
        when(playlists.createFor(7, "Friday at Sam's", "K7QX2M", SESSION))
                .thenThrow(new UpstreamException(502, "Spotify down"));

        PartyResponse r = controller.create(REQUEST, SESSION);

        assertEquals("K7QX2M", r.joinCode());
        assertNull(r.playlistId());
    }

    @Test
    void thePlaylistEndpointReturnsThePlaylistToItsHost() {
        when(parties.getParty("k7qx2m")).thenReturn(PARTY);
        when(playlists.requireForHost(7, SESSION)).thenReturn(new PartyPlaylist("pl1", "url1", SESSION));

        PlaylistResponse r = controller.playlist("k7qx2m", SESSION);

        assertEquals(new PlaylistResponse("pl1", "url1"), r);
    }

    @Test
    void thePlaylistEndpointWithoutAHeaderIsUnauthorized() {
        assertThrows(UnauthorizedException.class, () -> controller.playlist("K7QX2M", null));
        verifyNoInteractions(parties);
    }

    @Test
    void thePlaylistEndpointIsForbiddenForAnotherHost() {
        when(parties.getParty("K7QX2M")).thenReturn(PARTY);
        when(playlists.requireForHost(7, SESSION)).thenThrow(new ForbiddenException("not the host"));
        assertThrows(ForbiddenException.class, () -> controller.playlist("K7QX2M", SESSION));
    }
}
