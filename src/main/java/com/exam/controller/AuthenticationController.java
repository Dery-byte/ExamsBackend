package com.exam.controller;

import com.exam.DTO.*;
import com.exam.auth.AuthenticationRequest;
import com.exam.auth.AuthenticationResponse;
import com.exam.auth.RegisterRequest;
import com.exam.helper.UserFoundException;
import com.exam.helper.UserNotFoundException;
import com.exam.model.Role;
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
import org.springframework.dao.DataIntegrityViolationException;

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
    private com.exam.config.ForcePasswordChangeInterceptor forcePasswordChange;


    @Autowired
    private final AuthenticationService service;

    @Autowired
    private final UserDetailsService userDetailsService;


    @Autowired
    private JwtService jwtService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private com.exam.repository.CategoryRepository categoryRepository;

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
    @Autowired
    private com.exam.service.features.FeatureService featureService;

    @PostMapping("/register")
    public ResponseEntity<?> register(
            @RequestBody RegisterRequest request,
            Principal principal
    ) {
        // Self sign-up can be closed by the Super Admin; staff adding students are unaffected
        boolean staffCaller = principal != null && userRepository.findByUsername(principal.getName())
                .map(u -> u.getRole() != Role.NORMAL).orElse(false);
        if (!staffCaller && !featureService.isOnSystemWide(com.exam.model.features.Feature.STUDENT_SELF_SIGNUP))
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(java.util.Map.of("message", "Self sign-up is closed. Please ask your department to create your account."));
        // No new accounts while the portal is under maintenance (staff adding students are unaffected)
        if (!staffCaller && maintenance.isOn())
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(java.util.Map.of("message", maintenance.message()));
        try {
            return ResponseEntity.ok(service.register(request));
        } catch (UserFoundException e) {
            return ResponseEntity
                    .status(HttpStatus.CONFLICT)
                    .body(java.util.Map.of("message", e.getMessage()));
        } catch (DataIntegrityViolationException e) {
            // Fallback: DB-level unique constraint (e.g. duplicate email or phone)
            String msg = e.getMostSpecificCause().getMessage();
            String friendly = "An account with these details already exists. Please sign in or use different information.";
            if (msg != null && msg.contains("email")) {
                friendly = "An account with this email address already exists. Please sign in or use a different email.";
            } else if (msg != null && msg.contains("phone")) {
                friendly = "An account with this phone number already exists.";
            }
            return ResponseEntity
                    .status(HttpStatus.CONFLICT)
                    .body(java.util.Map.of("message", friendly));
        } catch (Exception e) {
            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(java.util.Map.of("message", "Registration failed. Please try again."));
        }
    }

