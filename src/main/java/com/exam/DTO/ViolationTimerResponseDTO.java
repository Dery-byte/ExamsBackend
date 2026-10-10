package com.exam.DTO;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;


@Data
@Getter
@Setter
public class ViolationTimerResponseDTO {
    private Integer violationDelayTime;
    private LocalDateTime updatedAt;
    private Integer totalViolationCount;
    /** True when a lock-out pauses while the student is away; the client then reports what is left. */
    private Boolean pausesWhileAway;

}
