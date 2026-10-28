package com.exam.model.fees;

import com.exam.model.User;
import com.exam.model.academic.AcademicSession;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * School fees a student still owed when a session's fees were closed (CARRIED), or owed from before the
 * school used this system (OPENING, no session). Paid oldest first, before this session's fees. The
 * bursar may write off what is left (a recorded loss, which can be restored).
 */
@Entity
@Table(name = "fee_arrears", indexes = {
        @Index(name = "idx_fee_arrears_student", columnList = "student_id, status"),
        @Index(name = "idx_fee_arrears_session", columnList = "session_id")
})
@Getter @Setter
public class FeeArrears {

    public enum Kind { CARRIED, OPENING }
    public enum Status { OPEN, WRITTEN_OFF }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false)
    private User student;

    /** The session the debt is from; null for an opening balance. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id")
    private AcademicSession session;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Kind kind;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    /** The class it was owed for, as it was then. */
    @Column(length = 255)
    private String programName;

    private Integer level;

    @Column(length = 300)
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Status status = Status.OPEN;

    /** What was left when it was written off. */
    @Column(precision = 12, scale = 2)
    private BigDecimal writtenOff;

    @Column(length = 200)
    private String statusNote;

    @Column(length = 120)
    private String statusBy;

    private LocalDateTime statusAt;

    @Column(length = 120)
    private String createdBy;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public boolean isOpen() { return status == Status.OPEN; }

    /** "2025/2026 fees" / "Opening balance". */
    public String label() {
        return session != null ? session.getName() + " fees (arrears)" : "Opening balance";
    }
}
