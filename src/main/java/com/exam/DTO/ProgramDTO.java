package com.exam.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

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
}
