package com.exam.model.fees;

import com.exam.model.academic.AcademicSession;
import com.exam.model.exam.Program;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * What one programme + level pays in one academic session, e.g. Computer Science Level 200 in
 * 2025/2026 pays GHS 3,000; JHS Class 1 pays GHS 1,500. Either a lump sum or an itemised breakdown
 * (school fees, sanitation, maintenance …); {@link #amount} always holds the total.
 */
@Entity
@Getter
@Setter
@Table(name = "fee_schedule",
        uniqueConstraints = @UniqueConstraint(name = "uk_fee_schedule_program_level_session",
                columnNames = {"program_id", "level", "session_id"}),
        indexes = @Index(name = "idx_fee_schedule_session", columnList = "session_id"))
public class FeeSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "program_id", nullable = false)
    private Program program;

    /** 100, 200 … at university; 1, 2, 3 … in schools (Form 1, Class 1 …). */
    @Column(name = "level", nullable = false)
    private int level;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    private AcademicSession session;

    /** Total payable: the lump sum, or the sum of the components when itemised. */
    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount = BigDecimal.ZERO;

    /** True when the total is made of {@link #components} that students see as a breakdown. */
    @Column(name = "itemised", nullable = false)
    private boolean itemised;

    @OneToMany(mappedBy = "schedule", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC, id ASC")
    private List<FeeComponent> components = new ArrayList<>();

    private LocalDate dueDate;

    @Column(length = 500)
    private String note;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    @Column(name = "updated_by", length = 120)
    private String updatedBy;
}
