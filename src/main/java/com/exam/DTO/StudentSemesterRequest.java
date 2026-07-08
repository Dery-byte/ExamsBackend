package com.exam.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Request body to set a student's current semester (and optionally level). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StudentSemesterRequest {
    private Integer currentSemester;  // 1 or 2
    private Integer currentLevel;     // optional override — 100, 200, etc.
}
