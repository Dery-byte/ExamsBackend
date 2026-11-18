package com.exam.service.materials;

import com.exam.service.SystemSettingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Every minute: tells students about items that have just become visible (straight away or at their
 * scheduled time, one message per course), reminds them a day before an assignment is due, and tells
 * the lecturer when submissions close. Every few hours: empties recycle-bin items older than 30 days.
 * <p>
 * The work itself is in {@link MaterialNotices}, another bean, so each course or assignment gets its
 * own real transaction; one that fails is skipped (and tried again next minute) without holding up the
 * rest. The same failure is logged with its stack trace at most once an hour, with a count in between.
 */
@Component
public class MaterialScheduler {

    private static final Logger log = LoggerFactory.getLogger(MaterialScheduler.class);
    private static final long LOG_EVERY_MS = 3600_000L;

    @Autowired private SystemSettingService settings;
    @Autowired private MaterialNotices notices;
    @Autowired private MaterialService materialService;
    @Autowired private MaterialStorage storage;

    @Scheduled(fixedDelay = 60_000, initialDelay = 50_000)
    public void everyMinute() {
        if (!settings.materialsOn()) return;
        announce();
        remindDue();
        reportClosed();
    }

    public void announce() {
        LocalDateTime now = LocalDateTime.now();
        forEach("announcing new items", "course", () -> notices.coursesToAnnounce(now), id -> notices.announce(id, now));
    }

    public void remindDue() {
        LocalDateTime now = LocalDateTime.now();
        forEach("sending due reminders", "assignment", () -> notices.assignmentsDueSoon(now), id -> notices.remind(id, now));
    }

    public void reportClosed() {
        LocalDateTime now = LocalDateTime.now();
        forEach("reporting closed submissions", "assignment", () -> notices.assignmentsJustClosed(now), notices::reportClosed);
    }

    /** Finds the work, then does each unit on its own; a unit that fails doesn't stop the others. */
    private void forEach(String job, String unit, java.util.function.Supplier<List<Long>> find, Function<Long, ?> each) {
        List<Long> ids;
        try {
            ids = find.get();
        } catch (Exception e) {
            problem(job, null, unit, e);
            return;
        }
        for (Long id : ids) {
            try {
                each.apply(id);
            } catch (Exception e) {
                problem(job, id, unit, e);
            }
        }
    }

    private record Seen(long loggedAt, int since) {}
    private final Map<String, Seen> seen = new ConcurrentHashMap<>();

    /** Logs a failure in full the first time in an hour; after that only counts it, so the log isn't flooded. */
    private void problem(String job, Long id, String unit, Exception e) {
        String key = job + '|' + e.getClass().getName() + '|' + e.getMessage();
        long now = System.currentTimeMillis();
        Seen s = seen.get(key);
        if (s == null || now - s.loggedAt() >= LOG_EVERY_MS) {
            String again = s != null && s.since() > 0 ? " (also failed " + s.since() + " more time(s) in the last hour)" : "";
            log.error("[Materials] {} failed{}{}", job, id == null ? "" : " for " + unit + " " + id, again, e);
            seen.put(key, new Seen(now, 0));
        } else {
            seen.put(key, new Seen(s.loggedAt(), s.since() + 1));
        }
        if (seen.size() > 500) seen.clear();
    }

    @Scheduled(fixedDelay = 6 * 3600_000L, initialDelay = 300_000)
    public void housekeeping() {
        try {
            int n = materialService.purgeOldBin();
            if (n > 0) log.info("[Materials] Emptied {} item(s) from recycle bins after 30 days", n);
        } catch (Exception e) {
            log.warn("[Materials] Emptying recycle bins failed: {}", e.getMessage());
        }
        // Uploads cut off half-way leave their part files behind
        Path tmp = storage.root().resolve("tmp");
        if (Files.isDirectory(tmp)) {
            Instant dayAgo = Instant.now().minus(1, ChronoUnit.DAYS);
            try (Stream<Path> parts = Files.list(tmp)) {
                parts.filter(p -> {
                    try { return Files.getLastModifiedTime(p).toInstant().isBefore(dayAgo); } catch (IOException e) { return false; }
                }).forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) { } });
            } catch (IOException ignored) { }
        }
    }
}
