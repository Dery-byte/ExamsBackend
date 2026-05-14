package com.exam.model.exam;

import com.exam.model.User;
import jakarta.persistence.*;

import java.time.LocalTime;
import java.util.Date;

@Entity
@Table()
public class Registered_courses {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long Rid;

    private Date RegDate;
//    private String CourseCode;

    @ManyToOne(cascade = CascadeType.MERGE)
    @JoinColumn(name = "c_id")
    private Category category;

    @ManyToOne(cascade = CascadeType.MERGE)
    @JoinColumn(name = "user_id")
    private User user;


    public Registered_courses() {
    }

    public Registered_courses(Long rid, Date regDate, Category category, User user) {
        Rid = rid;
        RegDate = regDate;
//        CourseCode = courseCode;
        this.category = category;
        this.user = user;
    }

    public Long getRid() {
        return Rid;
    }

    public void setRid(Long rid) {
        Rid = rid;
    }

    public Date getRegDate() {
        return RegDate;
    }

    public void setRegDate(Date regDate) {
        RegDate = regDate;
    }

//    public String getCourseCode() {
//        return CourseCode;
//    }

//    public void setCourseCode(String courseCode) {
//        CourseCode = courseCode;
//    }

    public Category getCategory() {
        return category;
    }

    public void setCategory(Category category) {
        this.category = category;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }
}
