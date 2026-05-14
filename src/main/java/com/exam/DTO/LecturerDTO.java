package com.exam.DTO;


import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Setter
@Getter
public class LecturerDTO {
    private Long id;
    @JsonProperty("firstname")
    private String firstname;

    @JsonProperty("lastname")
    private String lastname;
    private String email;
    private String username;
    private String fullName;
private String Phone;

}
