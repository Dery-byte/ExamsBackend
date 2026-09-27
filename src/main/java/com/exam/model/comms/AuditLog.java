package com.exam.model.comms;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** One recorded staff action (who did what, when, and whether it succeeded). */
@Entity
@Table(name = "audit_log", indexes = {
        @Index(name = "idx_audit_created", columnList = "created_at"),
        @Index(name = "idx_audit_actor", columnList = "actor_id")
})
@Getter @Setter
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "actor_id")
    private Long actorId;

    private String actorName;
    private String actorRole;

    /** Human-readable action, e.g. "Approved marks sheet". */
    @Column(nullable = false)
    private String action;

    private String httpMethod;

    @Column(length = 500)
    private String path;

    /** First numeric id found in the path, when there is one. */
    private String entityId;

    @Column(length = 2000)
    private String details;

    private Integer statusCode;
    private String ipAddress;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
