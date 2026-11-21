package com.exam.auth;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AuthenticationResponse {
  private String token;
    private String message;  // Add this for cookie-based auth
    /** Sign-in refused for too many attempts: seconds until the student may try again (the page counts down). */
    private Long retryAfterSeconds;

}
