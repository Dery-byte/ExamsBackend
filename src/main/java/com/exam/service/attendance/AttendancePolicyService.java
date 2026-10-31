package com.exam.service.attendance;

import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.attendance.AttendanceMode;
import com.exam.model.attendance.AttendancePolicy;
import com.exam.model.exam.Department;
import com.exam.model.features.Feature;
import com.exam.repository.AttendancePolicyRepository;
import com.exam.repository.DepartmentRepository;
import com.exam.service.SystemSettingService;
import com.exam.service.comms.CurrentUserService;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Attendance rules: the Super Admin's system default and each department's own choice.
 * A department without its own row follows the default.
 */
@Service
public class AttendancePolicyService {

    @Autowired private AttendancePolicyRepository policies;
    @Autowired private DepartmentRepository departments;
    @Autowired private SystemSettingService settings;

    /** Attendance is new and needs its rules set first, so it starts switched off system-wide. */
    @PostConstruct
    void startSwitchedOff() {
        String key = Feature.ATTENDANCE.settingKey();
        if (settings.getSetting(key) == null) settings.updateSetting(key, "false");
    }

    @Transactional
    public AttendancePolicy system() {
        return policies.findFirstByDepartmentIsNullOrderByIdAsc().orElseGet(() -> policies.save(new AttendancePolicy()));
    }

    /** The rules in force for a department (null = the default). */
    @Transactional
    public AttendancePolicy effective(Department d) {
        if (d != null) {
            Optional<AttendancePolicy> own = policies.findByDepartment_Id(d.getId());
            if (own.isPresent()) return own.get();
        }
        return system();
    }

    public AttendanceMode modeFor(Department d) {
        return effective(d).getMode();
    }

    // ── Settings page ────────────────────────────────────────────────────────

    public record PolicyRequest(String mode, Integer codeSeconds, Integer readAloudCodeSeconds, Integer graceSeconds,
                                Integer checkInMinutes, Integer lateAfterMinutes, Integer radiusMeters,
                                Integer maxAccuracyMeters, Integer maxFailedAttempts, Boolean secondCheck,
                                Integer secondCheckMinutes, Boolean minimumRequired, Integer minimumPercent,
                                Boolean confirmUnclearLocation) {

        /** Without the unclear-location choice (callers from before it existed): left as it is. */
        public PolicyRequest(String mode, Integer codeSeconds, Integer readAloudCodeSeconds, Integer graceSeconds,
                             Integer checkInMinutes, Integer lateAfterMinutes, Integer radiusMeters,
                             Integer maxAccuracyMeters, Integer maxFailedAttempts, Boolean secondCheck,
                             Integer secondCheckMinutes, Boolean minimumRequired, Integer minimumPercent) {
            this(mode, codeSeconds, readAloudCodeSeconds, graceSeconds, checkInMinutes, lateAfterMinutes, radiusMeters,
                    maxAccuracyMeters, maxFailedAttempts, secondCheck, secondCheckMinutes, minimumRequired, minimumPercent, null);
        }
    }

    /** The precision default before the fairer location rule; rules still on it move to the new default. */
    static final int OLD_DEFAULT_ACCURACY_METERS = 30;
    static final int DEFAULT_ACCURACY_METERS = 50;

    /**
     * Once, at start-up: rules saved under the old location rule that still use the old ±30 m
     * default move to ±50 m (a chosen value is kept); every row is then marked current.
     */
    @Transactional
    public int upgradeLocationRules() {
        int moved = 0;
        List<AttendancePolicy> changed = new ArrayList<>();
        for (AttendancePolicy p : policies.findAll()) {
            if (p.getLocationRulesVersion() >= 2) continue;
            if (p.getMaxAccuracyMeters() == OLD_DEFAULT_ACCURACY_METERS) {
                p.setMaxAccuracyMeters(DEFAULT_ACCURACY_METERS);
                moved++;
            }
            p.setLocationRulesVersion(2);
            changed.add(p);
        }
        policies.saveAll(changed);
        return moved;
    }

