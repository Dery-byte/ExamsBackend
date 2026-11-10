////package com.exam.controller;
////
////// ============================================
////// BACKEND - PASSWORD RESET WITH LINK
////// ============================================
////
////import com.exam.DTO.SmsRequest;
////import com.exam.service.MNotifyV2SmsService;
////import com.exam.service.UserService;
////import com.exam.model.User;
////import org.springframework.beans.factory.annotation.Autowired;
////import org.springframework.beans.factory.annotation.Value;
////import org.springframework.http.HttpStatus;
////import org.springframework.http.ResponseEntity;
////import org.springframework.web.bind.annotation.*;
////
////import java.security.SecureRandom;
////import java.util.*;
////import java.util.concurrent.TimeUnit;
////
////@RestController
////@RequestMapping("/api")
////@CrossOrigin(origins = "*")
////public class PasswordResetController {
////
////    @Autowired
////    private MNotifyV2SmsService smsService;
////
////    @Autowired
////    private UserService userService;
////
////    @Autowired
////    private RedisTemplate<String, String> redisTemplate;
////
////    // Frontend URL - configure in application.properties
////    @Value("${app.frontend.url:http://localhost:4200}")
////    private String frontendUrl;
////
////    private final SecureRandom random = new SecureRandom();
////    private static final int TOKEN_EXPIRY_MINUTES = 30;
////    private static final String REDIS_KEY_PREFIX = "reset_token:";
////
////    /**
////     * Forgotten Password - Send Reset Link via SMS
////     * Frontend sends: { "recipient": ["0544073427"] }
////     * Backend generates and sends a clickable link
////     */
////    @PostMapping("/forgotten-password")
////    public ResponseEntity<?> forgottenPassword(@RequestBody SmsRequest request) {
////        try {
////            // Validate request
////            if (request.getRecipient() == null || request.getRecipient().isEmpty()) {
////                return ResponseEntity.badRequest()
////                        .body(Map.of("success", false, "message", "Phone number is required"));
////            }
////
////            String phone = normalizePhoneNumber(request.getRecipient().get(0));
////
////            // Check if user exists
////            User user = userService.findByPhone(phone);
////            if (user == null) {
////                return ResponseEntity.status(HttpStatus.NOT_FOUND)
////                        .body(Map.of("success", false, "message", "Phone number not found"));
////            }
////
////            // Rate limiting
////            String rateLimitKey = REDIS_KEY_PREFIX + "ratelimit:" + phone;
////            if (Boolean.TRUE.equals(redisTemplate.hasKey(rateLimitKey))) {
////                return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
////                        .body(Map.of(
////                                "success", false,
////                                "message", "Please wait before requesting another reset link"
////                        ));
////            }
////
////            // Generate secure token (random UUID)
////            String resetToken = UUID.randomUUID().toString();
////
////            // Store token in Redis with expiry (30 minutes)
////            String redisKey = REDIS_KEY_PREFIX + resetToken;
////            String value = phone + ":" + user.getId();
////            redisTemplate.opsForValue().set(redisKey, value, TOKEN_EXPIRY_MINUTES, TimeUnit.MINUTES);
////
////            // Set rate limit (2 minutes)
////            redisTemplate.opsForValue().set(rateLimitKey, "1", 2, TimeUnit.MINUTES);
////
////            // Create reset link - THIS IS THE LINK USER WILL CLICK
////            String resetLink = String.format(
////                    "%s/reset-password?token=%s",
////                    frontendUrl,
////                    resetToken
////            );
////
////            // Backend generates the SMS message with clickable link
////            String message = String.format(
////                    "Click this link to reset your password: %s. " +
////                            "This link will expire in %d minutes. " +
////                            "If you didn't request this, please ignore.",
////                    resetLink,
////                    TOKEN_EXPIRY_MINUTES
////            );
////
////            System.out.println("🔗 Reset link generated: " + resetLink);
////            System.out.println("📱 Sending SMS to: " + phone);
////
////            // Send SMS via MNotify with the link
////            String smsResponse = smsService.sendSms(request.getRecipient(), message);
////
////            System.out.println("✅ SMS sent successfully");
////            System.out.println("SMS Response: " + smsResponse);
////
////            return ResponseEntity.ok(Map.of(
////                    "success", true,
////                    "message", "Reset link sent to your phone",
////                    "recipient", request.getRecipient(),
////                    "expiresIn", TOKEN_EXPIRY_MINUTES * 60
////            ));
////
////        } catch (Exception e) {
////            System.err.println("❌ Error in forgotten password: " + e.getMessage());
////            e.printStackTrace();
////            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
////                    .body(Map.of(
////                            "success", false,
////                            "message", "Failed to send reset link. Please try again."
////                    ));
////        }
////    }
////
////    /**
////     * Validate Reset Token
////     * Called when user clicks the link and lands on reset page
////     */
////    @GetMapping("/validate-reset-token")
////    public ResponseEntity<?> validateResetToken(@RequestParam String token) {
////        try {
////            String redisKey = REDIS_KEY_PREFIX + token;
////            String storedValue = redisTemplate.opsForValue().get(redisKey);
////
////            if (storedValue == null) {
////                return ResponseEntity.status(HttpStatus.NOT_FOUND)
////                        .body(Map.of(
////                                "success", false,
////                                "message", "Invalid or expired reset link"
////                        ));
////            }
////
////            // Parse stored value
////            String[] parts = storedValue.split(":");
////            String phone = parts[0];
////            Long userId = Long.parseLong(parts[1]);
////
////            // Get user info
////            User user = userService.findById(userId);
////
////            return ResponseEntity.ok(Map.of(
////                    "success", true,
////                    "message", "Token is valid",
////                    "phone", phone,
////                    "userId", userId,
////                    "userName", user.getFirstName() + " " + user.getLastName()
////            ));
////
////        } catch (Exception e) {
////            System.err.println("❌ Error validating token: " + e.getMessage());
////            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
////                    .body(Map.of("success", false, "message", "Validation failed"));
////        }
////    }
////
////    /**
////     * Reset Password with Token
////     * Called when user submits new password
////     */
////    @PostMapping("/reset-password-with-token")
////    public ResponseEntity<?> resetPasswordWithToken(@RequestBody ResetPasswordWithTokenRequest request) {
////        try {
////            String token = request.getToken();
////            String newPassword = request.getNewPassword();
////
////            // Validate input
////            if (token == null || token.isEmpty() || newPassword == null || newPassword.isEmpty()) {
////                return ResponseEntity.badRequest()
////                        .body(Map.of("success", false, "message", "Token and password are required"));
////            }
////
////            // Validate password strength
////            if (newPassword.length() < 8) {
////                return ResponseEntity.badRequest()
////                        .body(Map.of("success", false, "message", "Password must be at least 8 characters"));
////            }
////
////            // Get stored data from Redis
////            String redisKey = REDIS_KEY_PREFIX + token;
////            String storedValue = redisTemplate.opsForValue().get(redisKey);
////
////            if (storedValue == null) {
////                return ResponseEntity.status(HttpStatus.NOT_FOUND)
////                        .body(Map.of(
////                                "success", false,
////                                "message", "Invalid or expired reset link"
////                        ));
////            }
////
////            // Parse stored value
////            String[] parts = storedValue.split(":");
////            String phone = parts[0];
////            Long userId = Long.parseLong(parts[1]);
////
////            // Update password
////            User user = userService.findById(userId);
////            if (user == null) {
////                return ResponseEntity.status(HttpStatus.NOT_FOUND)
////                        .body(Map.of("success", false, "message", "User not found"));
////            }
////
////            userService.updatePassword(user, newPassword);
////
////            // Delete used token
////            redisTemplate.delete(redisKey);
////
////            System.out.println("✅ Password reset successful for: " + phone);
////
////            // Optional: Send confirmation SMS
////            String confirmMessage = "Your password has been successfully reset. " +
////                    "If you didn't do this, please contact support immediately.";
////            smsService.sendSms(List.of(phone), confirmMessage);
////
////            return ResponseEntity.ok(Map.of(
////                    "success", true,
////                    "message", "Password updated successfully"
////            ));
////
////        } catch (Exception e) {
////            System.err.println("❌ Error in reset password: " + e.getMessage());
////            e.printStackTrace();
////            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
////                    .body(Map.of("success", false, "message", "Failed to reset password"));
////        }
////    }
////
////    /**
////     * Normalize phone number
////     */
//    private String normalizePhoneNumber(String phone) {
//        if (phone == null) return null;
//        return phone.replaceAll("[\\s\\-\\(\\)]", "");
//    }
////}
//
//
//
//
//package com.exam.controller;
//
//import com.exam.DTO.ForgotPasswordRequest;
//import com.exam.DTO.ResetPasswordWithTokenRequest;
//import com.exam.DTO.SmsRequest;
//import com.exam.repository.UserRepository;
//import com.exam.service.AuthenticationService;
//import com.exam.service.MNotifyV2SmsService;
//import com.exam.model.User;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.beans.factory.annotation.Value;
//import org.springframework.data.redis.core.RedisTemplate;
//import org.springframework.http.HttpStatus;
//import org.springframework.http.ResponseEntity;
//import org.springframework.web.bind.annotation.*;
//
//import java.util.*;
//import java.util.concurrent.TimeUnit;
//
//@RestController
//@RequestMapping("/api/v1/auth")
//@CrossOrigin(origins = "*")
//public class PasswordResetController {
//
//    @Autowired
//    private MNotifyV2SmsService smsService;
//
//    @Autowired
//    private UserRepository userRepository;
//
//    @Autowired
//    private AuthenticationService authenticationService;
//
//
//    @Autowired
//    private RedisTemplate<String, String> redisTemplate;
//
//    // Frontend URL - configure in application.properties
//    @Value("${app.frontend.url:http://localhost:4200}")
//    private String frontendUrl;
//
//    private static final int TOKEN_EXPIRY_MINUTES = 30;
//    private static final String REDIS_KEY_PREFIX = "reset_token:";
//
//    /**
//     * Forgotten Password - Send Reset Link via SMS
//     * Frontend sends: { "recipient": ["0544073427"] }
//     * Backend generates and sends a clickable link
//     */
//    @PostMapping("/forgotten-password/send/link")
//    public ResponseEntity<?> forgottenPassword(@RequestBody ForgotPasswordRequest request) {
//        try {
//            // Validate request
//            if (request.getRecipient() == null || request.getRecipient().isEmpty()) {
//                return ResponseEntity.badRequest()
//                        .body(Map.of("success", false, "message", "Phone number is required"));
//            }
//
//            String phone = normalizePhoneNumber(request.getRecipient().get(0));
//
//            // Check if user exists with this phone number
//            User user = userRepository.findByPhone(phone);
//            if (user == null) {
//                return ResponseEntity.status(HttpStatus.NOT_FOUND)
//                        .body(Map.of("success", false, "message", "Phone number not found"));
//            }
//
//            // Rate limiting - check if request was made recently
//            String rateLimitKey = REDIS_KEY_PREFIX + "ratelimit:" + phone;
//            if (Boolean.TRUE.equals(redisTemplate.hasKey(rateLimitKey))) {
//                return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
//                        .body(Map.of(
//                                "success", false,
//                                "message", "Please wait before requesting another reset link"
//                        ));
//            }
//
//            // Generate secure token (random UUID)
//            String resetToken = UUID.randomUUID().toString();
//
//            // Store token in Redis with expiry (30 minutes)
//            String redisKey = REDIS_KEY_PREFIX + resetToken;
//            String value = phone + ":" + user.getId();
//            redisTemplate.opsForValue().set(redisKey, value, TOKEN_EXPIRY_MINUTES, TimeUnit.MINUTES);
//
//            // Set rate limit (2 minutes)
//            redisTemplate.opsForValue().set(rateLimitKey, "1", 2, TimeUnit.MINUTES);
//
//            // Create reset link - THIS IS THE LINK USER WILL CLICK
//            String resetLink = String.format(
//                    "%s/reset-password?token=%s",
//                    frontendUrl,
//                    resetToken
//            );
//
//            // Prepare variables for SMS
//            Map<String, Object> vars = new HashMap<>();
//            vars.put("username", user.getFullName());
//            vars.put("resetUrl", resetLink);
//
//            System.out.println("🔗 Reset link generated: " + resetLink);
//            System.out.println("📱 Sending SMS to: " + phone);
//
//            // Send SMS via MNotify with the link
//            // The MNotifyV2SmsService will format the message automatically
//            String smsResponse = smsService.sendSms(phone, vars);
//
//            System.out.println("✅ SMS sent successfully");
//            System.out.println("SMS Response: " + smsResponse);
//
//            return ResponseEntity.ok(Map.of(
//                    "success", true,
//                    "message", "Reset link sent to your phone",
//                    "recipient", request.getRecipient(),
//                    "expiresIn", TOKEN_EXPIRY_MINUTES * 60
//            ));
//
//        } catch (Exception e) {
//            System.err.println("❌ Error in forgotten password: " + e.getMessage());
//            e.printStackTrace();
//            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
//                    .body(Map.of(
//                            "success", false,
//                            "message", "Failed to send reset link. Please try again."
//                    ));
//        }
//    }
//
//    /**
//     * Validate Reset Token
//     * Called when user clicks the link and lands on reset page
//     */
//    @GetMapping("/validate-reset-token")
//    public ResponseEntity<?> validateResetToken(@RequestParam String token) {
//        try {
//            String redisKey = REDIS_KEY_PREFIX + token;
//            String storedValue = redisTemplate.opsForValue().get(redisKey);
//
//            if (storedValue == null) {
//                return ResponseEntity.status(HttpStatus.NOT_FOUND)
//                        .body(Map.of(
//                                "success", false,
//                                "message", "Invalid or expired reset link"
//                        ));
//            }
//
//            // Parse stored value
//            String[] parts = storedValue.split(":");
//            String phone = parts[0];
//            Long userId = Long.parseLong(parts[1]);
//
//            // Get user info
//            Optional<User> user = userRepository.findById(userId);
//            if (user == null) {
//                return ResponseEntity.status(HttpStatus.NOT_FOUND)
//                        .body(Map.of("success", false, "message", "User not found"));
//            }
//
//            return ResponseEntity.ok(Map.of(
//                    "success", true,
//                    "message", "Token is valid",
//                    "phone", phone,
//                    "userId", userId,
//                    "userName", user.get().getFullName()
//            ));
//
//        } catch (Exception e) {
//            System.err.println("❌ Error validating token: " + e.getMessage());
//            e.printStackTrace();
//            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
//                    .body(Map.of("success", false, "message", "Validation failed"));
//        }
//    }
//
//    /**
//     * Reset Password with Token
//     * Called when user submits new password
//     */
//    @PostMapping("/reset-password-with-token")
//    public ResponseEntity<?> resetPasswordWithToken(@RequestBody ResetPasswordWithTokenRequest request) {
//        try {
//            String token = request.getToken();
//            String newPassword = request.getNewPassword();
//
//            // Validate input
//            if (token == null || token.isEmpty() || newPassword == null || newPassword.isEmpty()) {
//                return ResponseEntity.badRequest()
//                        .body(Map.of("success", false, "message", "Token and password are required"));
//            }
//
//            // Validate password strength
//            if (newPassword.length() < 8) {
//                return ResponseEntity.badRequest()
//                        .body(Map.of("success", false, "message", "Password must be at least 8 characters"));
//            }
//
//            // Get stored data from Redis
//            String redisKey = REDIS_KEY_PREFIX + token;
//            String storedValue = redisTemplate.opsForValue().get(redisKey);
//
//            if (storedValue == null) {
//                return ResponseEntity.status(HttpStatus.NOT_FOUND)
//                        .body(Map.of(
//                                "success", false,
//                                "message", "Invalid or expired reset link"
//                        ));
//            }
//
//            // Parse stored value
//            String[] parts = storedValue.split(":");
//            String phone = parts[0];
//            Long userId = Long.parseLong(parts[1]);
//
//            // Update password
//            Optional<User> user = userRepository.findById(userId);
//            if (user == null) {
//                return ResponseEntity.status(HttpStatus.NOT_FOUND)
//                        .body(Map.of("success", false, "message", "User not found"));
//            }
//
//            // Update the password
////            authenticationService.updatePassword(user, newPassword);
//
//            // Delete used token
//            redisTemplate.delete(redisKey);
//
//            System.out.println("✅ Password reset successful for: " + phone);
//
//            // Optional: Send confirmation SMS
//            try {
//                String confirmMessage = String.format(
//                        "Hi %s, your password has been successfully reset. " +
//                                "If you didn't do this, please contact support immediately.",
//                        user.get().getFullName()
//                );
//                smsService.sendSms(phone, confirmMessage);
//            } catch (Exception e) {
//                System.err.println("⚠️ Failed to send confirmation SMS: " + e.getMessage());
//            }
//
//            return ResponseEntity.ok(Map.of(
//                    "success", true,
//                    "message", "Password updated successfully"
//            ));
//
//        } catch (Exception e) {
//            System.err.println("❌ Error in reset password: " + e.getMessage());
//            e.printStackTrace();
//            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
//                    .body(Map.of("success", false, "message", "Failed to reset password"));
//        }
//    }
//
//    /**
//     * Normalize phone number (remove spaces, dashes, etc.)
//     */
//    private String normalizePhoneNumber(String phone) {
//        if (phone == null) return null;
//        return phone.replaceAll("[\\s\\-\\(\\)]", "");
//    }
//
//}








