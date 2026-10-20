package com.stayos.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;

@Component
public class IpLoginLimiter {
    private record Window(Instant start, int attempts) {}
    private final Map<String, Window> windows = new HashMap<>();
    private final int limit;
    private final Duration duration;
    public IpLoginLimiter(@Value("${stayos.auth.ip-limit}") int limit, @Value("${stayos.auth.ip-window}") Duration duration) {
        this.limit = limit; this.duration = duration;
    }
    public synchronized boolean allow(String ip) {
        var now = Instant.now();
        windows.entrySet().removeIf(entry -> !entry.getValue().start.plus(duration).isAfter(now));
        var old = windows.get(ip);
        // Bounded memory; refuse new sources when full rather than evicting active limits.
        if (old == null && windows.size() >= 10000) return false;
        if (old == null) { windows.put(ip, new Window(now, 1)); return true; }
        if (old.attempts >= limit) return false;
        windows.put(ip, new Window(old.start, old.attempts + 1));
        return true;
    }
}
