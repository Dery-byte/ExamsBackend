package com.exam.DTO;

import lombok.Data;

@Data
public class CategoryRequest {
    private String title;
    private String courseCode;
    private String description;
    private String level;
    private Long userId;
}
