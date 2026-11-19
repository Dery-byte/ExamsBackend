package com.exam.config;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;

/**
 * One line in the log every 30 s saying how the server is coping: memory, time lost to garbage collection,
 * CPU, threads and database connections. Under load (a full exam hall, a load test) it shows which of these
 * runs out first; it is logged as a warning while connections are being waited for or memory is nearly full.
 * Switch off with APP_HEALTH_LOG=false.
 */
@Component
public class ServerHealthLog {

    private static final Logger log = LoggerFactory.getLogger(ServerHealthLog.class);
    private static final long EVERY_MS = 30_000;

    private final DataSource dataSource;
    private final boolean enabled;
    private long lastGcMs = -1;
    private long lastCpuNs = -1;
    private long lastAt;

    public ServerHealthLog(DataSource dataSource, @Value("${APP_HEALTH_LOG:true}") boolean enabled) {
        this.dataSource = dataSource;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelay = EVERY_MS, initialDelay = 60_000)
    public void report() {
        if (!enabled) return;
        try {
            Runtime rt = Runtime.getRuntime();
            long maxMb = rt.maxMemory() >> 20;
            long usedMb = (rt.totalMemory() - rt.freeMemory()) >> 20;

            long gcMs = 0;
            for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) gcMs += Math.max(0, gc.getCollectionTime());
            long now = System.currentTimeMillis();
            String gcShare = lastGcMs < 0 ? "n/a" : Math.round(100.0 * (gcMs - lastGcMs) / Math.max(1, now - lastAt)) + "%";
            lastGcMs = gcMs;

            // CPU time this server used since the last line, as a share of all its CPUs. (getProcessCpuLoad() reads 0
            // inside Render's containers, so it's worked out from the process's own CPU time instead.)
            int cores = Runtime.getRuntime().availableProcessors();
            String cpu = "n/a";
            if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
                long cpuNs = os.getProcessCpuTime();
                if (cpuNs >= 0 && lastCpuNs >= 0)
                    cpu = Math.round(100.0 * (cpuNs - lastCpuNs) / 1_000_000.0 / Math.max(1, now - lastAt) / cores) + "% of " + cores + (cores == 1 ? " CPU" : " CPUs");
                lastCpuNs = cpuNs;
            }
            lastAt = now;
            int threads = ManagementFactory.getThreadMXBean().getThreadCount();

            String pool = "n/a";
            int waiting = 0;
            HikariPoolMXBean p = poolBean();
            if (p != null) {
                waiting = p.getThreadsAwaitingConnection();
                pool = p.getActiveConnections() + "/" + p.getTotalConnections() + " busy, " + waiting + " waiting";
            }

            String line = "Server health: heap " + usedMb + " of " + maxMb + " MB, GC " + gcShare + " of the last 30 s, CPU " + cpu
                    + ", threads " + threads + ", database connections " + pool;
            if (waiting > 0 || usedMb > maxMb * 0.85) log.warn(line);
            else log.info(line);
        } catch (Exception e) {
            log.debug("Server health line failed", e);
        }
    }

    private HikariPoolMXBean poolBean() {
        try {
            if (dataSource instanceof HikariDataSource h) return h.getHikariPoolMXBean();
            if (dataSource.isWrapperFor(HikariDataSource.class)) return dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean();
        } catch (Exception ignored) { /* not Hikari */ }
        return null;
    }
}
