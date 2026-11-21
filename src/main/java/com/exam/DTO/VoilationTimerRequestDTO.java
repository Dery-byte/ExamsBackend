package com.exam.DTO;


import lombok.Data;
import lombok.Getter;
import lombok.Setter;

@Data
@Setter
@Getter
public class VoilationTimerRequestDTO {
    private Integer violationDelayTime;
    private Integer totalViolationCount;
    /** The reset number the exam page knew when it queued this save (null from pages older than resets: 0). */
    private Integer violationResetNo;


}
