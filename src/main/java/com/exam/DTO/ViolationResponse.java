package com.exam.DTO;


import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class ViolationResponse {
    private Integer delaySeconds;
    private Boolean autoSubmit = false;
}
