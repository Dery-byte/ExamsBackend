package com.exam.DTO;


import com.exam.model.exam.Category;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class CategoryDTO {
    private Long cid;
    private String title;
    private String courseCode;
    private String description;
    private String level;
    //private String lecturerName; // optional


    // Constructor from Category entity
    public CategoryDTO(Category category) {
        this.cid = category.getCid();
        this.title = category.getTitle();
        this.courseCode = category.getCourseCode();
        this.description = category.getDescription();
        this.level = category.getLevel();
//        this.lecturerName = category.getUser() != null ? category.getUser().getFullName() : null;
    }


}