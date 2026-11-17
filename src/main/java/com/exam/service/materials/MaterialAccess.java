package com.exam.service.materials;

import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.exam.Category;
import com.exam.model.exam.Department;
import com.exam.model.features.Feature;
import com.exam.service.SystemSettingService;
import com.exam.service.attendance.AttendanceAccess;
import com.exam.service.examops.ExamAccess;
import com.exam.service.features.FeatureService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * The three switches, in order: the developer's module, the Super Admin's feature (with the course's
 * department's own choice, set by its HOD), then the lecturer's own visibility (in MaterialService).
 * Managing a course's materials follows course management: its lecturer, its department's HOD, the
 * Super Admin.
 */
@Component
public class MaterialAccess {

    @Autowired private SystemSettingService settings;
    @Autowired private FeatureService features;

    public static Department departmentOf(Category c) {
        return AttendanceAccess.departmentOf(c);
    }

    public void requireModule() {
        if (!settings.materialsOn()) throw new AccessDeniedException("Course notes and assignments are switched off for this institution.");
    }

    /** Whether the feature is on for this course (module + Super Admin + the course's department). */
    public boolean onForCourse(Category c) {
        if (!settings.materialsOn()) return false;
        return features.isOnForDepartment(Feature.COURSE_MATERIALS, departmentOf(c));
    }

    /** For staff pages: the Super Admin always gets in while the module is on. */
    public void requireOnFor(User u, Category c) {
        requireModule();
        if (u != null && u.getRole() == Role.SUPER_ADMIN) return;
        if (onForCourse(c)) return;
        String who = !features.isOnSystemWide(Feature.COURSE_MATERIALS) ? "the Super Admin" : "the department";
        throw new AccessDeniedException("Course notes and assignments have been turned off by " + who + ".");
    }

    /** Whether the user may see the feature at all (menus): module + their own department. */
    public void requireOnForUser(User u) {
        requireModule();
        if (!features.isOnFor(Feature.COURSE_MATERIALS, u))
            throw new AccessDeniedException("Course notes and assignments have been turned off by "
                    + (!features.isOnSystemWide(Feature.COURSE_MATERIALS) ? "the Super Admin" : "your department") + ".");
    }

    public static boolean canManage(User u, Category c) {
        return ExamAccess.canManageCourse(u, c);
    }

    public static void requireManage(User u, Category c) {
        if (!canManage(u, c)) throw new AccessDeniedException("You don't manage this course.");
    }
}
