package com.exam.config;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Small in-memory sliding-window limiter used against password guessing and form abuse.
 * Per server instance: with several instances behind a load balancer each keeps its own counts.
 */
@Component
public class RateLimiter {

    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    /** Records a hit and returns true if it is within {@code max} hits per {@code windowMs}. */
    public boolean tryAcquire(String key, int max, long windowMs) {
        long now = System.currentTimeMillis();
        Deque<Long> q = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            prune(q, now - windowMs);
            if (q.size() >= max) return false;
            q.addLast(now);
            return true;
        }
    }

    /** Hits within the window, without recording a new one. */
    public int count(String key, long windowMs) {
        Deque<Long> q = hits.get(key);
        if (q == null) return 0;
        synchronized (q) {
            prune(q, System.currentTimeMillis() - windowMs);
            return q.size();
        }
    }

    /** Records a hit unconditionally (e.g. a failed login). */
    public void record(String key) {
        Deque<Long> q = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) { q.addLast(System.currentTimeMillis()); }
    }

    public void reset(String key) {
        hits.remove(key);
    }

    /** Seconds until the oldest hit in the window expires (how long to wait). */
    public long retryAfterSeconds(String key, long windowMs) {
        Deque<Long> q = hits.get(key);
        if (q == null) return 0;
        synchronized (q) {
            Long oldest = q.peekFirst();
            return oldest == null ? 0 : Math.max(1, (oldest + windowMs - System.currentTimeMillis()) / 1000);
        }
    }

    private static void prune(Deque<Long> q, long cutoff) {
        while (!q.isEmpty() && q.peekFirst() < cutoff) q.pollFirst();
    }

    /** Forget idle keys so memory stays bounded. */
    @Scheduled(fixedRate = 10 * 60 * 1000)
    public void cleanUp() {
        long cutoff = System.currentTimeMillis() - 60 * 60 * 1000;   // nothing we track has a window over 1 h
        hits.entrySet().removeIf(e -> {
            synchronized (e.getValue()) {
                prune(e.getValue(), cutoff);
                return e.getValue().isEmpty();
            }
        });
    }
}
