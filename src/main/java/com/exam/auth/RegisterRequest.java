package com.exam.auth;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RegisterRequest {

  private String firstname;
  private String lastname;
  private String email;
  private String password;
  private String phone;
  private String username;

  /** For students: the program they belong to (e.g. Computer Science BS). */
  private Long programId;

  /** For students: their current academic level (100, 200, 300 …). */
  private Integer currentLevel;

  /** For lecturers and HODs: the department they belong to. */
  private Long departmentId;

  /** For students: MALE / FEMALE (optional). */
  private String gender;

  /** For students added by staff while the institution has day students and boarders: DAY / BOARDING (optional). */
  private String residency;
}