package com.exam.controller;

import com.exam.DTO.ForgotPasswordRequest;
import com.exam.DTO.ForgotPasswordUnifiedRequest;
import com.exam.DTO.ResetPasswordWithTokenRequest;
import com.exam.model.PasswordResetLink;
import com.exam.repository.PasswordResetLinkRepository;
import com.exam.repository.UserRepository;
import com.exam.service.academic.InstitutionService;
import com.exam.service.MNotifyV2SmsService;
import com.exam.service.Impl.EmailService;
import com.exam.service.Impl.EmailTemplateName;
import com.exam.model.User;
import com.exam.service.monitoring.ErrorMonitorService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "Forgot password": sends a one-time reset link by SMS or email, then sets the new password.
 * Links are stored in the database (hashed), so they survive restarts and redeploys; only the
 * 2-minute resend cooldown is kept in memory.
 */
@RestController
@RequestMapping("/api/v1/auth")
@CrossOrigin(origins = "*")
public class PasswordResetController {

    @Autowired
    private MNotifyV2SmsService smsService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private EmailService emailService;

    @Autowired
    private ErrorMonitorService errorMonitor;




    @Autowired
    private PasswordResetLinkRepository linkRepository;

    @Autowired
    private InstitutionService institution;

    @Autowired @Lazy @Qualifier("corsConfigurationSource")
    private CorsConfigurationSource cors;

