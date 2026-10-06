package com.exam.model.fees;

import com.exam.model.exam.Program;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * The Super Admin's rule for one programme: students who haven't paid enough of their current
 * session's fee can't see or download their report cards and / or transcript. No row = no hold.
 * Only applies while the system-wide switch {@code FEES_RESULTS_HOLD} is on.
 */
@Entity
@Getter
@Setter
@Table(name = "results_fee_hold",
        uniqueConstraints = @UniqueConstraint(name = "uk_results_fee_hold_program", columnNames = "program_id"))
public class ResultsFeeHold {

    /**
     * FULL: the whole fee. PERCENT: at least {@link #minPercent}% of it.
     * ITEMS: the {@link #requiredItems} of an itemised fee (a fee without a breakdown must be paid in full).
     */
    public enum Mode { FULL, PERCENT, ITEMS }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "program_id", nullable = false)
    private Program program;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Mode mode = Mode.FULL;

    @Column(name = "min_percent")
    private Integer minPercent;

    /** Item names, one per line (matched to the fee's breakdown ignoring case and spacing). */
    @Column(name = "required_items", length = 2000)
    private String requiredItems;

    @Column(name = "hold_report_cards", nullable = false)
    private boolean holdReportCards = true;

    @Column(name = "hold_transcript", nullable = false)
    private boolean holdTranscript = true;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    @Column(name = "updated_by", length = 120)
    private String updatedBy;
}
