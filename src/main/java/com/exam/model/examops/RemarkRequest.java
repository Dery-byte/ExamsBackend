package com.exam.model.examops;

import com.exam.model.User;
import com.exam.model.exam.Report;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** A student's request to have a reviewed quiz script re-marked, and the lecturer's response. */
@Entity
@Table(name = "remark_request")
@Getter @Setter
public class RemarkRequest {

    public enum Status { PENDING, RESOLVED, REJECTED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // All associations LAZY: loading the report and the student eagerly in one query reaches the same
    // student twice, which trips a Hibernate 6.1 bug ("force initializing collection loading") on flush.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "report_id")
    private Report report;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id")
    private User student;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.PENDING;

    @Column(columnDefinition = "TEXT")
    private String response;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "responded_by_id")
    private User respondedBy;

    /** Total score (section A + B) when the request was made, and when it was answered. */
    private BigDecimal scoreBefore;
    private BigDecimal scoreAfter;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime respondedAt;
}
