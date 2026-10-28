package com.exam.model.fees;

import com.exam.model.User;
import com.exam.model.academic.AcademicSession;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * What one student is billed for apart from school fees (uniform, books, an excursion). Owed until
 * paid, across sessions; CANCELLED by the bursar (only while nothing is paid on it) or DECLINED by
 * the student when the bill is optional.
 */
@Entity
@Table(name = "fee_bill", indexes = {
        @Index(name = "idx_fee_bill_student", columnList = "student_id, status"),
        @Index(name = "idx_fee_bill_batch", columnList = "batch_id")
})
@Getter @Setter
public class StudentBill {

    public enum Status { OPEN, CANCELLED, DECLINED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false)
    private User student;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "batch_id", nullable = false)
    private BillBatch batch;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id")
    private AcademicSession session;

    @Column(nullable = false, length = 120)
    private String title;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false)
    private boolean optional;

    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status = Status.OPEN;

    @Column(length = 200)
    private String statusNote;

    @Column(length = 120)
    private String statusBy;

    private LocalDateTime statusAt;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @OneToMany(mappedBy = "bill", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    @org.hibernate.annotations.BatchSize(size = 50)
    private List<BillLine> lines = new ArrayList<>();

    public boolean isOpen() { return status == Status.OPEN; }
}
