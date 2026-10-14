package com.exam.service.attendance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Ends class meetings whose time is up (absentees and missed second checks are recorded then). */
@Component
public class AttendanceScheduler {

    private static final Logger log = LoggerFactory.getLogger(AttendanceScheduler.class);

    @Autowired private AttendanceService attendance;

    @Scheduled(fixedDelay = 60_000, initialDelay = 45_000)
    public void endFinishedClasses() {
        for (Long id : attendance.dueSessionIds()) {
            try {
                if (attendance.finalizeIfDue(id)) log.info("[Attendance] Session {} ended on time", id);
            } catch (Exception e) {
                log.warn("[Attendance] Could not end session {}: {}", id, e.getMessage());
            }
        }
    }
}
