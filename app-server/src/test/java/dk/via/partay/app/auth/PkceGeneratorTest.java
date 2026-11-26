package dk.via.partay.app.auth;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PkceGeneratorTest {

    private final PkceGenerator pkce = new PkceGenerator();

    @Test
    void verifierHas64CharactersFromTheUnreservedSet() {
        for (int i = 0; i < 200; i++) {
            String verifier = pkce.codeVerifier();
            assertEquals(64, verifier.length());
            assertTrue(verifier.matches("[A-Za-z0-9\\-._~]{64}"), verifier);
        }
    }

    @Test
    void verifiersAreUnique() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            assertTrue(seen.add(pkce.codeVerifier()));
        }
    }

    @Test
    void challengeMatchesTheKnownVector() {
        assertEquals("FOYyUd1-3LRLlAXZUC8pI4NkautdU9t9OQylXhS2Y7I",
                pkce.codeChallenge("dBjftJeZ4CVP-mJ92K9JWsFhG2r6bq0DWO8wWZFR2mM"));
    }

    @Test
    void challengeIsBase64UrlWithoutPadding() {
        for (int i = 0; i < 200; i++) {
            String challenge = pkce.codeChallenge(pkce.codeVerifier());
            assertEquals(43, challenge.length());
            assertFalse(challenge.contains("="));
            assertFalse(challenge.contains("+"));
            assertFalse(challenge.contains("/"));
        }
    }

    @Test
    void statesAreUniqueAndUrlSafe() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String state = pkce.state();
            assertTrue(state.matches("[A-Za-z0-9_-]{43}"), state);
            assertTrue(seen.add(state));
        }
    }
}
