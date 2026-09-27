package com.exam.model.academic;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** One row of the grading scale: scores from {@code minScore} up to the next band's minimum earn this grade. */
@Entity
@Table(name = "grade_band")
@Getter @Setter
public class GradeBand {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 5)
    private String letter;

    /** Lowest total score (out of 100) for this grade. */
    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal minScore;

    @Column(nullable = false, precision = 4, scale = 2)
    private BigDecimal gradePoint;

    /** Short description printed on transcripts, e.g. "Excellent". */
    @Column(length = 40)
    private String remark;

    /** False for failing grades (these become carry-overs). */
    private boolean passing = true;
}
