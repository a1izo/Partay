package dk.via.partay.app.auth;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/** PKCE (RFC 7636) helpers: code verifier, S256 code challenge and the random OAuth state. */
@Component
public class PkceGenerator {

    public static final int VERIFIER_LENGTH = 64;
    private static final int STATE_BYTES = 32;
    /** The RFC 7636 "unreserved" characters: letters, digits, hyphen, dot, underscore, tilde. */
    private static final String UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~";

    private final SecureRandom random = new SecureRandom();

    public String codeVerifier() {
        StringBuilder verifier = new StringBuilder(VERIFIER_LENGTH);
        for (int i = 0; i < VERIFIER_LENGTH; i++) {
            verifier.append(UNRESERVED.charAt(random.nextInt(UNRESERVED.length())));
        }
        return verifier.toString();
    }

    /** base64url(SHA-256(verifier)) without padding. */
    public String codeChallenge(String verifier) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** 32 random bytes as base64url, 43 characters. */
    public String state() {
        byte[] bytes = new byte[STATE_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
