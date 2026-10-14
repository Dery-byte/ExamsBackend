package com.exam.service.attendance;

import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.attendance.AttendanceMode;
import com.exam.model.attendance.AttendanceSession;
import com.exam.model.exam.Category;
import com.exam.model.exam.Department;
import com.exam.model.exam.Program;
import com.exam.service.examops.ExamAccess;
import org.springframework.security.access.AccessDeniedException;

import java.util.Comparator;
import java.util.Objects;

/**
 * Who may run attendance:
 *  - course sessions: whoever manages the course (its lecturer, the department's HOD, the Super Admin);
 *  - programme sessions: the programme's HOD and the Super Admin only.
 */
public final class AttendanceAccess {

    private AttendanceAccess() {}

    /** The department whose attendance rules apply to a course: its programmes' department, else its lecturer's. */
    public static Department departmentOf(Category c) {
        if (c == null) return null;
        if (c.getPrograms() != null) {
            Department d = c.getPrograms().stream()
                    .sorted(Comparator.comparing(Program::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                    .map(Program::getDepartment).filter(Objects::nonNull).findFirst().orElse(null);
            if (d != null) return d;
        }
        return c.getUser() != null ? c.getUser().getDepartment() : null;
    }

    public static boolean canOpenCourse(User u, Category course) {
        return ExamAccess.canManageCourse(u, course);
    }

    public static boolean canOpenProgramme(User u, Program p) {
        if (u == null || p == null) return false;
        if (u.getRole() == Role.SUPER_ADMIN) return true;
        return u.getRole() == Role.ADMIN && sameDepartment(u.getDepartment(), p.getDepartment());
    }

    public static boolean canManage(User u, AttendanceSession s) {
        if (u == null || s == null) return false;
        return switch (u.getRole()) {
            case SUPER_ADMIN -> true;
            case ADMIN -> sameDepartment(u.getDepartment(), s.getDepartment())
                    || (s.getCourse() != null && ExamAccess.courseInDepartment(s.getCourse(), u.getDepartment()));
            case LECTURER -> s.getScope() == AttendanceMode.COURSE && s.getCourse() != null && s.getCourse().getUser() != null
                    && s.getCourse().getUser().getId().equals(u.getId());
            default -> false;
        };
    }

    public static void requireManage(User u, AttendanceSession s) {
        if (!canManage(u, s)) throw new AccessDeniedException("You don't manage this class.");
    }

    public static boolean sameDepartment(Department a, Department b) {
        return a != null && b != null && a.getId() != null && a.getId().equals(b.getId());
    }

    public static boolean isStaff(User u) {
        return ExamAccess.isStaff(u);
    }
}
