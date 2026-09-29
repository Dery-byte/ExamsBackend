package com.exam.service.monitoring;

import com.exam.service.academic.InstitutionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Health of the running system: database, email, disk, memory, scheduled jobs and recent errors.
 * Overall status: DOWN (database unreachable), DEGRADED (new errors in the last hour, a stalled job,
 * low disk or memory) or UP.
 */
@Service
public class HealthService {

    /** Jobs report in each run; a job that hasn't reported for a while is shown as stalled. */
    private record Beat(Instant at, Duration expectedEvery) {}

    private final Map<String, Beat> heartbeats = new ConcurrentHashMap<>();

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ErrorMonitorService errorMonitor;
    @Autowired private InstitutionService institutionService;
    @Autowired private Environment environment;

    @Value("${spring.mail.host:}") private String mailHost;

    public void heartbeat(String job, Duration expectedEvery) {
        heartbeats.put(job, new Beat(Instant.now(), expectedEvery));
    }

    /** For uptime monitors: no details, just whether the database answers. */
    public Map<String, Object> publicStatus() {
        return Map.of("status", database().get("status"), "time", Instant.now().toString());
    }

    public Map<String, Object> details() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();

        Map<String, Object> db = database();
        boolean dbUp = "UP".equals(db.get("status"));
        if (!dbUp) problems.add("The database is not answering.");

        Map<String, Object> mail = new LinkedHashMap<>();
        boolean mailConfigured = mailHost != null && !mailHost.isBlank();
        mail.put("status", mailConfigured ? "CONFIGURED" : "NOT_CONFIGURED");
        mail.put("host", mailConfigured ? mailHost : null);
        if (!mailConfigured) problems.add("No mail server is configured, so emails (codes, alerts, result slips) can't be sent.");

        File root = new File(".").getAbsoluteFile();
        long total = root.getTotalSpace(), free = root.getUsableSpace();
        Map<String, Object> disk = new LinkedHashMap<>();
        disk.put("freeMb", free / (1024 * 1024));
        disk.put("totalMb", total / (1024 * 1024));
        disk.put("freePercent", total == 0 ? null : Math.round(free * 100.0 / total));
        if (total > 0 && free * 100.0 / total < 5) problems.add("Less than 5% disk space is free.");

        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory(), max = rt.maxMemory();
        Map<String, Object> memory = new LinkedHashMap<>();
        memory.put("usedMb", used / (1024 * 1024));
        memory.put("maxMb", max / (1024 * 1024));
        memory.put("usedPercent", Math.round(used * 100.0 / max));
        if (used * 100.0 / max > 90) problems.add("Memory use is above 90%.");

        List<Map<String, Object>> jobs = new ArrayList<>();
        heartbeats.forEach((name, beat) -> {
            boolean stalled = beat.at().plus(beat.expectedEvery().multipliedBy(3)).isBefore(Instant.now());
            if (stalled) problems.add("The job \"" + name + "\" hasn't run since " + beat.at() + ".");
            jobs.add(Map.of("name", name, "lastRun", beat.at().toString(), "status", stalled ? "STALLED" : "OK"));
        });

        Map<String, Object> errors = errorMonitor.summary();
        long openLastHour = ((Number) errors.get("openLastHour")).longValue();
        if (openLastHour > 0) problems.add(openLastHour + " unresolved error" + (openLastHour == 1 ? "" : "s") + " in the last hour.");

        long uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();
        out.put("status", !dbUp ? "DOWN" : problems.isEmpty() ? "UP" : "DEGRADED");
        out.put("problems", problems);
        out.put("checkedAt", Instant.now().toString());
        out.put("startedAt", Instant.now().minusMillis(uptimeMs).toString());
        out.put("uptimeMinutes", uptimeMs / 60000);
        out.put("mode", institutionService.mode().name());
        out.put("profiles", Arrays.asList(environment.getActiveProfiles()));
        out.put("javaVersion", System.getProperty("java.version"));
        out.put("database", db);
        out.put("mail", mail);
        out.put("disk", disk);
        out.put("memory", memory);
        out.put("jobs", jobs);
        out.put("errors", errors);
        return out;
    }

    private Map<String, Object> database() {
        Map<String, Object> m = new LinkedHashMap<>();
        long start = System.nanoTime();
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            m.put("status", "UP");
            m.put("responseMs", (System.nanoTime() - start) / 1_000_000);
        } catch (Exception e) {
            m.put("status", "DOWN");
            m.put("error", e.getClass().getSimpleName());
        }
        return m;
    }
}
