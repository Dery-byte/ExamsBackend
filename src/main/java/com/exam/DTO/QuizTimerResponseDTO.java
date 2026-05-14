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
    private String status;
// getter + setter
}
