package com.exam.controller;

import com.exam.service.SystemSettingService;
import com.exam.service.fees.FinanceStaffService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Super Admin (path restricted in EndpointRules): the accounts office's logins, bursars and cashiers. */
@RestController
@RequestMapping("/api/v1/super-admin/finance-staff")
public class FinanceStaffController {

    @Autowired private FinanceStaffService staff;
    @Autowired private SystemSettingService settings;

    @GetMapping
    public List<Map<String, Object>> list() {
        return staff.list();
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody FinanceStaffService.StaffRequest req) {
        return staff.create(req);
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody FinanceStaffService.StaffRequest req) {
        return staff.update(id, req);
    }

    @PostMapping("/{id}/reset-password")
    public Map<String, Object> resetPassword(@PathVariable Long id) {
        return staff.resetPassword(id);
    }

    /** selfCashUp: a one-person accounts office, where the bursar's own cash-ups need no second check. */
    @GetMapping("/settings")
    public Map<String, Object> settings() {
        return Map.of("selfCashUp", settings.getBooleanSetting(SystemSettingService.FEES_SELF_CASHUP, false));
    }

    public record SettingsRequest(Boolean selfCashUp) {}

    @PutMapping("/settings")
    public Map<String, Object> saveSettings(@RequestBody SettingsRequest req) {
        if (req == null || req.selfCashUp() == null) throw new IllegalArgumentException("Nothing to save.");
        settings.updateSetting(SystemSettingService.FEES_SELF_CASHUP, String.valueOf(req.selfCashUp()));
        return settings();
    }
}
