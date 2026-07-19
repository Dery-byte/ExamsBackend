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

    @PostConstruct
    public void initDefaultSettings() {
        if (!systemSettingRepository.existsById(ALLOW_CARRYOVER_REGISTRATION)) {
            systemSettingRepository.save(new SystemSetting(ALLOW_CARRYOVER_REGISTRATION, "false"));
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

    public Map<String, String> getAllSettings() {
        return systemSettingRepository.findAll().stream()
                .collect(Collectors.toMap(SystemSetting::getSettingKey, SystemSetting::getSettingValue));
    }
}
