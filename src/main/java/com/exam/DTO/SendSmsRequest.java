package com.exam.DTO;

import lombok.Data;

import java.util.List;

@Data
public class SendSmsRequest {
    private List<String> recipient;
    private String message;


}