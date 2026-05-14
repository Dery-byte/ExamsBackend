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
import com.exam.DTO.ResetPasswordWithTokenRequest;
import com.exam.DTO.TokenData;
import com.exam.repository.UserRepository;
import com.exam.service.MNotifyV2SmsService;
import com.exam.model.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Password Reset Controller - IN-MEMORY VERSION
 * No Redis required - stores tokens in memory
 * Perfect for development and testing
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




    @Value("${application.mailing.frontend.baseUrl:http://localhost:4200}")
    private String frontendUrl;

    // In-memory storage (replaces Redis)
    private static final Map<String, TokenData> resetTokens = new ConcurrentHashMap<>();
    private static final Map<String, Long> rateLimits = new ConcurrentHashMap<>();

    private static final int TOKEN_EXPIRY_MINUTES = 30;
    private static final long TOKEN_EXPIRY_MS = TOKEN_EXPIRY_MINUTES * 60 * 1000;
    private static final long RATE_LIMIT_MS = 2 * 60 * 1000; // 2 minutes

    /**
     * Request password reset link via SMS
     * POST /api/forgotten-password
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

            // Check if user exists
            User user = userRepository.findByPhone(phone);
            if (user == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("success", false, "message", "Phone number not found"));
            }

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

            // Generate unique token
            String resetToken = UUID.randomUUID().toString();

            // Store token with expiry time
            long expiryTime = now + TOKEN_EXPIRY_MS;
            resetTokens.put(resetToken, new TokenData(phone, user.getId(), expiryTime));

            // Update rate limit
            rateLimits.put(phone, now);

            // Create reset link
            String resetLink = String.format("%s/reset-password?token=%s", frontendUrl, resetToken);

            // Prepare variables for SMS
            Map<String, Object> vars = new HashMap<>();
            vars.put("username", user.getFullName());
            vars.put("resetUrl", resetLink);

            System.out.println("🔗 Reset link generated: " + resetLink);
            System.out.println("🎫 Token: " + resetToken);
            System.out.println("📱 Sending SMS to: " + phone);

            // Send SMS via MNotify
            String smsResponse = smsService.sendSms(phone, vars);

            System.out.println("✅ SMS sent successfully");
            System.out.println("📩 Response: " + smsResponse);

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
            TokenData tokenData = resetTokens.get(token);

            // Check if token exists and is not expired
            if (tokenData == null || tokenData.isExpired()) {
                if (tokenData != null) {
                    resetTokens.remove(token); // Clean up expired token
                }
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of(
                                "success", false,
                                "message", "Invalid or expired reset link"
                        ));
            }

            // Get user info
            Optional<User> user = userRepository.findById(tokenData.getUserId());
            if (user == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("success", false, "message", "User not found"));
            }

            System.out.println("✅ Token validated for: " + tokenData.getPhone());

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Token is valid",
                    "phone", tokenData.getPhone(),
                    "userId", tokenData.getUserId(),
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

            // Get token data
            TokenData tokenData = resetTokens.get(token);

            if (tokenData == null || tokenData.isExpired()) {
                resetTokens.remove(token); // Clean up
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of(
                                "success", false,
                                "message", "Invalid or expired reset link"
                        ));
            }

            // Get user
            //
            Optional<User> userOptional = userRepository.findById(tokenData.getUserId());

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
            userRepository.save(user);

            // Delete used token (one-time use)
            resetTokens.remove(token);

            System.out.println("✅ Password reset successful for: " + tokenData.getPhone());

            // Optional: Send confirmation SMS
            try {
                String confirmMessage = String.format(
                        "Hi %s, your password has been successfully reset. " +
                                "If you didn't do this, please contact support immediately.",
                        user.getFirstname()
                );
                smsService.sendSms(tokenData.getPhone(), confirmMessage);
                System.out.println("📱 Confirmation SMS sent");
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

    /**
     * Clean up expired tokens and rate limits
     */
    private void cleanupExpiredTokens() {
        long now = System.currentTimeMillis();

        // Remove expired tokens
        resetTokens.entrySet().removeIf(entry -> entry.getValue().isExpired());

        // Remove old rate limits
        rateLimits.entrySet().removeIf(entry -> (now - entry.getValue()) > RATE_LIMIT_MS);

        System.out.println("🧹 Cleanup: " + resetTokens.size() + " active tokens, " + rateLimits.size() + " rate limits");
    }
}