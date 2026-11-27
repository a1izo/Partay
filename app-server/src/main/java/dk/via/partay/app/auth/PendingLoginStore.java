package dk.via.partay.app.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Logins that were started but not finished: OAuth state -> PKCE code verifier.
 * An entry lives 10 minutes and works once. In memory only.
 */
@Component
public class PendingLoginStore {

    public static final Duration TTL = Duration.ofMinutes(10);

    private record Pending(String verifier, Instant expiresAt) {
    }

    private final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();
    private final Clock clock;

    @Autowired
    public PendingLoginStore() {
        this(Clock.systemUTC());
    }

    PendingLoginStore(Clock clock) {
        this.clock = clock;
    }

    public void put(String state, String verifier) {
        Instant now = clock.instant();
        pending.values().removeIf(p -> !now.isBefore(p.expiresAt()));
        pending.put(state, new Pending(verifier, now.plus(TTL)));
    }

    /** Returns the verifier and removes the entry. Empty when the state is unknown, used or expired. */
    public Optional<String> consume(String state) {
        if (state == null) {
            return Optional.empty();
        }
        Pending p = pending.remove(state);
        if (p == null || !clock.instant().isBefore(p.expiresAt())) {
            return Optional.empty();
        }
        return Optional.of(p.verifier());
    }
}