//LECTURER
    @PostMapping("/register/lecturer")
    public ResponseEntity<?> registerLecturer(
            @RequestBody RegisterRequest request
    ) {
        try {
            return ResponseEntity.ok(service.registerAslecturer(request));
        } catch (UserFoundException e) {
            return ResponseEntity
                    .status(HttpStatus.CONFLICT)
                    .body(java.util.Map.of("message", e.getMessage()));
        } catch (DataIntegrityViolationException e) {
            String msg = e.getMostSpecificCause().getMessage();
            String friendly = "An account with these details already exists.";
            if (msg != null && msg.contains("email")) {
                friendly = "An account with this email address already exists.";
            }
            return ResponseEntity
                    .status(HttpStatus.CONFLICT)
                    .body(java.util.Map.of("message", friendly));
        } catch (Exception e) {
            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(java.util.Map.of("message", "Registration failed. Please try again."));
        }
    }
    //ADMIN
    @PostMapping("/register/admin")
    public ResponseEntity<AuthenticationResponse> registerAdmin(
            @RequestBody RegisterRequest request
    ) throws UserFoundException {
        return ResponseEntity.ok(service.registerAsAdmin(request));
    }

    // SUPER ADMIN  (bootstrap — secured at network/infra level in production)
    @PostMapping("/register/super-admin")
    public ResponseEntity<?> registerSuperAdmin(
            @RequestBody RegisterRequest request,
            Principal principal
    ) {
        // Open only for first-time setup (no Super Admin yet); afterwards only a Super Admin may add another
        boolean anyExists = !userRepository.findByRole(Role.SUPER_ADMIN).isEmpty();
        if (anyExists) {
            User caller = principal == null ? null : userRepository.findByUsername(principal.getName()).orElse(null);
            if (caller == null || caller.getRole() != Role.SUPER_ADMIN)
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(java.util.Map.of("message", "Only a Super Admin can create another Super Admin account."));
        }
        try {
            return ResponseEntity.ok(service.registerAsSuperAdmin(request));
        } catch (UserFoundException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(java.util.Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(java.util.Map.of("message", "Registration failed."));
        }
    }






@Autowired
private com.exam.config.RateLimiter rateLimiter;
@Autowired
private com.exam.service.admin.MaintenanceService maintenance;
private static final int MAX_FAILED_LOGINS = 5;
private static final long LOGIN_LOCK_WINDOW_MS = 15 * 60 * 1000L;

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

    // Per-account lockout: too many wrong passwords for one username locks it for a while
    String lockKey = "login-fail:" + String.valueOf(request.getUsername()).trim().toLowerCase();
    if (rateLimiter.count(lockKey, LOGIN_LOCK_WINDOW_MS) >= MAX_FAILED_LOGINS) {
        long wait = Math.max(1, rateLimiter.retryAfterSeconds(lockKey, LOGIN_LOCK_WINDOW_MS) / 60);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(AuthenticationResponse.builder()
                .message("Too many failed sign-in attempts for this account. Try again in " + wait + " minute(s).").build());
    }

    // Maintenance: nobody new signs in except those the developer allowed (developers use their own sign-in)
    if (maintenance.isOn()) {
        Role role = userRepository.findByUsername(String.valueOf(request.getUsername()).trim()).map(User::getRole).orElse(null);
        if (role == null || !maintenance.allowsSignIn(role))
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(AuthenticationResponse.builder()
                    .message(maintenance.message()).build());
    }

    // Authenticate user and generate token
    AuthenticationResponse authResponse;
    try {
        authResponse = service.authenticate(request);
        rateLimiter.reset(lockKey);
    } catch (org.springframework.security.authentication.DisabledException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(AuthenticationResponse.builder()
                .message("This account has been deactivated. Please contact your administrator.").build());
    } catch (org.springframework.security.core.AuthenticationException e) {
        // 400 (not 401): the frontend treats 401 as "session expired" and reloads the sign-in page
        rateLimiter.record(lockKey);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(AuthenticationResponse.builder()
                .message("Incorrect username or password.").build());
    }

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
                user.isAccountNonLocked(),
                user.getDepartment()
        );
        response.setMustChangePassword(forcePasswordChange.mustChange(user));
        return ResponseEntity.ok(response);
    }


    //Change Password if logged In
    @PutMapping("/updatepassword")
    public ResponseEntity<?> changePassword(Principal principal, @RequestBody Map<String, String> body){
        User user = (User) userDetailsService.loadUserByUsername(principal.getName());
        String next = body.get("password");
        if (next == null || next.length() < 6)
            return ResponseEntity.badRequest().body(Map.of("message", "Password must be at least 6 characters."));
        String current = body.get("currentPassword");
        if (current != null && !passwordEncoder.matches(current, user.getPassword()))
            return ResponseEntity.badRequest().body(Map.of("message", "Your current password is not correct."));
        if (passwordEncoder.matches(next, user.getPassword()))
            return ResponseEntity.badRequest().body(Map.of("message", "Choose a password different from the current one."));
        user.setPassword(passwordEncoder.encode(next));
        user.setMustChangePassword(null);
        userRepository.save(user);
        return ResponseEntity.ok(Map.of("message", "Password changed."));
    }

    // Update own profile — works for ANY role (ADMIN, LECTURER, NORMAL)
    // Uses the authenticated principal so no role filter needed.
    @PutMapping("/update-my-profile")
    public ResponseEntity<?> updateMyProfile(
            Principal principal,
            @RequestBody Map<String, String> body) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        User user = (User) userDetailsService.loadUserByUsername(principal.getName());

        String firstname = body.get("firstname");
        String lastname  = body.get("lastname");
        String email     = body.get("email");
        String phone     = body.get("phone");

        if (firstname != null && !firstname.isBlank()) user.setFirstname(firstname);
        if (lastname  != null && !lastname.isBlank())  user.setLastname(lastname);
        if (email     != null && !email.isBlank())     user.setEmail(email);
        if (phone     != null && !phone.isBlank())     user.setPhone(phone);

        userRepository.save(user);

        UserResponse response = new UserResponse(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getFirstname(),
                user.getLastname(),
                user.getRole().name(),
                user.getAuthorities().stream()
                        .map(a -> a.getAuthority())
                        .collect(Collectors.joining(", ")),
                user.isEnabled(),
                user.getPhone(),
                user.isAccountNonExpired(),
                user.isCredentialsNonExpired(),
                user.isAccountNonLocked(),
                user.getDepartment()
        );
        return ResponseEntity.ok(response);
    }

    //Change Password if not logged in
    @PutMapping("/changePassword")
    public String changePasswordNoLoggedIn(@RequestBody User users) {
        List<User> user = service.getAllUsers();
        for (User u : user) {
            if (u.getUsername().equals(users.getUsername())) {
                System.out.println("True");
                u.setPassword(passwordEncoder.encode(users.getPassword()));
                u.setMustChangePassword(Boolean.TRUE);
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











    @PostMapping("/forgotten-password/email-only")
    public ResponseEntity<Void> forgottenPasswordEmailOnly(
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

















    // Get all lecturers (unfiltered) - for general management screens
    @GetMapping("/all/lecturers")
    public ResponseEntity<List<LecturerDTO>> getAllLecturers(Principal principal) {
        String username = principal != null ? principal.getName() : null;
        List<LecturerDTO> lecturers = service.getAllLecturers(username);
        return ResponseEntity.ok(lecturers);
    }

    // Get lecturers filtered to the calling admin/HOD's department - used for sheet creation
    @GetMapping("/lecturers/by-department")
    public ResponseEntity<List<LecturerDTO>> getLecturersByDepartment(Principal principal) {
        String username = principal != null ? principal.getName() : null;
        List<LecturerDTO> lecturers = service.getLecturersByDepartment(username);
        return ResponseEntity.ok(lecturers);
    }


    // Get all students
    @GetMapping("/all/students")
    public ResponseEntity<List<LecturerDTO>> getAllStudents(Principal principal) {
        String username = principal != null ? principal.getName() : null;
        List<LecturerDTO> lecturers = service.getAllStudents(username);
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
    public ResponseEntity<?> deleteLecturer(@PathVariable Long id) {
        try {
            service.deleteLecturer(id);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", e.getMessage()));
        }
        return ResponseEntity.noContent().build();
    }


    @DeleteMapping("/student/{id}")
    public ResponseEntity<?> deleteStudent(@PathVariable Long id) {
        try {
            service.deleteStudent(id);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", e.getMessage()));
        }
        return ResponseEntity.noContent().build();
    }









//CONTROLLER FOR GETTING LECTURER, ADMIN,STUDENT

    @GetMapping("/students/counts")
    public StudentResponse getStudents(Principal principal) {
        String username = principal != null ? principal.getName() : null;
        return service.getStudents(username);
    }

    @GetMapping("/lecturers/counts")
    public LecturerResponse getLecturers(Principal principal) {
        String username = principal != null ? principal.getName() : null;
        return service.getLecturers(username);
    }

    @GetMapping("/admins/counts")
    public AdminResponse getAdmins() {
        return service.getAdmins();
    }

    @GetMapping("/admin/dashboard-stats")
    public ResponseEntity<?> getAdminDashboardStats(Principal principal) {
        User user = (User) userDetailsService.loadUserByUsername(principal.getName());
        com.exam.model.exam.Department dept = user.getDepartment();
        
        if (dept == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "No department assigned to this HOD."));
        }
        
        long candidatesCount = userRepository.findByRole(Role.NORMAL).stream()
            .filter(u -> u.getDepartment() != null && u.getDepartment().getId().equals(dept.getId()))
            .count();
            
        long personnelCount = userRepository.findByRole(Role.LECTURER).stream()
            .filter(u -> u.getDepartment() != null && u.getDepartment().getId().equals(dept.getId()))
            .count();
            
        List<com.exam.model.exam.Category> allCats = categoryRepository.findAll();
        List<com.exam.model.exam.Category> deptCats = allCats.stream()
            .filter(c -> c.getPrograms().stream().anyMatch(p -> p.getDepartment() != null && p.getDepartment().getId().equals(dept.getId())))
            .collect(Collectors.toList());
            
        long modulesCount = deptCats.size();
        
        long assessmentsCount = deptCats.stream()
            .mapToLong(c -> c.getQuizzes().size())
            .sum();
            
        Map<String, Object> stats = new java.util.HashMap<>();
        stats.put("candidates", candidatesCount);
        stats.put("personnel", personnelCount);
        stats.put("modules", modulesCount);
        stats.put("assessments", assessmentsCount);
        
        return ResponseEntity.ok(stats);
    }

        }


