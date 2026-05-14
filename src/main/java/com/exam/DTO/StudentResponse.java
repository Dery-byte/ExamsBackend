package com.exam.DTO;

import com.exam.model.User;
import java.util.List;


public class StudentResponse {
    private long count;
    private List<User> students;

    public StudentResponse(List<User> students) {
        this.students = students;
        this.count = students.size();
    }

    public long getCount() {
        return count;
    }

    public void setCount(long count) {
        this.count = count;
    }

    public List<User> getStudents() {
        return students;
    }

    public void setStudents(List<User> students) {
        this.students = students;
    }
}