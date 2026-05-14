package com.exam.DTO;


import lombok.Data;
import lombok.Getter;
import lombok.Setter;

@Data
@Setter
@Getter
public class QuizTimerRequestDTO {
    private Integer remainingTime;
}
