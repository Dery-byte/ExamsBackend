package com.exam.DTO;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class UserResponse {
    private Long id;
    private String username;
    private String email;
    private String firstName;
    private String lastName;
    private  String role;
    private String authorities;
    private  boolean enabled;
    private String phone;
    private boolean accountNonExpired;
    private boolean credentialsNonExpired;
    private boolean accountNonLocked;
    private com.exam.model.exam.Department department;
    /** True while the user must choose a new password before doing anything else. */
    private boolean mustChangePassword;

    public UserResponse(Long id, String username, String email, String firstName, String lastName, String role,
                        String authorities, boolean enabled, String phone, boolean accountNonExpired,
                        boolean credentialsNonExpired, boolean accountNonLocked, com.exam.model.exam.Department department) {
        this(id, username, email, firstName, lastName, role, authorities, enabled, phone, accountNonExpired,
                credentialsNonExpired, accountNonLocked, department, false);
    }
    // Only include fields you want to expose
}