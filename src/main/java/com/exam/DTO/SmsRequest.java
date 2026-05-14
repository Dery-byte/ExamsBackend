package com.exam.DTO;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
public class SmsRequest {
    @JsonProperty("recipient")
    private List<String> recipient;

    @JsonProperty("sender")
    private String sender;

    @JsonProperty("message")
    private String message;

    @JsonProperty("is_schedule")
    private String is_schedule = "false";

    @JsonProperty("schedule_date")
    private String schedule_date = "";


}