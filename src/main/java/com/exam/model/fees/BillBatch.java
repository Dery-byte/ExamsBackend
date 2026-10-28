package com.exam.model.fees;

import com.exam.model.academic.AcademicSession;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** One issue of a bill to a group (a class, a house, all boarders, one student): each student gets their own {@link StudentBill}. */
@Entity
@Table(name = "fee_bill_batch")
@Getter @Setter
public class BillBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String title;

    /** Who it went to, in words: "General Science · Form 1", "Aggrey House", "Ama Owusu". */
    @Column(nullable = false, length = 200)
    private String audience;

    /** What each student is billed. */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amountEach;

    @Column(nullable = false)
    private int students;

    /** Students may decline an optional bill (an excursion, an extra textbook). */
    @Column(nullable = false)
    private boolean optional;

    private LocalDate dueDate;

    @Column(length = 300)
    private String note;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id")
    private AcademicSession session;

    @Column(length = 120)
    private String issuedBy;

    @Column(nullable = false)
    private LocalDateTime issuedAt = LocalDateTime.now();

    /** Set when the whole batch was cancelled. */
    private LocalDateTime cancelledAt;

    @Column(length = 120)
    private String cancelledBy;
}
