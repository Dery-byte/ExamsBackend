package com.exam.DTO;


import com.exam.model.exam.Category;
import com.exam.model.User;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class CategoryDTO {
    private Long cid;
    private String title;
    private String courseCode;
    private String description;
    private String level;
    private String semester;

    private java.util.List<Long> programIds;
    private java.util.List<String> programNames;

    // Nested user info so the frontend lecturer pill still works
    private UserInfo user;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserInfo {
        private Long id;
        private String fullName;
        private String username;
    }

    // Constructor from Category entity
    public CategoryDTO(Category category) {
        this.cid = category.getCid();
        this.title = category.getTitle();
        this.courseCode = category.getCourseCode();
        this.description = category.getDescription();
        this.level = category.getLevel();
        this.semester = category.getSemester() != null ? category.getSemester().toString() : null;
        if (category.getPrograms() != null) {
            this.programIds = category.getPrograms().stream().map(p -> p.getId()).collect(java.util.stream.Collectors.toList());
            this.programNames = category.getPrograms().stream().map(p -> p.getName()).collect(java.util.stream.Collectors.toList());
        } else {
            this.programIds = new java.util.ArrayList<>();
            this.programNames = new java.util.ArrayList<>();
        }
        if (category.getUser() != null) {
            User u = category.getUser();
            this.user = new UserInfo(u.getId(), u.getFullName(), u.getUsername());
        }
    }


}