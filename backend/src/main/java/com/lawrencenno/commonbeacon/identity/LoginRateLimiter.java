package com.lawrencenno.commonbeacon.identity;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/** Single-instance fixed-window limiter; bounds both work and retained keys. */
public final class LoginRateLimiter {
    private record Window(long startedAt, int attempts) {}
    private final Map<String, Window> windows = new HashMap<>();
    private final Clock clock;
    private final int maximum;
    private final int capacity;
    private final long windowMillis;
    public LoginRateLimiter(Clock clock, int maximum, int capacity, Duration window) {
        this.clock = clock; this.maximum = maximum; this.capacity = capacity;
        this.windowMillis = window.toMillis();
    }
    public synchronized boolean allow(String address) {
        long now = clock.millis();
        windows.entrySet().removeIf(entry -> now - entry.getValue().startedAt() >= windowMillis);
        var current = windows.get(address);
        if (current == null) {
            if (windows.size() >= capacity) return false;
            windows.put(address, new Window(now, 1));
            return true;
        }
        if (current.attempts() >= maximum) return false;
        windows.put(address, new Window(current.startedAt(), current.attempts() + 1));
        return true;
    }
}
