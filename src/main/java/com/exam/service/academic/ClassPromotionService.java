package com.exam.service.academic;

import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.exam.Program;
import com.exam.repository.ProgramRepository;
import com.exam.repository.UserRepository;
import com.exam.service.ProgramService;
import com.exam.service.admin.AccountService;
import com.exam.service.comms.CurrentUserService;
import com.exam.service.features.FeatureService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Promotion in the school modes: a class is one year, so students move to another class (its first
 * term), not up a level. The next class comes from {@link ProgramService#promotionTarget}: the one the
 * Super Admin chose, else the next year's class (same stream), else the first year of the next section.
 * After a final class students leave the school: their accounts are deactivated (records are kept and
 * they can be reactivated). The Super Admin's promotion rules apply to forward moves; only the Super
 * Admin may override them, or move a student to any class. HODs act on their own section, and only
 * while the Super Admin allows HOD promotion.
 */
@Service
public class ClassPromotionService {

    public static final String LEFT_REASON = "Completed school";

    @Autowired private UserRepository userRepository;
    @Autowired private ProgramRepository programRepository;
    @Autowired private ProgramService programService;
    @Autowired private AcademicRecordService recordService;
    @Autowired private AccountService accountService;
    @Autowired private FeatureService featureService;

    /** Who in the class would move and where, with each student's standing against the promotion rules. */
    @Transactional(readOnly = true)
    public Map<String, Object> preview(User actor, Long programId) {
        Program cls = requireClass(actor, programId);
        Optional<Program> target = programService.promotionTarget(cls);
        List<Map<String, Object>> students = studentsOf(cls).stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>(recordService.eligibility(s));
            m.put("username", s.getUsername());
            m.put("term", s.getCurrentSemester());
            return m;
        }).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("classId", cls.getId());
        out.put("className", cls.getName());
        out.put("targetId", target.map(Program::getId).orElse(null));
        out.put("targetName", target.map(Program::getName).orElse(null));
        out.put("leaves", target.isEmpty());
        out.put("students", students);
        out.put("eligible", students.stream().filter(m -> Boolean.TRUE.equals(m.get("eligible"))).count());
        out.put("canOverride", actor.getRole() == Role.SUPER_ADMIN);
        return out;
    }

    /**
     * Moves the class's students (all, or the ones listed) to its next class, or out of the school after
     * a final class. Students short of the promotion rules stay, unless the Super Admin overrides.
     */
    @Transactional
    public Map<String, Object> promoteClass(User actor, Long programId, Collection<Long> studentIds, boolean override) {
        Program cls = requireClass(actor, programId);
        Optional<Program> target = programService.promotionTarget(cls);
        boolean mayOverride = override && actor.getRole() == Role.SUPER_ADMIN;
        Set<Long> only = studentIds == null || studentIds.isEmpty() ? null : new HashSet<>(studentIds);

        List<String> moved = new ArrayList<>();
        List<Map<String, Object>> heldBack = new ArrayList<>();
        for (User s : studentsOf(cls)) {
            if (only != null && !only.contains(s.getId())) continue;
            if (!mayOverride) {
                Map<String, Object> elig = recordService.eligibility(s);
                if (!Boolean.TRUE.equals(elig.get("eligible"))) {
                    heldBack.add(Map.of("studentId", s.getId(), "name", elig.get("name"), "reasons", elig.get("reasons")));
                    continue;
                }
            }
            moveTo(actor, s, target.orElse(null));
            moved.add(CurrentUserService.displayName(s));
        }
        String where = target.map(t -> "to " + t.getName()).orElse("out of the school (final class)");
        String msg = moved.isEmpty() ? "No one was moved." : "Moved " + moved.size() + " student" + (moved.size() == 1 ? "" : "s") + " from " + cls.getName() + " " + where + ".";
        if (!heldBack.isEmpty()) msg += " " + heldBack.size() + " held back by the promotion rules.";
        return Map.of("message", msg, "count", moved.size(), "heldBack", heldBack, "leaves", target.isEmpty());
    }

    /**
     * One student: to the class given (Super Admin only; any class, back or forward) or, with none given,
     * to their class's next class. Forward moves follow the promotion rules unless the Super Admin overrides.
     */
    @Transactional
    public Map<String, Object> moveStudent(User actor, Long studentId, Long toProgramId, boolean override) {
        User s = userRepository.findById(studentId).orElseThrow(() -> new IllegalArgumentException("Student not found."));
        if (s.getRole() != Role.NORMAL) throw new IllegalArgumentException("That account is not a student.");
        if (s.getProgram() == null) throw new IllegalArgumentException("This student has no class yet: set one on their record first.");
        Program from = requireClass(actor, s.getProgram().getId());
        if (!s.isEnabled()) throw new IllegalArgumentException("This account is deactivated: reactivate it first.");

        Program to;
        boolean forward;
        if (toProgramId != null) {
            if (actor.getRole() != Role.SUPER_ADMIN) throw new AccessDeniedException("Only the Super Admin can move a student to another class.");
            to = programRepository.findById(toProgramId).orElseThrow(() -> new IllegalArgumentException("Class not found."));
            if (to.getId().equals(from.getId())) throw new IllegalArgumentException(s.getFirstname() + " is already in " + to.getName() + ".");
            forward = to.getId().equals(programService.promotionTarget(from).map(Program::getId).orElse(null))
                    || ProgramService.ORDER.compare(to, from) > 0;
        } else {
            to = programService.promotionTarget(from).orElse(null);
            forward = true;
        }
        if (forward && !(override && actor.getRole() == Role.SUPER_ADMIN)) {
            Map<String, Object> elig = recordService.eligibility(s);
            if (!Boolean.TRUE.equals(elig.get("eligible")))
                throw new PromotionRefused(elig.get("reasons"), actor.getRole() == Role.SUPER_ADMIN);
        }
        moveTo(actor, s, to);
        String name = CurrentUserService.displayName(s);
        return Map.of("message", to == null ? name + " has completed school; their account is now deactivated." : name + " moved to " + to.getName() + ".",
                "programId", to == null ? "" : to.getId(), "leaves", to == null);
    }

    /** A student short of the promotion rules; carries the reasons and whether an override is possible. */
    public static class PromotionRefused extends RuntimeException {
        private final Object reasons;
        private final boolean canOverride;

        PromotionRefused(Object reasons, boolean canOverride) {
            super(canOverride ? "This student doesn't meet the promotion rules." : "This student doesn't meet the promotion rules. Only the Super Admin can override.");
            this.reasons = reasons;
            this.canOverride = canOverride;
        }

        public Object reasons() { return reasons; }
        public boolean canOverride() { return canOverride; }
    }

    // ── helpers ──

    private void moveTo(User actor, User s, Program to) {
        if (to == null) {
            // Leaving: the record stays, the account closes (and a boarder gives up their room)
            accountService.deactivate(actor, s.getId(), LEFT_REASON);
            return;
        }
        s.setProgram(to);
        s.setCurrentLevel(to.getConfiguredLevels().isEmpty() ? 1 : to.getConfiguredLevels().get(0));
        s.setCurrentSemester(1);
        userRepository.save(s);
    }

    private List<User> studentsOf(Program cls) {
        return userRepository.findByRole(Role.NORMAL).stream()
                .filter(User::isEnabled)
                .filter(s -> s.getProgram() != null && s.getProgram().getId().equals(cls.getId()))
                .sorted(Comparator.comparing((User s) -> Objects.toString(s.getLastname(), "")).thenComparing(s -> Objects.toString(s.getFirstname(), "")))
                .toList();
    }

    /** The class, if this person may promote in it (Super Admin; HOD of its section while HOD promotion is on). */
    private Program requireClass(User actor, Long programId) {
        Program cls = programRepository.findById(programId).orElseThrow(() -> new IllegalArgumentException("Class not found."));
        switch (actor.getRole()) {
            case SUPER_ADMIN -> { }
            case ADMIN -> {
                featureService.require(com.exam.model.features.Feature.HOD_PROMOTION, actor);
                if (actor.getDepartment() == null || cls.getDepartment() == null || !actor.getDepartment().getId().equals(cls.getDepartment().getId()))
                    throw new AccessDeniedException("You can only promote students in your own section.");
            }
            default -> throw new AccessDeniedException("Only the Super Admin and heads can promote students.");
        }
        return cls;
    }
}
