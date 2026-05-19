package com.exam.DTO;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class CategoryWithQuizzesDTO {
    private Long cid;
    private String title;
    private String courseCode;
    private String description;
    private String level;
    private List<QuizDTO> quizzes;
}
