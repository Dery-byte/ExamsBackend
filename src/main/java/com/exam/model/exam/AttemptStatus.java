package com.exam.model.exam;

public enum AttemptStatus {
    /** Started and not yet finished. Counts towards the attempt limit; can be resumed. */
    IN_PROGRESS,
    /** Finished. Counts towards the attempt limit. */
    SUBMITTED,
    /** Cancelled by staff (retake granted). Kept for audit, does not count towards the limit. */
    VOIDED
}
