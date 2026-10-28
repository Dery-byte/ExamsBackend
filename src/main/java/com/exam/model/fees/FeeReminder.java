package com.exam.model.fees;

import com.exam.model.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** A fee reminder sent to a student: how much they owed then, by which channels, by whom (or automatically). */
@Entity
@Table(name = "fee_reminder", indexes = {
        @Index(name = "idx_fee_reminder_student", columnList = "student_id, sent_at"),
        @Index(name = "idx_fee_reminder_batch", columnList = "batch")
})
@Getter @Setter
public class FeeReminder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false)
    private User student;

    /** One send: every reminder of it shares this. */
    @Column(nullable = false, length = 36)
    private String batch;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal owed;

    @Column(nullable = false)
    private boolean byEmail;

    @Column(nullable = false)
    private boolean bySms;

    @Column(nullable = false)
    private boolean automatic;

    @Column(length = 500)
    private String message;

    @Column(length = 120)
    private String sentBy;

    @Column(name = "sent_at", nullable = false)
    private LocalDateTime sentAt = LocalDateTime.now();
}
