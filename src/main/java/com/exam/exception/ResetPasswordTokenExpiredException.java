package com.exam.exception;

public class ResetPasswordTokenExpiredException extends RuntimeException{
    public ResetPasswordTokenExpiredException(String message) {
        super(message);
    }
}