    // Resend cooldown only; the links themselves live in the database
    private static final Map<String, Long> rateLimits = new ConcurrentHashMap<>();
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final int TOKEN_EXPIRY_MINUTES = 30;
    private static final long RATE_LIMIT_MS = 2 * 60 * 1000; // 2 minutes

    private static final String SHARED_PHONE = "This phone number is linked to more than one account. "
            + "Use your email address instead, or ask the administrator to reset your password.";
    private static final String SHARED_EMAIL = "This email address is linked to more than one account. "
            + "Use your phone number instead, or ask the administrator to reset your password.";

    // ─────────────────────────────────────────────────────────────────────────
    // UNIFIED endpoint: POST /api/v1/auth/forgotten-password
    // Body: { "phone": "0544073427" }  OR  { "email": "user@example.com" }
    // ─────────────────────────────────────────────────────────────────────────
    @PostMapping("/forgotten-password")
    public ResponseEntity<?> forgottenPasswordUnified(@RequestBody ForgotPasswordUnifiedRequest request,
                                                      HttpServletRequest http) {
        try {
            boolean hasPhone = request.getPhone() != null && !request.getPhone().trim().isEmpty();
            boolean hasEmail = request.getEmail() != null && !request.getEmail().trim().isEmpty();

            if (!hasPhone && !hasEmail) {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "message", "Please provide a phone number or email address."));
            }

