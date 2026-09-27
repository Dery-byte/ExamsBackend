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

    @GetMapping("/feature-flags")
    public ResponseEntity<Map<String, Boolean>> getFeatureFlags() {
        return ResponseEntity.ok(systemSettingService.getFeatureFlags());
    }
}
