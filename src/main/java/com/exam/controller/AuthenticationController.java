package com.exam.controller;

import com.exam.DTO.*;
import com.exam.auth.AuthenticationRequest;
import com.exam.auth.AuthenticationResponse;
import com.exam.auth.RegisterRequest;
import com.exam.helper.UserFoundException;
import com.exam.helper.UserNotFoundException;
import com.exam.model.User;
import com.exam.repository.UserRepository;
import com.exam.service.AuthenticationService;
import com.exam.service.JwtService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.mail.MessagingException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.UnsupportedEncodingException;
import java.security.Principal;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthenticationController {


    @Autowired
    private final AuthenticationService service;

    @Autowired
    private final UserDetailsService userDetailsService;


    @Autowired
    private JwtService jwtService;

    @Autowired
    private UserRepository userRepository;

//    @Autowired
//    private QuestionsService questionsService;
    @Autowired
    private PasswordEncoder passwordEncoder;

//    OAUTH2 GOOGLE CONTROLLER

    @GetMapping("/")
    public String home(){
        return "index";
    }

// STUDENT
    @PostMapping("/register")
    public ResponseEntity<AuthenticationResponse> register(
            @RequestBody RegisterRequest request
    ) throws UserFoundException {
        return ResponseEntity.ok(service.register(request));
    }

//LECTURER
    @PostMapping("/register/lecturer")
    public ResponseEntity<AuthenticationResponse> registerLecturer(
            @RequestBody RegisterRequest request
    ) throws UserFoundException {
        return ResponseEntity.ok(service.registerAslecturer(request));
    }
    //ADMIN
    @PostMapping("/register/admin")
    public ResponseEntity<AuthenticationResponse> registerAdmin(
            @RequestBody RegisterRequest request
    ) throws UserFoundException {
        return ResponseEntity.ok(service.registerAsAdmin(request));
    }






@PostMapping("/authenticate")
public ResponseEntity<AuthenticationResponse> authenticate(
        @RequestBody AuthenticationRequest request,
        HttpServletRequest httpRequest
) throws UserNotFoundException {

    // ✅ Debug logging (optional - remove in production)
//    log.debug("=== Authentication Request ===");
//    log.debug("Origin: {}", httpRequest.getHeader("Origin"));
//    log.debug("Method: {}", httpRequest.getMethod());
//    log.debug("Path: {}", httpRequest.getRequestURI());
//    log.debug("Content-Type: {}", httpRequest.getHeader("Content-Type"));

    // Authenticate user and generate token
    AuthenticationResponse authResponse = service.authenticate(request);

//    log.info("✅ User authenticated successfully: {}", request.getEmail());

    // Return token in response body (NOT in cookie)
    return ResponseEntity.ok(AuthenticationResponse.builder()
            .token(authResponse.getToken())  // or .accessToken() depending on your DTO
//            .tokenType("Bearer")
            .message("Authentication successful")
//            .email(authResponse.getEmail())  // Optional: return user info
//            .role(authResponse.getRole())    // Optional: return user role
            .build());
}

//





// NEW

@PostMapping("/logout")
public ResponseEntity<?> logout(
        HttpServletRequest request,
        HttpServletResponse response
) {
    // Your existing logout logic...
    // ✅ Clear the cookie properly
    String cookieHeader = "accessToken=; Path=/; Max-Age=0; HttpOnly; Secure; SameSite=None";
    response.addHeader("Set-Cookie", cookieHeader);
    return ResponseEntity.ok(Map.of("message", "Logout successful"));
}


 @GetMapping("/current-user")
    public ResponseEntity<UserResponse> getCurrentUser(Principal principal) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        User user = (User) userDetailsService.loadUserByUsername(principal.getName());
        // Get authorities from Spring Security
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        // Format as comma-separated string
        String authorities = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.joining(", "));
        UserResponse response = new UserResponse(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getFirstname(),
                user.getLastname(),
                user.getRole().name(),
                authorities,  // Now it's a clean string like "ROLE_ADMIN, PERMISSION_WRITE"
                user.isEnabled(),
                user.getPhone(),
                user.isAccountNonExpired(),
                user.isCredentialsNonExpired(),
                user.isAccountNonLocked()
        );
        return ResponseEntity.ok(response);
    }


    //Change Password if logged In
    @PutMapping("/updatepassword")
    String changePassword(Principal principal, @RequestBody User users){

        User user = (User) userDetailsService.loadUserByUsername(principal.getName());
//        user.setPassword(users.getPassword());
        user.setPassword(passwordEncoder.encode(users.getPassword()));
        userRepository.save(user);
return "Password changed " + user.getPassword();
    }

    //Change Password if not logged in
    @PutMapping("/changePassword")
    public String changePasswordNoLoggedIn(@RequestBody User users) {
        List<User> user = service.getAllUsers();
        for (User u : user) {
            if (u.getUsername().equals(users.getUsername())) {
                System.out.println("True");
                u.setPassword(passwordEncoder.encode(users.getPassword()));
                userRepository.save(u);
                return "Successful password reset " + " for " + users.getUsername();
            }
        }
        return "Username " + users.getUsername() + " not found ";
    }



    @GetMapping("/users")
    public List<User> getAllUsers() {
        return  service.getAllUsers();
    }











    @PostMapping("/forgotten-password")
    public ResponseEntity<Void> forgottenPassword(
            @RequestBody ForgottenPasswordRequest request
    ) throws MessagingException, UnsupportedEncodingException {
        service.forgottenPassword(request);
        return ResponseEntity.accepted().build();
    }


    @PostMapping("/reset-password")
    public ResponseEntity<Void> resetPassword(
            @RequestBody ResetPasswordRequest request
    ) {
        service.resetPassword(request);
        return ResponseEntity.ok().build();
    }















