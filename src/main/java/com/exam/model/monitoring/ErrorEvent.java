package com.exam.model.monitoring;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * One kind of failure (same place, same exception), counted each time it happens. Server errors
 * (HTTP 5xx), failed background jobs and crashes in users' browsers all end up here.
 */
@Entity
@Table(name = "error_event", uniqueConstraints = @UniqueConstraint(columnNames = "fingerprint"),
       indexes = @Index(name = "idx_error_event_last_seen", columnList = "lastSeen"))
@Getter @Setter
public class ErrorEvent {

    public enum Source { SERVER, BACKGROUND, BROWSER }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String fingerprint;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Source source;

    /** e.g. "POST /api/remarks/{id}/respond", a job name, or the page a browser error happened on. */
    @Column(length = 300)
    private String location;

    private Integer httpStatus;

    @Column(length = 200)
    private String exceptionType;

    @Column(length = 1000)
    private String message;

    @Column(columnDefinition = "TEXT")
    private String stack;

    /** Username of the last person who hit it (not a secret, helps reproduce). */
    @Column(length = 80)
    private String lastUser;

    private long occurrences;

    @Column(nullable = false)
    private Instant firstSeen;

    @Column(nullable = false)
    private Instant lastSeen;

    private Instant lastAlertAt;

    private boolean resolved;

    private Instant resolvedAt;
}
