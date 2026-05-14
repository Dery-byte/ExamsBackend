package com.exam.DTO;
public class TokenInfo {
    private long exp; // expiration timestamp in seconds

    public TokenInfo(long exp) {
        this.exp = exp;
    }

    public long getExp() {
        return exp;
    }

    public void setExp(long exp) {
        this.exp = exp;
    }
}
