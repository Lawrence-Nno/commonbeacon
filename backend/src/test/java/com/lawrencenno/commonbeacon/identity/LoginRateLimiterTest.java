package com.lawrencenno.commonbeacon.identity;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class LoginRateLimiterTest {
    static class MutableClock extends Clock {
        Instant now = Instant.EPOCH;
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
    @Test void rejectsExcessAttemptsAndRecoversAfterWindow() {
        var clock = new MutableClock();
        var limiter = new LoginRateLimiter(clock, 2, 2, Duration.ofMinutes(1));
        assertThat(limiter.allow("a")).isTrue();
        assertThat(limiter.allow("a")).isTrue();
        assertThat(limiter.allow("a")).isFalse();
        clock.now = clock.now.plusSeconds(60);
        assertThat(limiter.allow("a")).isTrue();
    }
    @Test void boundsKeysAndKeepsAddressesIndependent() {
        var limiter = new LoginRateLimiter(Clock.systemUTC(), 1, 2, Duration.ofMinutes(1));
        assertThat(limiter.allow("a")).isTrue();
        assertThat(limiter.allow("b")).isTrue();
        assertThat(limiter.allow("a")).isFalse();
        assertThat(limiter.allow("c")).isFalse();
    }
}
