package com.occpl.platform.shared.id;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;

/**
 * Generates RFC 9562 version 7 UUIDs: a 48-bit Unix millisecond timestamp followed by random
 * bits. Time ordering keeps InnoDB primary-key inserts mostly sequential (ADR-0003).
 */
public final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() {
    }

    public static UUID generate() {
        return generate(Clock.systemUTC());
    }

    public static UUID generate(Clock clock) {
        long millis = clock.millis();
        byte[] random = new byte[10];
        RANDOM.nextBytes(random);

        long msb = (millis & 0xFFFF_FFFF_FFFFL) << 16;
        msb |= 0x7000L; // version 7
        msb |= ((random[0] & 0x0FL) << 8) | (random[1] & 0xFFL); // rand_a (12 bits)

        long lsb = 0x8000_0000_0000_0000L; // IETF variant (10xx)
        lsb |= (random[2] & 0x3FL) << 56;
        for (int i = 3; i < 10; i++) {
            lsb |= (random[i] & 0xFFL) << (8 * (9 - i));
        }
        return new UUID(msb, lsb);
    }

    /** Milliseconds since the Unix epoch encoded in a version 7 UUID. */
    public static long timestampMillis(UUID uuid) {
        if (uuid.version() != 7) {
            throw new IllegalArgumentException("Not a version 7 UUID: " + uuid);
        }
        return uuid.getMostSignificantBits() >>> 16;
    }
}
