package com.exam.service;

import com.exam.model.SystemSetting;
import com.exam.repository.SystemSettingRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class SystemSettingService {

    @Autowired
    private SystemSettingRepository systemSettingRepository;

    public static final String ALLOW_CARRYOVER_REGISTRATION = "ALLOW_CARRYOVER_REGISTRATION";
    /**
     * Master switch (Admin / Super Admin). When "false", no result slip is ever emailed, whatever the
     * per-quiz setting says. Lecturers choose per quiz; this only allows or blocks the feature system-wide.
     */
    public static final String EMAIL_REPORT_FEATURE_ENABLED = "EMAIL_REPORT_FEATURE_ENABLED";

    /** Super Admin switches: show the Marks Sheet navigation entry to each role. */
    public static final String MARKS_SHEET_VISIBLE_ADMIN    = "MARKS_SHEET_VISIBLE_ADMIN";
    public static final String MARKS_SHEET_VISIBLE_LECTURER = "MARKS_SHEET_VISIBLE_LECTURER";
    public static final String MARKS_SHEET_VISIBLE_STUDENT  = "MARKS_SHEET_VISIBLE_STUDENT";

    @PostConstruct
    public void initDefaultSettings() {
        for (String key : List.of(MARKS_SHEET_VISIBLE_ADMIN, MARKS_SHEET_VISIBLE_LECTURER, MARKS_SHEET_VISIBLE_STUDENT)) {
            if (!systemSettingRepository.existsById(key)) {
                systemSettingRepository.save(new SystemSetting(key, "true"));
            }
        }
        if (!systemSettingRepository.existsById(ALLOW_CARRYOVER_REGISTRATION)) {
            systemSettingRepository.save(new SystemSetting(ALLOW_CARRYOVER_REGISTRATION, "false"));
        }
        if (!systemSettingRepository.existsById(EMAIL_REPORT_FEATURE_ENABLED)) {
            systemSettingRepository.save(new SystemSetting(EMAIL_REPORT_FEATURE_ENABLED, "true"));
        }
    }

    public String getSetting(String key) {
        return systemSettingRepository.findById(key)
                .map(SystemSetting::getSettingValue)
                .orElse(null);
    }

    public boolean getBooleanSetting(String key, boolean defaultValue) {
        String val = getSetting(key);
        if (val == null) return defaultValue;
        return Boolean.parseBoolean(val);
    }

    public void updateSetting(String key, String value) {
        SystemSetting setting = systemSettingRepository.findById(key).orElse(new SystemSetting(key));
        setting.setSettingValue(value);
        systemSettingRepository.save(setting);
    }

    /** Navigation visibility flags, readable by every signed-in role. */
    public Map<String, Boolean> getFeatureFlags() {
        Map<String, Boolean> flags = new HashMap<>();
        flags.put("marksSheetAdmin",    getBooleanSetting(MARKS_SHEET_VISIBLE_ADMIN, true));
        flags.put("marksSheetLecturer", getBooleanSetting(MARKS_SHEET_VISIBLE_LECTURER, true));
        flags.put("marksSheetStudent",  getBooleanSetting(MARKS_SHEET_VISIBLE_STUDENT, true));
        return flags;
    }

    public Map<String, String> getAllSettings() {
        return systemSettingRepository.findAll().stream()
                .collect(Collectors.toMap(SystemSetting::getSettingKey, SystemSetting::getSettingValue));
    }
}