    @Transactional
    public Map<String, Object> view(User actor) {
        boolean sa = actor.getRole() == Role.SUPER_ADMIN;
        if (!sa && actor.getRole() != Role.ADMIN) throw new AccessDeniedException("Only the Super Admin and HODs manage attendance rules.");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("system", dto(system()));
        if (sa) {
            Map<Long, AttendancePolicy> own = new HashMap<>();
            policies.findByDepartmentIsNotNull().forEach(p -> own.put(p.getDepartment().getId(), p));
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Department d : departments.findAll()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("departmentId", d.getId());
                m.put("departmentName", d.getName());
                m.put("policy", own.containsKey(d.getId()) ? dto(own.get(d.getId())) : null);
                rows.add(m);
            }
            rows.sort(Comparator.comparing(m -> String.valueOf(m.get("departmentName"))));
            out.put("departments", rows);
        } else {
            Department d = requireDepartment(actor);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("departmentId", d.getId());
            m.put("departmentName", d.getName());
            m.put("policy", policies.findByDepartment_Id(d.getId()).map(this::dto).orElse(null));
            out.put("departments", List.of(m));
        }
        return out;
    }

    @Transactional
    public Map<String, Object> saveSystem(User actor, PolicyRequest req) {
        if (actor.getRole() != Role.SUPER_ADMIN) throw new AccessDeniedException("Only the Super Admin changes the default attendance rules.");
        AttendancePolicy p = system();
        apply(p, req, actor);
        return dto(policies.save(p));
    }

    @Transactional
    public Map<String, Object> saveDepartment(User actor, Long departmentId, PolicyRequest req) {
        Department d = departmentFor(actor, departmentId);
        AttendancePolicy p = policies.findByDepartment_Id(d.getId()).orElseGet(() -> {
            AttendancePolicy copy = system().copyRules();
            copy.setDepartment(d);
            return copy;
        });
        apply(p, req, actor);
        return dto(policies.save(p));
    }

    /** The department follows the system default again. */
    @Transactional
    public void resetDepartment(User actor, Long departmentId) {
        Department d = departmentFor(actor, departmentId);
        policies.findByDepartment_Id(d.getId()).ifPresent(policies::delete);
    }

    private Department departmentFor(User actor, Long departmentId) {
        boolean sa = actor.getRole() == Role.SUPER_ADMIN;
        boolean ownHod = actor.getRole() == Role.ADMIN && actor.getDepartment() != null
                && actor.getDepartment().getId().equals(departmentId);
        if (!sa && !ownHod) throw new AccessDeniedException("You can only change attendance rules for your own department.");
        return departments.findById(departmentId).orElseThrow(() -> new IllegalArgumentException("Department not found."));
    }

    private static Department requireDepartment(User actor) {
        if (actor.getDepartment() == null) throw new AccessDeniedException("Your account is not linked to a department.");
        return actor.getDepartment();
    }

    private static void apply(AttendancePolicy p, PolicyRequest r, User actor) {
        if (r == null) throw new IllegalArgumentException("Nothing to save.");
        if (r.mode() != null) {
            try { p.setMode(AttendanceMode.valueOf(r.mode().trim().toUpperCase())); }
            catch (IllegalArgumentException e) { throw new IllegalArgumentException("Choose attendance by course or by programme."); }
        }
        if (r.codeSeconds() != null) p.setCodeSeconds(range(r.codeSeconds(), 10, 60, "Projector code timing", "seconds"));
        if (r.readAloudCodeSeconds() != null) p.setReadAloudCodeSeconds(range(r.readAloudCodeSeconds(), 30, 300, "Read-aloud code timing", "seconds"));
        if (r.graceSeconds() != null) p.setGraceSeconds(range(r.graceSeconds(), 0, 15, "Typing allowance", "seconds"));
        if (r.checkInMinutes() != null) p.setCheckInMinutes(range(r.checkInMinutes(), 3, 60, "Check-in window", "minutes"));
        if (r.lateAfterMinutes() != null) p.setLateAfterMinutes(range(r.lateAfterMinutes(), 0, 60, "Late after", "minutes"));
        if (r.radiusMeters() != null) p.setRadiusMeters(range(r.radiusMeters(), 10, 300, "Allowed distance", "metres"));
        if (r.maxAccuracyMeters() != null) p.setMaxAccuracyMeters(range(r.maxAccuracyMeters(), 5, 150, "Location precision", "metres"));
        if (r.confirmUnclearLocation() != null) p.setConfirmUnclearLocation(r.confirmUnclearLocation());
        if (r.maxFailedAttempts() != null) p.setMaxFailedAttempts(range(r.maxFailedAttempts(), 3, 10, "Wrong codes allowed", "attempts"));
        if (r.secondCheck() != null) p.setSecondCheck(r.secondCheck());
        if (r.secondCheckMinutes() != null) p.setSecondCheckMinutes(range(r.secondCheckMinutes(), 1, 10, "Second check window", "minutes"));
        if (r.minimumRequired() != null) p.setMinimumRequired(r.minimumRequired());
        if (r.minimumPercent() != null) p.setMinimumPercent(range(r.minimumPercent(), 1, 100, "Minimum attendance", "%"));
        if (p.getLateAfterMinutes() > p.getCheckInMinutes())
            throw new IllegalArgumentException("\"Late after\" can't be longer than the check-in window.");
        if (p.getGraceSeconds() >= p.getCodeSeconds())
            throw new IllegalArgumentException("The typing allowance must be shorter than the code timing.");
        p.setUpdatedBy(CurrentUserService.displayName(actor));
        p.setUpdatedAt(LocalDateTime.now());
    }

    private static int range(int v, int min, int max, String what, String unit) {
        if (v < min || v > max) throw new IllegalArgumentException(what + " must be between " + min + " and " + max + " " + unit + ".");
        return v;
    }

    public Map<String, Object> dto(AttendancePolicy p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", p.getMode().name());
        m.put("codeSeconds", p.getCodeSeconds());
        m.put("readAloudCodeSeconds", p.getReadAloudCodeSeconds());
        m.put("graceSeconds", p.getGraceSeconds());
        m.put("checkInMinutes", p.getCheckInMinutes());
        m.put("lateAfterMinutes", p.getLateAfterMinutes());
        m.put("radiusMeters", p.getRadiusMeters());
        m.put("maxAccuracyMeters", p.getMaxAccuracyMeters());
        m.put("confirmUnclearLocation", p.isConfirmUnclearLocation());
        m.put("maxFailedAttempts", p.getMaxFailedAttempts());
        m.put("secondCheck", p.isSecondCheck());
        m.put("secondCheckMinutes", p.getSecondCheckMinutes());
        m.put("minimumRequired", p.isMinimumRequired());
        m.put("minimumPercent", p.getMinimumPercent());
        m.put("updatedBy", p.getUpdatedBy());
        m.put("updatedAt", p.getUpdatedAt());
        return m;
    }
}
