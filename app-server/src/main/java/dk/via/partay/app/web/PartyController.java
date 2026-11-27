package dk.via.partay.app.web;

import dk.via.partay.app.error.UnauthorizedException;
import dk.via.partay.app.grpc.DataClient;
import dk.via.partay.app.model.GuestView;
import dk.via.partay.app.model.PartyView;
import dk.via.partay.app.playlist.PartyPlaylist;
import dk.via.partay.app.playlist.PartyPlaylistService;
import dk.via.partay.app.service.PartyService;
import dk.via.partay.app.web.dto.CreatePartyRequest;
import dk.via.partay.app.web.dto.GuestSummary;
import dk.via.partay.app.web.dto.JoinPartyRequest;
import dk.via.partay.app.web.dto.JoinPartyResponse;
import dk.via.partay.app.web.dto.PartyResponse;
import dk.via.partay.app.web.dto.PlaylistResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/parties")
@Tag(name = "Parties", description = "Create a party and join it")
public class PartyController {

    private static final Logger log = LoggerFactory.getLogger(PartyController.class);

    private final PartyService parties;
    private final PartyPlaylistService playlists;

    public PartyController(PartyService parties, PartyPlaylistService playlists) {
        this.parties = parties;
        this.playlists = playlists;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a party",
            description = "The host gets a 6-character join code and a guestId. With the X-Host-Session header "
                    + "the party also gets a private playlist on the host's Spotify account, and the response "
                    + "carries playlistId and playlistUrl. Without the header there is no playlist and the "
                    + "response has no playlist fields. If Spotify fails after the party exists, the party is "
                    + "kept and the playlist fields are missing. 401: unknown host session.")
    public PartyResponse create(
            @Valid @RequestBody CreatePartyRequest request,
            @Parameter(description = "Optional. hostSessionId from the Spotify login (/api/auth/login).")
            @RequestHeader(value = "X-Host-Session", required = false) String hostSessionId) {
        if (hostSessionId != null) {
            playlists.requireHost(hostSessionId); // 401 before any party exists
        }
        DataClient.CreatedParty created = parties.createParty(request.name(), request.hostNickname());
        PartyView p = created.party();
        GuestView host = created.host();
        GuestSummary hostSummary = new GuestSummary(host.id(), host.nickname(), host.role());

        PartyPlaylist playlist = null;
        if (hostSessionId != null) {
            try {
                playlist = playlists.createFor(p.id(), p.name(), p.joinCode(), hostSessionId);
            } catch (RuntimeException e) {
                // The party is already stored. Keep it and let the host see that no playlist exists.
                log.warn("[T2] party {} created, but its Spotify playlist failed: {}", p.id(), e.getMessage());
            }
        }
        return new PartyResponse(p.id(), p.joinCode(), p.name(), p.createdAt(), hostSummary,
                playlist == null ? null : playlist.playlistId(), playlist == null ? null : playlist.playlistUrl());
    }

    @GetMapping("/{joinCode}")
    @Operation(summary = "Look up a party by join code", description = "Join codes are case-insensitive.")
    public PartyResponse get(@PathVariable String joinCode) {
        PartyView p = parties.getParty(joinCode);
        return new PartyResponse(p.id(), p.joinCode(), p.name(), p.createdAt(), null);
    }

    @GetMapping("/{joinCode}/playlist")
    @Operation(summary = "The Spotify playlist of a party (host only)",
            description = "Send the hostSessionId of the host who created the party as X-Host-Session.")
    @ApiResponse(responseCode = "401", description = "Missing or unknown host session")
    @ApiResponse(responseCode = "403", description = "The session is not the host of this party")
    @ApiResponse(responseCode = "404", description = "No party with this join code, or the party has no playlist")
    public PlaylistResponse playlist(
            @PathVariable String joinCode,
            @Parameter(description = "hostSessionId from the Spotify login")
            @RequestHeader(value = "X-Host-Session", required = false) String hostSessionId) {
        if (hostSessionId == null) {
            throw new UnauthorizedException("Header X-Host-Session is required");
        }
        playlists.requireHost(hostSessionId); // 401 before the party lookup
        PartyView p = parties.getParty(joinCode);
        PartyPlaylist playlist = playlists.requireForHost(p.id(), hostSessionId);
        return new PlaylistResponse(playlist.playlistId(), playlist.playlistUrl());
    }

    @PostMapping("/{joinCode}/guests")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Join a party with a nickname", description = "Returns the guestId to send as X-Guest-Id.")
    public JoinPartyResponse join(@PathVariable String joinCode, @Valid @RequestBody JoinPartyRequest request) {
        PartyService.Member m = parties.join(joinCode, request.nickname());
        return new JoinPartyResponse(m.guest().id(), m.guest().nickname(), m.guest().role(), m.party().id(),
                m.party().joinCode());
    }
}
