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

    /**
     * When "true" (default), a student's exam clock keeps running while they are away
     * (browser closed, crash, power cut): on return they get only the time actually left.
     * When "false", the clock resumes from the last checkpoint.
     */
    public static final String EXAM_CLOCK_RUNS_WHILE_AWAY = "EXAM_CLOCK_RUNS_WHILE_AWAY";

    /** Super Admin switches for fees: the Fees page for students, paying online, paying in parts. */
    public static final String FEES_VISIBLE_STUDENT  = "FEES_VISIBLE_STUDENT";
    public static final String FEES_ONLINE_PAYMENT   = "FEES_ONLINE_PAYMENT";
    public static final String FEES_PART_PAYMENT     = "FEES_PART_PAYMENT";
    /** Students may pay for chosen items of an itemised fee (Tuition, SRC dues …), even when part payments are off. */
    public static final String FEES_ITEM_PAYMENT     = "FEES_ITEM_PAYMENT";
    /** Master switch for holding report cards / transcripts until fees are paid (rules are per programme). */
    public static final String FEES_RESULTS_HOLD     = "FEES_RESULTS_HOLD";

    /**
     * Super Admin switch: show the "Verify a transcript or report card" link on the sign-in page.
     * Only hides the link; /verify (and the codes printed on documents) keep working.
     */
    public static final String LOGIN_VERIFY_LINK_VISIBLE = "LOGIN_VERIFY_LINK_VISIBLE";

    @PostConstruct
    public void initDefaultSettings() {
        if (!systemSettingRepository.existsById(EXAM_CLOCK_RUNS_WHILE_AWAY)) {
            systemSettingRepository.save(new SystemSetting(EXAM_CLOCK_RUNS_WHILE_AWAY, "true"));
        }
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
        // Fees stay hidden from students until the Super Admin has set them up and switched them on
        if (!systemSettingRepository.existsById(FEES_VISIBLE_STUDENT)) {
            systemSettingRepository.save(new SystemSetting(FEES_VISIBLE_STUDENT, "false"));
        }
        if (!systemSettingRepository.existsById(FEES_RESULTS_HOLD)) {
            systemSettingRepository.save(new SystemSetting(FEES_RESULTS_HOLD, "false"));
        }
        for (String key : List.of(FEES_ONLINE_PAYMENT, FEES_PART_PAYMENT, FEES_ITEM_PAYMENT, LOGIN_VERIFY_LINK_VISIBLE)) {
            if (!systemSettingRepository.existsById(key)) {
                systemSettingRepository.save(new SystemSetting(key, "true"));
            }
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
        flags.put("feesStudent",        getBooleanSetting(FEES_VISIBLE_STUDENT, false));
        return flags;
    }

    public Map<String, String> getAllSettings() {
        return systemSettingRepository.findAll().stream()
                .collect(Collectors.toMap(SystemSetting::getSettingKey, SystemSetting::getSettingValue));
    }
}
