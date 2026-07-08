package com.exam.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for Super Admin to create an HOD account.
 * The HOD gets ADMIN role and is linked to a department.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegisterHodRequest {
    private String firstname;
    private String lastname;
    private String email;
    private String password;
    private String phone;
    private String username;
    private Long departmentId;   // Which department this HOD heads
}
