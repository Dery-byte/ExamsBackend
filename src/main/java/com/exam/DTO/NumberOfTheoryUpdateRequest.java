package com.exam.DTO;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class NumberOfTheoryUpdateRequest {
    private Long answerTheoryId;       // ID of the record to update
    private Integer totalQuestToAnswer; // optional
    private Integer timeAllowed;        // optional
}
