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
    private String phone;
    private Integer currentLevel;
    private Integer currentSemester;
    private Long programId;
    private String programName;
    private Long departmentId;
    private java.util.List<Long> secondaryDepartmentIds;
    /** Students: MALE / FEMALE or null. */
    private String gender;
    /** Students: DAY / BOARDING (by the institution's residency mode) or null when not recorded. */
    private String residency;
    /** Students with a room: "Aggrey House · Room 14 · Bed 3". */
    private String residence;
}

