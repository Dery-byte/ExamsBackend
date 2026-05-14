package com.exam.DTO;

import com.exam.model.User;

import java.util.List;

public class LecturerResponse {
    private long count;
    private List<User> lecturers;

    public LecturerResponse(List<User> lecturers) {
        this.lecturers = lecturers;
        this.count = lecturers.size();
    }

    public long getCount() {
        return count;
    }

    public void setCount(long count) {
        this.count = count;
    }

    public List<User> getLecturers() {
        return lecturers;
    }

    public void setLecturers(List<User> lecturers) {
        this.lecturers = lecturers;
    }
}