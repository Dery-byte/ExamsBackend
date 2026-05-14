package com.exam.DTO;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class CategoryUpdateRequest {

    // Getters and Setters
    private Long id;
    private String title;
    private String courseCode;
    private String description;
    private String level;
    //private Long userId;
    // Constructors
//    public CategoryUpdateRequest() {
//    }
//
//    public CategoryUpdateRequest(Long id, String title, String description) {
//        this.id = id;
//        this.title = title;
//        this.description = description;
//    }

    @Override
    public String toString() {
        return "CategoryUpdateRequest{" +
                "id=" + id +
                ", title='" + title + '\'' +
                ", description='" + description + '\'' +
                '}';
    }
}