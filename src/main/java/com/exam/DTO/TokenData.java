package com.exam.DTO;

public class TokenData {
    private final String phone;
    private final Long userId;
    private final long expiryTime;

    public TokenData(String phone, Long userId, long expiryTime) {
        this.phone = phone;
        this.userId = userId;
        this.expiryTime = expiryTime;
    }

    public String getPhone() {
        return phone;
    }

    public Long getUserId() {
        return userId;
    }

    public boolean isExpired() {
        return System.currentTimeMillis() > expiryTime;
    }
}