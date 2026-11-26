package dk.via.partay.app.web;

import dk.via.partay.app.auth.AuthService;
import dk.via.partay.app.auth.HostSession;
import dk.via.partay.app.web.dto.HostProfileResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Auth", description = "Host login with Spotify (Authorization Code with PKCE)")
public class AuthController {

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @GetMapping("/login")
    @Operation(summary = "Start the Spotify login",
            description = "Open this URL in a browser, not in Swagger. Use 127.0.0.1, not localhost, so the redirect "
                    + "URI matches the one in the Spotify dashboard. Answers 302 to the Spotify consent page.")
    @ApiResponse(responseCode = "302", description = "Redirect to accounts.spotify.com/authorize")
    public ResponseEntity<Void> login() {
        return ResponseEntity.status(HttpStatus.FOUND).location(auth.loginUri()).build();
    }

    @GetMapping("/callback")
    @Operation(summary = "Spotify redirects here after the login",
            description = "Checks the state, exchanges the code, rejects accounts that are not Premium and "
                    + "returns the host profile with a hostSessionId. Send that id as X-Host-Session. "
                    + "400: unknown, expired or used state, or login denied. 403: not Premium.")
    public HostProfileResponse callback(@RequestParam(required = false) String code,
                                        @RequestParam(required = false) String state,
                                        @RequestParam(required = false) String error) {
        return toResponse(auth.completeLogin(code, state, error));
    }

    @GetMapping("/me")
    @Operation(summary = "The logged-in host", description = "401 when the host session is missing or unknown.")
    public HostProfileResponse me(@Parameter(description = "hostSessionId from the login callback")
                                  @RequestHeader(value = "X-Host-Session", required = false) String hostSessionId) {
        return toResponse(auth.session(hostSessionId));
    }

    private static HostProfileResponse toResponse(HostSession s) {
        return new HostProfileResponse(s.id(), s.spotifyUserId(), s.displayName(), s.product());
    }
}
