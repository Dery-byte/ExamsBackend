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
}