//    @GetMapping("/token-info")
//    public TokenInfo getTokenInfo(HttpServletRequest request) {
//        String accessToken = extractTokenFromHeader(request);
//
//        if (accessToken == null) {
//            System.out.println("❌ No valid Authorization header found");
//            return new TokenInfo(0);
//        }
//
//        System.out.println("✅ JWT token extracted");
//
//        try {
//            Claims claims = jwtService.extractAllClaims(accessToken);
//            long exp = claims.getExpiration().getTime() / 1000;
//            System.out.println("✅ Token expiration: " + exp + " (" + new Date(exp * 1000) + ")");
//            return new TokenInfo(exp);
//        } catch (Exception e) {
//            System.out.println("❌ Error extracting claims: " + e.getMessage());
//            return new TokenInfo(0);
//        }
//    }


    private String extractTokenFromHeader(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }

        return null;
    }


















    // Get all lecturers
    @GetMapping("/all/lecturers")
    public ResponseEntity<List<LecturerDTO>> getAllLecturers() {
        List<LecturerDTO> lecturers = service.getAllLecturers();
        return ResponseEntity.ok(lecturers);
    }


    // Get all lecturers
    @GetMapping("/all/students")
    public ResponseEntity<List<LecturerDTO>> getAllStudents() {
        List<LecturerDTO> lecturers = service.getAllStudents();
        return ResponseEntity.ok(lecturers);
    }



    // Get lecturer by ID
    @GetMapping("/lecturerbyId/{id}")
    public ResponseEntity<LecturerDTO> getLecturer(@PathVariable Long id) {
        return service.getLecturerById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }




    @GetMapping("/studentbyId/{id}")
    public ResponseEntity<LecturerDTO> getStudent(@PathVariable Long id) {
        return service.getStuentById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }





    // Create lecturer
    @PostMapping
    public ResponseEntity<LecturerDTO> createLecturer(@RequestBody User lecturer) {
        LecturerDTO saved = service.saveOrUpdateLecturer(lecturer);
        return ResponseEntity.ok(saved);
    }

    // Update lecturer
//
    @PutMapping("/update/lecturer/{id}")
    public ResponseEntity<LecturerDTO> updateLecturer(
            @PathVariable Long id,
            @RequestBody LecturerUpdateDTO updateDTO) {
        return service.updateLecturer(id, updateDTO)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }



    @PutMapping("/update/student/{id}")
    public ResponseEntity<LecturerDTO> updateStudent(
            @PathVariable Long id,
            @RequestBody LecturerUpdateDTO updateDTO) {
        System.out.println("UPDATE STUDENT CALLED - ID: " + id);  // ← Add this
        return service.updateStudent(id, updateDTO)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // Delete lecturer
    @DeleteMapping("/lecturer/{id}")
    public ResponseEntity<Void> deleteLecturer(@PathVariable Long id) {
        service.deleteLecturer(id);
        return ResponseEntity.noContent().build();
    }


    @DeleteMapping("/student/{id}")
    public ResponseEntity<Void> deleteStudent(@PathVariable Long id) {
        service.deleteStudent(id);
        return ResponseEntity.noContent().build();
    }









//CONTROLLER FOR GETTING LECTURER, ADMIN,STUDENT

    @GetMapping("/students/counts")
    public StudentResponse getStudents() {
        return service.getStudents();
    }

    @GetMapping("/lecturers/counts")
    public LecturerResponse getLecturers() {
        return service.getLecturers();
    }

    @GetMapping("/admins/counts")
    public AdminResponse getAdmins() {
        return service.getAdmins();
    }






        }


