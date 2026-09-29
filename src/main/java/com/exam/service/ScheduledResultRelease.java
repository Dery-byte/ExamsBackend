package com.exam.service;

import com.exam.model.exam.SemesterSheet;
import com.exam.service.comms.NotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Publishes approved marks sheets whose scheduled release time has passed (checked every minute). */
@Slf4j
@Component
public class ScheduledResultRelease {

    @Autowired
    private MarksEntryService marksEntryService;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private com.exam.service.monitoring.HealthService healthService;

    @Autowired
    private com.exam.service.monitoring.ErrorMonitorService errorMonitor;

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void releaseDueSheets() {
        healthService.heartbeat("Scheduled result release", java.time.Duration.ofMinutes(1));
        java.util.List<SemesterSheet> dueSheets;
        try { dueSheets = marksEntryService.sheetsDueForRelease(); }
        catch (Exception e) { errorMonitor.recordBackground("Scheduled result release", e); return; }
        for (SemesterSheet due : dueSheets) {
            try {
                marksEntryService.publishSheet(due.getId());
                SemesterSheet sheet = marksEntryService.getSheetById(due.getId());
                if (sheet != null) notificationService.sheetPublished(sheet);
                log.info("Released results for sheet {} on schedule", due.getId());
            } catch (Exception e) {
                errorMonitor.recordBackground("Scheduled result release (sheet " + due.getId() + ")", e);
            }
        }
    }
}
