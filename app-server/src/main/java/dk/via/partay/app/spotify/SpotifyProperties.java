package dk.via.partay.app.spotify;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param mode        live or mock
 * @param redirectUri where Spotify sends the host after the login. Must match the Spotify dashboard.
 * @param scopes      space separated Spotify scopes the host login asks for
 */
@ConfigurationProperties(prefix = "spotify")
public record SpotifyProperties(String mode, String clientId, String clientSecret, String accountsUrl, String apiUrl,
                                String redirectUri, String scopes) {

    public boolean hasCredentials() {
        return clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank();
    }
}
