package com.exam.controller;

import com.exam.service.SystemSettingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Read-only feature flags that every role's navigation depends on.
 * Only the Super Admin can change them (via PUT /api/v1/super-admin/settings).
 */
@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/api/v1/auth")
public class FeatureFlagController {

    @Autowired
    private SystemSettingService systemSettingService;
    @Autowired
    private com.exam.service.features.FeatureService featureService;
    @Autowired
    private com.exam.service.comms.CurrentUserService currentUserService;

    /** Marks-sheet visibility plus every switchable feature's state for the signed-in user. */
    @GetMapping("/feature-flags")
    public ResponseEntity<Map<String, Object>> getFeatureFlags() {
        Map<String, Object> out = new java.util.LinkedHashMap<>(systemSettingService.getFeatureFlags());
        out.put("features", featureService.flagsFor(currentUserService.current().orElse(null)));
        return ResponseEntity.ok(out);
    }
}
