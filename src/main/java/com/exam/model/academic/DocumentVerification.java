package com.exam.model.academic;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * One issued transcript or report card. The code printed on the PDF looks this row up, so an
 * employer or school can confirm the document is genuine and see what it said when it was issued.
 */
@Entity
@Table(name = "document_verification", uniqueConstraints = @UniqueConstraint(columnNames = "code"))
@Getter @Setter
public class DocumentVerification {

    public enum Type { TRANSCRIPT, REPORT_CARD, CUMULATIVE_REPORT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Type docType;

    private Long studentId;

    @Column(length = 150)
    private String studentName;

    @Column(length = 80)
    private String studentUsername;

    @Column(length = 200)
    private String programName;

    /** What the document showed, e.g. "Level 200, First Semester 2025/2026 · GPA 3.45". */
    @Column(length = 600)
    private String summary;

    /** The institution named on the document when it was issued. */
    @Column(length = 150)
    private String institutionName;

    @Column(nullable = false)
    private Instant issuedAt = Instant.now();

    @Column(length = 80)
    private String issuedBy;

    private boolean revoked;

    @Column(length = 300)
    private String revokedReason;
}