            // ── Phone / SMS path ─────────────────────────────────────────────
            if (hasPhone) {
                String phone = normalizePhoneNumber(request.getPhone().trim());

                List<User> phoneMatches = userRepository.findAllByPhone(phone);
                if (phoneMatches.isEmpty()) {
                    return ResponseEntity.status(HttpStatus.NOT_FOUND)
                            .body(Map.of("success", false, "message", "No account found for this phone number."));
                }
                if (phoneMatches.size() > 1) {
                    // Several accounts share this number: we can't tell which one to reset
                    return ResponseEntity.status(HttpStatus.CONFLICT)
                            .body(Map.of("success", false, "message", SHARED_PHONE));
                }
                User user = phoneMatches.get(0);

                // Rate limiting
                Long lastRequest = rateLimits.get(phone);
                long now = System.currentTimeMillis();
                if (lastRequest != null && (now - lastRequest) < RATE_LIMIT_MS) {
                    long wait = (RATE_LIMIT_MS - (now - lastRequest)) / 1000;
                    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                            .body(Map.of("success", false, "message",
                                    "Please wait " + wait + " seconds before requesting again."));
                }

                String resetToken = issueToken(user, phone);
                rateLimits.put(phone, System.currentTimeMillis());

                Map<String, Object> vars = new HashMap<>();
                vars.put("username", user.getFullName());
                vars.put("resetUrl", resetUrl(resetToken));

                try {
                    smsService.sendSms(phone, vars);
                } catch (Exception sendFailed) {
                    // Nothing was sent: drop the link and the cooldown so the student can simply retry
                    discardToken(resetToken);
                    rateLimits.remove(phone);
                    throw sendFailed;
                }
                cleanupExpiredTokens();

                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "message", "Recovery code sent to your phone.",
                        "channel", "sms"
                ));
            }

            // ── Email path ────────────────────────────────────────────────────
            String email = request.getEmail().trim().toLowerCase();
            List<User> emailMatches = userRepository.findAllByEmailIgnoreCase(email);
            if (emailMatches.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("success", false, "message", "No account found for this email address."));
            }
            if (emailMatches.size() > 1) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("success", false, "message", SHARED_EMAIL));
            }
            User user = emailMatches.get(0);

            // Rate limiting by email
            Long lastRequest = rateLimits.get(email);
            long now = System.currentTimeMillis();
            if (lastRequest != null && (now - lastRequest) < RATE_LIMIT_MS) {
                long wait = (RATE_LIMIT_MS - (now - lastRequest)) / 1000;
                return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                        .body(Map.of("success", false, "message",
                                "Please wait " + wait + " seconds before requesting again."));
            }

            String resetToken = issueToken(user, email);
            rateLimits.put(email, System.currentTimeMillis());

            Map<String, Object> vars = new HashMap<>();
            vars.put("username", user.getFullName());
            vars.put("resetUrl", resetUrl(resetToken));

            try {
                emailService.sendEmail(user.getEmail(), EmailTemplateName.RESET_PASSWORD, vars, "Password Reset");
            } catch (Exception sendFailed) {
                discardToken(resetToken);
                rateLimits.remove(email);
                throw sendFailed;
            }
            cleanupExpiredTokens();

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Recovery link sent to your email.",
                    "channel", "email"
            ));

        } catch (Exception e) {
            // Record the real exception (with stack trace) on the developer dashboard
            errorMonitor.recordRequest(http, HttpStatus.INTERNAL_SERVER_ERROR.value(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message",
                            "We couldn't send the reset message just now. Please try again in a few minutes."));
        }
    }

    /**
     * Request password reset link via SMS  (legacy endpoint kept for back-compat)
     * POST /api/forgotten-password/send/link
     * Body: { "recipient": ["0544073427"] }
     */
    @PostMapping("/forgotten-password/send/link")
    public ResponseEntity<?> forgottenPassword(@RequestBody ForgotPasswordRequest request) {
        try {
            // Validate request
            if (request.getRecipient() == null || request.getRecipient().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "message", "Phone number is required"));
            }

            String phone = normalizePhoneNumber(request.getRecipient().get(0));

            // Check if user exists (phones aren't unique; a shared number can't be reset this way)
            List<User> phoneMatches = userRepository.findAllByPhone(phone);
            if (phoneMatches.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("success", false, "message", "Phone number not found"));
            }
            if (phoneMatches.size() > 1) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("success", false, "message", SHARED_PHONE));
            }
            User user = phoneMatches.get(0);

            // Rate limiting
            Long lastRequest = rateLimits.get(phone);
            long now = System.currentTimeMillis();
            if (lastRequest != null && (now - lastRequest) < RATE_LIMIT_MS) {
                long waitSeconds = (RATE_LIMIT_MS - (now - lastRequest)) / 1000;
                return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                        .body(Map.of(
                                "success", false,
                                "message", "Please wait " + waitSeconds + " seconds before requesting another reset link"
                        ));
            }

            String resetToken = issueToken(user, phone);
            rateLimits.put(phone, now);

            Map<String, Object> vars = new HashMap<>();
            vars.put("username", user.getFullName());
            vars.put("resetUrl", resetUrl(resetToken));

            try {
                smsService.sendSms(phone, vars);
            } catch (Exception sendFailed) {
                discardToken(resetToken);
                rateLimits.remove(phone);
                throw sendFailed;
            }

            // Clean up expired tokens periodically
            cleanupExpiredTokens();

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Reset link sent to your phone",
                    "recipient", request.getRecipient(),
                    "expiresIn", TOKEN_EXPIRY_MINUTES * 60
            ));

        } catch (Exception e) {
            System.err.println("❌ Error in forgotten password: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "success", false,
                            "message", "Failed to send reset link. Please try again."
                    ));
        }
    }

    /**
     * Validate reset token
     * GET /api/validate-reset-token?token=abc123...
     */
    @GetMapping("/validate-reset-token")
    public ResponseEntity<?> validateResetToken(@RequestParam String token) {
        try {
            Optional<PasswordResetLink> link = liveLink(token);
            if (link.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of(
                                "success", false,
                                "message", "Invalid or expired reset link"
                        ));
            }

            Optional<User> user = userRepository.findById(link.get().getUserId());
            if (user.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("success", false, "message", "User not found"));
            }

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Token is valid",
                    "phone", link.get().getSentTo(),
                    "userId", link.get().getUserId(),
                    "userName", user.get().getFullName()));

        } catch (Exception e) {
            System.err.println("❌ Error validating token: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", "Validation failed"));
        }
    }

    /**
     * Reset password with token
     * POST /api/reset-password-with-token
     * Body: { "token": "abc123...", "newPassword": "SecurePass123!" }
     */
    @PostMapping("/reset-password-with-token")
    public ResponseEntity<?> resetPasswordWithToken(@RequestBody ResetPasswordWithTokenRequest request) {
        try {
            String token = request.getToken();
            String newPassword = request.getNewPassword();

            // Validate input
            if (token == null || token.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "message", "Token is required"));
            }

            if (newPassword == null || newPassword.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "message", "Password is required"));
            }

            if (newPassword.length() < 8) {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "message", "Password must be at least 8 characters"));
            }

            Optional<PasswordResetLink> link = liveLink(token.trim());
            if (link.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of(
                                "success", false,
                                "message", "Invalid or expired reset link"
                        ));
            }

            Optional<User> userOptional = userRepository.findById(link.get().getUserId());

            // Check if user exists
            if (!userOptional.isPresent()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("success", false, "message", "User not found"));
            }

            // Get the actual User object from Optional
            User user = userOptional.get();

            // Now you can update the password
            String encodedPassword = passwordEncoder.encode(newPassword);
            user.setPassword(encodedPassword);
            user.setMustChangePassword(null);
            userRepository.save(user);

            // One use: this link and any other unused links for the account stop working
            linkRepository.deleteAllForUser(user.getId());

            // Optional: Send confirmation SMS
            try {
                String confirmMessage = String.format(
                        "Hi %s, your password has been successfully reset. " +
                                "If you didn't do this, please contact support immediately.",
                        user.getFirstname()
                );
                if (user.getPhone() != null && !user.getPhone().isBlank()) {
                    smsService.sendSms(user.getPhone(), confirmMessage);
                }
            } catch (Exception e) {
                System.err.println("⚠️ Failed to send confirmation SMS: " + e.getMessage());
            }

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Password updated successfully"
            ));

        } catch (Exception e) {
            System.err.println("❌ Error in reset password: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", "Failed to reset password"));
        }
    }

    /**
     * Normalize phone number (remove spaces, dashes, parentheses)
     */
    private String normalizePhoneNumber(String phone) {
        if (phone == null) return null;
        return phone.replaceAll("[\\s\\-\\(\\)]", "");
    }

    /** Stores a new 30-minute reset link for this account and returns the token that goes in the URL. */
    private String issueToken(User user, String sentTo) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        PasswordResetLink link = new PasswordResetLink();
        link.setTokenHash(sha256(token));
        link.setUserId(user.getId());
        link.setSentTo(sentTo.length() > 150 ? sentTo.substring(0, 150) : sentTo);
        link.setCreatedAt(Instant.now());
        link.setExpiresAt(Instant.now().plus(Duration.ofMinutes(TOKEN_EXPIRY_MINUTES)));
        linkRepository.save(link);
        return token;
    }

    /** The message never went out: remove its link. */
    private void discardToken(String token) {
        try {
            linkRepository.findByTokenHash(sha256(token)).ifPresent(linkRepository::delete);
        } catch (Exception ignored) { /* it expires on its own */ }
    }

    /** The stored link for this token, if it exists and has not expired. */
    private Optional<PasswordResetLink> liveLink(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        Optional<PasswordResetLink> link = linkRepository.findByTokenHash(sha256(token.trim()));
        if (link.isPresent() && link.get().isExpired()) {
            linkRepository.delete(link.get());
            return Optional.empty();
        }
        return link;
    }

    private String resetUrl(String token) {
        return webAppAddress() + "/reset-password?token=" + token;
    }

    /**
     * Where the link should open: the web app the request came from (its Origin, if CORS allows it),
     * otherwise the portal address from Institution settings / FRONTEND_URL. Same rule as fee payments,
     * so a link requested on one site never sends the student to another site's server.
     */
    private String webAppAddress() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            var request = attrs.getRequest();
            String origin = request.getHeader("Origin");
            CorsConfiguration allowed = cors.getCorsConfiguration(request);
            if (origin != null && allowed != null && allowed.checkOrigin(origin) != null && !"*".equals(allowed.checkOrigin(origin)))
                return origin.replaceAll("/+$", "");
        }
        return institution.portalUrl().replaceAll("/+$", "");
    }

    private static String sha256(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Drops expired links and old resend cooldowns. */
    private void cleanupExpiredTokens() {
        long now = System.currentTimeMillis();
        rateLimits.entrySet().removeIf(entry -> (now - entry.getValue()) > RATE_LIMIT_MS);
        try {
            linkRepository.deleteExpired(Instant.now());
        } catch (Exception ignored) { /* tried again on the next request */ }
    }
}