package com.occpl.platform.shared.id;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UuidV7Test {

    @Test
    void setsVersionAndVariant() {
        UUID id = UuidV7.generate();
        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    void encodesClockTimestamp() {
        Instant instant = Instant.parse("2026-10-13T10:15:30.123Z");
        UUID id = UuidV7.generate(Clock.fixed(instant, ZoneOffset.UTC));
        assertThat(UuidV7.timestampMillis(id)).isEqualTo(instant.toEpochMilli());
    }

    @Test
    void laterTimestampsSortAfterEarlierOnes() {
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        UUID earlier = UuidV7.generate(Clock.fixed(base, ZoneOffset.UTC));
        UUID later = UuidV7.generate(Clock.fixed(base.plusMillis(1), ZoneOffset.UTC));
        // Unsigned comparison of the most significant bits matches BINARY(16) ordering in MySQL.
        assertThat(Long.compareUnsigned(earlier.getMostSignificantBits(), later.getMostSignificantBits()))
                .isNegative();
    }

    @Test
    void generatesUniqueValuesWithinTheSameMillisecond() {
        Clock fixed = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        Set<UUID> ids = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            ids.add(UuidV7.generate(fixed));
        }
        assertThat(ids).hasSize(10_000);
    }

    @Test
    void rejectsOtherVersionsWhenReadingTimestamp() {
        assertThatThrownBy(() -> UuidV7.timestampMillis(UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
