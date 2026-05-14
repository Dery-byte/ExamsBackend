package com.exam.DTO;

import com.exam.model.User;

import java.util.List;

public class AdminResponse {
    private long count;
    private List<User> admins;

    public AdminResponse(List<User> admins) {
        this.admins = admins;
        this.count = admins.size();
    }

    public long getCount() {
        return count;
    }

    public void setCount(long count) {
        this.count = count;
    }

    public List<User> getAdmins() {
        return admins;
    }

    public void setAdmins(List<User> admins) {
        this.admins = admins;
    }
}