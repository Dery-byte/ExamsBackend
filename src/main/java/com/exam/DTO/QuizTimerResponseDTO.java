package com.exam.DTO;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;


@Data
@Getter
@Setter
public class QuizTimerResponseDTO {
    private Integer remainingTime;
    private LocalDateTime updatedAt;
    private Integer totalViolationCount;
    /** Lets a clock checkpoint tell the exam page that staff reset the violations. */
    private Integer violationResetNo;
    private String status;
// getter + setter
}
