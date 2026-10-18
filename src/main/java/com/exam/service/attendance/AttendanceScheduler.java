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

    /** Older programme meetings get their term, so report cards can count them. */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void recordTermsOnOlderMeetings() {
        try {
            int n = attendance.backfillProgrammeTerms();
            if (n > 0) log.info("[Attendance] Recorded the term on {} older programme meeting(s)", n);
        } catch (Exception e) {
            log.warn("[Attendance] Could not record terms on older meetings: {}", e.getMessage());
        }
    }

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
