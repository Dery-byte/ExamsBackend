package com.exam.model.exam;

import com.exam.model.User;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(name="category")
public class Category {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long cid;

    private  String title;
    private String CourseCode;

    private String description;
    private String level;

    @OneToMany(mappedBy = "category",cascade = CascadeType.ALL)
    @JsonIgnore
    private Set<Quiz> quizzes = new LinkedHashSet<>();

    // 🔹 USERS MAPPED TO CATEGORY
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "user_id") // creates a column in category table
    private User user;



    @JsonCreator
    public Category(Long cid, String level, String title, String CourseCode, String description, Set<Quiz> quizzes) {
        this.cid = cid;
        this.title = title;
        this.CourseCode=CourseCode;
        this.description = description;
        this.quizzes = quizzes;
        this.level = level;
    }

    public Category() {
    }

    public Long getCid() {
        return cid;
    }

    public String getCourseCode() {
        return CourseCode;
    }


    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }

    public void setCourseCode(String courseCode) {
        CourseCode = courseCode;
    }

    public void setCid(Long cid) {
        this.cid = cid;
    }

    public Set<Quiz> getQuizzes() {
        return quizzes;
    }

    public void setQuizzes(Set<Quiz> quizzes) {
        this.quizzes = quizzes;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
