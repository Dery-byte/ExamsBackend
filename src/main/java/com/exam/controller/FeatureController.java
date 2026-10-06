package com.exam.controller;

import com.exam.exception.ErrorMessage;
import com.exam.model.User;
import com.exam.model.features.Feature;
import com.exam.service.SystemSettingService;
import com.exam.service.comms.CurrentUserService;
import com.exam.service.features.FeatureService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

/** Feature switches: Super Admin (system-wide + any department) and HODs (their own department). */
@RestController
@CrossOrigin(origins = "*")
public class FeatureController {

    @Autowired private CurrentUserService currentUserService;
    @Autowired private FeatureService featureService;
    @Autowired private SystemSettingService systemSettingService;

    @GetMapping("/api/features")
    public ResponseEntity<?> list() {
        return withUser(u -> ResponseEntity.ok(featureService.list(u)));
    }

    @PutMapping("/api/features/{key}")
    public ResponseEntity<?> setSystemWide(@PathVariable String key, @RequestBody Map<String, Boolean> body,
                                           jakarta.servlet.http.HttpServletRequest request) {
        request.setAttribute(com.exam.config.AuditInterceptor.AUDIT_DETAILS, key + " = " + (Boolean.TRUE.equals(body.get("enabled")) ? "on" : "off"));
        return withUser(u -> {
            featureService.setSystemWide(u, key, Boolean.TRUE.equals(body.get("enabled")));
            return ResponseEntity.ok(featureService.list(u));
        });
    }

    /** Body {"enabled": true|false|null}; null clears the department's choice. */
    @PutMapping("/api/features/{key}/departments/{departmentId}")
    public ResponseEntity<?> setForDepartment(@PathVariable String key, @PathVariable Long departmentId,
                                              @RequestBody Map<String, Boolean> body,
                                              jakarta.servlet.http.HttpServletRequest request) {
        Boolean v = body.get("enabled");
        request.setAttribute(com.exam.config.AuditInterceptor.AUDIT_DETAILS,
                key + " = " + (v == null ? "follow system" : v ? "on" : "off") + " (department " + departmentId + ")");
        return withUser(u -> {
            featureService.setForDepartment(u, key, departmentId, body.get("enabled"));
            return ResponseEntity.ok(featureService.list(u));
        });
    }

    /** Settings the sign-in / sign-up pages need before anyone is signed in. */
    @GetMapping("/api/v1/auth/public-settings")
    public ResponseEntity<?> publicSettings() {
        return ResponseEntity.ok(Map.of(
                "studentSelfSignup", featureService.isOnSystemWide(Feature.STUDENT_SELF_SIGNUP),
                // No point linking to the verify page while verification itself is switched off
                "showVerifyLink", featureService.isOnSystemWide(Feature.DOCUMENT_VERIFICATION)
                        && systemSettingService.getBooleanSetting(SystemSettingService.LOGIN_VERIFY_LINK_VISIBLE, true)));
    }

    private interface UserAction { ResponseEntity<?> apply(User u); }

    private ResponseEntity<?> withUser(UserAction action) {
        Optional<User> user = currentUserService.current();
        if (user.isEmpty()) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorMessage("Please sign in."));
        try {
            return action.apply(user.get());
        } catch (AccessDeniedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ErrorMessage(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ErrorMessage(e.getMessage()));
        }
    }
}
