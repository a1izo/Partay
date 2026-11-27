package dk.via.partay.app.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * {@code host} is only set in the response to creating a party. {@code playlistId} and {@code playlistUrl}
 * are only set when the party was created with a host session and Spotify made the playlist.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PartyResponse(long partyId, String joinCode, String name, Instant createdAt, GuestSummary host,
                            String playlistId, String playlistUrl) {

    public PartyResponse(long partyId, String joinCode, String name, Instant createdAt, GuestSummary host) {
        this(partyId, joinCode, name, createdAt, host, null, null);
    }
}
