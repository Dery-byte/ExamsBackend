package com.exam.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProgramDTO {
    private Long id;
    private String name;
    private String code;
    private int durationYears;
    private Long departmentId;
    private String departmentName;
    private List<Integer> configuredLevels; // e.g. [100, 200, 300, 400]
    private boolean enabled;               // true = active; false = hidden system-wide
    /** Number of semesters per level, e.g. {100: 3, 200: 2, 300: 2, 400: 2}. */
    private Map<Integer, Integer> semestersPerLevel;

    // Schools: each programme is one class
    private Integer sortOrder;
    /** Year of its section (1 … the section's years). */
    private Integer sectionYear;
    /** The section's years, for showing "Year 2 of 6". Read only. */
    private Integer sectionYears;
    /** Chosen next class; null = the next one in order. */
    private Long nextProgramId;
    /** Students leave the school after this class. */
    private Boolean finalClass;
    /** Where students go at promotion (null = they leave the school). Read only. */
    private Long promotesToId;
    private String promotesToName;
}
