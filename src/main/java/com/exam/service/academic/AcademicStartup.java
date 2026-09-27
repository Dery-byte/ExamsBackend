package com.exam.service.academic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * One-time academic set-up at start-up: seeds the grading scale and degree classes (matching
 * the grades used before they were configurable) and places records created before sessions
 * existed into the current session. Both steps are idempotent.
 */
@Component
public class AcademicStartup {

    private static final Logger log = LoggerFactory.getLogger(AcademicStartup.class);

    @Autowired private GradingService gradingService;
    @Autowired private AcademicSessionService sessionService;

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            gradingService.seedDefaults();
            sessionService.backfill();
        } catch (Exception e) {
            log.error("[AcademicStartup] Academic set-up failed: {}", e.getMessage(), e);
        }
    }
}
