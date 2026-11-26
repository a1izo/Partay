package dk.via.partay.app.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PendingLoginStoreTest {

    private MutableClock clock;
    private PendingLoginStore store;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-11-26T10:00:00Z"));
        store = new PendingLoginStore(clock);
    }

    @Test
    void consumeReturnsTheVerifierOnce() {
        store.put("state-1", "verifier-1");
        assertEquals(Optional.of("verifier-1"), store.consume("state-1"));
        assertEquals(Optional.empty(), store.consume("state-1"));
    }

    @Test
    void unknownOrNullStateIsEmpty() {
        assertEquals(Optional.empty(), store.consume("nope"));
        assertEquals(Optional.empty(), store.consume(null));
    }

    @Test
    void entryWorksJustBeforeTenMinutes() {
        store.put("state-1", "verifier-1");
        clock.advance(Duration.ofMinutes(9).plusSeconds(59));
        assertEquals(Optional.of("verifier-1"), store.consume("state-1"));
    }

    @Test
    void entryExpiresAfterTenMinutes() {
        store.put("state-1", "verifier-1");
        clock.advance(Duration.ofMinutes(10).plusSeconds(1));
        assertTrue(store.consume("state-1").isEmpty());
    }

    @Test
    void statesAreIndependent() {
        store.put("a", "va");
        store.put("b", "vb");
        assertEquals(Optional.of("vb"), store.consume("b"));
        assertEquals(Optional.of("va"), store.consume("a"));
    }
}
