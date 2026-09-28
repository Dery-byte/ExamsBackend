package com.exam.service.features;

import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.exam.Department;
import com.exam.model.features.DepartmentFeatureSetting;
import com.exam.model.features.Feature;
import com.exam.repository.DepartmentFeatureSettingRepository;
import com.exam.repository.DepartmentRepository;
import com.exam.service.SystemSettingService;
import com.exam.service.comms.CurrentUserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Decides whether a feature is on for a user, and lets the Super Admin / HODs switch features.
 * <p>
 * Effective state: system switch off → off for everyone. System switch on → SYSTEM features are
 * on; DEPARTMENT features follow the user's department choice if one is set, otherwise on.
 * The Super Admin is never restricted by department choices.
 */
@Service
public class FeatureService {

    @Autowired private SystemSettingService systemSettingService;
    @Autowired private DepartmentFeatureSettingRepository departmentSettings;
    @Autowired private DepartmentRepository departmentRepository;

    // ── Checks ───────────────────────────────────────────────────────────────

    public boolean isOnSystemWide(Feature f) {
        return systemSettingService.getBooleanSetting(f.settingKey(), true);
    }

    public boolean isOnFor(Feature f, User u) {
        if (u != null && u.getRole() == Role.SUPER_ADMIN) return true;
        if (!isOnSystemWide(f)) return false;
        if (f.scope() == Feature.Scope.SYSTEM || u == null) return true;
        Department d = departmentOf(u);
        if (d == null) return true;
        return departmentSettings.findByDepartment_IdAndFeatureKey(d.getId(), f.name())
                .map(DepartmentFeatureSetting::isEnabled).orElse(true);
    }

    /** Throws 403 (AccessDeniedException) with a readable reason when the feature is off for the user. */
    public void require(Feature f, User u) {
        if (isOnFor(f, u)) return;
        String who = !isOnSystemWide(f) ? "the Super Admin" : "your department";
        throw new AccessDeniedException(f.label() + " has been turned off by " + who + ".");
    }

    /** Every feature's state for this user (drives menus and buttons in the UI). */
    public Map<String, Boolean> flagsFor(User u) {
        Map<String, Boolean> m = new LinkedHashMap<>();
        for (Feature f : Feature.values()) m.put(f.name(), isOnFor(f, u));
        return m;
    }

    /** Student → program's department (or own); staff → own department. */
    public static Department departmentOf(User u) {
        if (u == null) return null;
        if (u.getRole() == Role.NORMAL && u.getProgram() != null && u.getProgram().getDepartment() != null)
            return u.getProgram().getDepartment();
        return u.getDepartment();
    }

    // ── Settings pages ───────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(User actor) {
        boolean sa = actor.getRole() == Role.SUPER_ADMIN;
        if (!sa && actor.getRole() != Role.ADMIN) throw new AccessDeniedException("Only the Super Admin and HODs manage features.");
        Department own = actor.getDepartment();
        if (!sa && own == null) throw new AccessDeniedException("Your account is not linked to a department.");

        List<Map<String, Object>> out = new ArrayList<>();
        for (Feature f : Feature.values()) {
            if (!sa && f.scope() != Feature.Scope.DEPARTMENT) continue;   // HODs only see department-level features
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", f.name());
            m.put("label", f.label());
            m.put("description", f.description());
            m.put("audience", f.audience());
            m.put("scope", f.scope().name());
            m.put("systemEnabled", isOnSystemWide(f));
            if (f.scope() == Feature.Scope.DEPARTMENT) {
                if (sa) {
                    m.put("departments", departmentSettings.findByFeatureKey(f.name()).stream().map(s -> {
                        Map<String, Object> d = new LinkedHashMap<>();
                        d.put("departmentId", s.getDepartment().getId());
                        d.put("departmentName", s.getDepartment().getName());
                        d.put("enabled", s.isEnabled());
                        d.put("updatedBy", s.getUpdatedBy());
                        return d;
                    }).toList());
                } else {
                    Optional<DepartmentFeatureSetting> s = departmentSettings.findByDepartment_IdAndFeatureKey(own.getId(), f.name());
                    m.put("departmentSetting", s.map(DepartmentFeatureSetting::isEnabled).orElse(null));   // null = follows system
                    m.put("effective", isOnSystemWide(f) && s.map(DepartmentFeatureSetting::isEnabled).orElse(true));
                    m.put("departmentName", own.getName());
                }
            }
            out.add(m);
        }
        return out;
    }

    @Transactional
    public void setSystemWide(User actor, String key, boolean enabled) {
        if (actor.getRole() != Role.SUPER_ADMIN) throw new AccessDeniedException("Only the Super Admin can change system-wide switches.");
        systemSettingService.updateSetting(feature(key).settingKey(), String.valueOf(enabled));
    }

    /** enabled = null clears the department's choice so it follows the system-wide switch again. */
    @Transactional
    public void setForDepartment(User actor, String key, Long departmentId, Boolean enabled) {
        Feature f = feature(key);
        if (f.scope() != Feature.Scope.DEPARTMENT) throw new IllegalArgumentException(f.label() + " can only be switched system-wide.");
        boolean sa = actor.getRole() == Role.SUPER_ADMIN;
        boolean ownHod = actor.getRole() == Role.ADMIN && actor.getDepartment() != null
                && actor.getDepartment().getId().equals(departmentId);
        if (!sa && !ownHod) throw new AccessDeniedException("You can only change settings for your own department.");
        if (!sa && !isOnSystemWide(f))
            throw new AccessDeniedException(f.label() + " is turned off by the Super Admin, so it can't be changed per department.");

        Department dept = departmentRepository.findById(departmentId).orElseThrow(() -> new IllegalArgumentException("Department not found."));
        Optional<DepartmentFeatureSetting> existing = departmentSettings.findByDepartment_IdAndFeatureKey(departmentId, f.name());
        if (enabled == null) {
            existing.ifPresent(departmentSettings::delete);
            return;
        }
        DepartmentFeatureSetting s = existing.orElseGet(DepartmentFeatureSetting::new);
        s.setDepartment(dept);
        s.setFeatureKey(f.name());
        s.setEnabled(enabled);
        s.setUpdatedBy(CurrentUserService.displayName(actor));
        s.setUpdatedAt(LocalDateTime.now());
        departmentSettings.save(s);
    }

    private static Feature feature(String key) {
        try { return Feature.valueOf(key); }
        catch (Exception e) { throw new IllegalArgumentException("Unknown feature: " + key); }
    }
}
