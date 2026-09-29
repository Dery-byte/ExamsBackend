package com.exam.model.academic;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** Attendance, conduct and remarks for one student on one marks sheet (term), printed on the report card. */
@Entity
@Table(name = "term_report_remark", uniqueConstraints = @UniqueConstraint(columnNames = {"sheet_id", "student_id"}))
@Getter @Setter
public class TermReportRemark {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private Long sheetId;

    @Column(name = "student_id", nullable = false)
    private Long studentId;

    private Integer daysPresent;
    private Integer daysOpen;

    @Column(length = 100)
    private String conduct;

    @Column(length = 100)
    private String interest;

    @Column(length = 500)
    private String classTeacherRemark;

    @Column(length = 500)
    private String headRemark;

    @Column(length = 80)
    private String updatedBy;

    private LocalDateTime updatedAt;
}
