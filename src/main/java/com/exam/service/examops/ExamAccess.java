package com.exam.service.examops;

import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.exam.Category;
import com.exam.model.exam.Department;
import com.exam.model.exam.Quiz;
import com.exam.service.comms.NotificationService;
import org.springframework.security.access.AccessDeniedException;

import java.util.ArrayList;
import java.util.List;

/**
 * Who may manage a course or quiz:
 *  - Super Admin: everything;
 *  - HOD: courses/quizzes belonging to their department;
 *  - Lecturer: courses assigned to them and quizzes they created or whose course they teach.
 */
public final class ExamAccess {

    private ExamAccess() {}

    public static boolean isStaff(User u) {
        return u != null && (u.getRole() == Role.SUPER_ADMIN || u.getRole() == Role.ADMIN || u.getRole() == Role.LECTURER);
    }

    public static boolean canManageCourse(User u, Category course) {
        if (u == null || course == null) return false;
        return switch (u.getRole()) {
            case SUPER_ADMIN -> true;
            case ADMIN -> courseInDepartment(course, u.getDepartment());
            case LECTURER -> course.getUser() != null && course.getUser().getId().equals(u.getId());
            default -> false;
        };
    }

    public static boolean canManageQuiz(User u, Quiz quiz) {
        if (u == null || quiz == null) return false;
        return switch (u.getRole()) {
            case SUPER_ADMIN -> true;
            case ADMIN -> courseInDepartment(quiz.getCategory(), u.getDepartment())
                    || (quiz.getUser() != null && NotificationService.inDepartment(quiz.getUser(), u.getDepartment()));
            case LECTURER -> (quiz.getUser() != null && quiz.getUser().getId().equals(u.getId()))
                    || (quiz.getCategory() != null && quiz.getCategory().getUser() != null
                        && quiz.getCategory().getUser().getId().equals(u.getId()));
            default -> false;
        };
    }

    public static void requireCourse(User u, Category course) {
        if (!canManageCourse(u, course)) throw new AccessDeniedException("You don't manage this course.");
    }

    public static void requireQuiz(User u, Quiz quiz) {
        if (!canManageQuiz(u, quiz)) throw new AccessDeniedException("You don't manage this quiz.");
    }

    public static boolean courseInDepartment(Category c, Department dept) {
        return c != null && dept != null && c.getPrograms() != null && c.getPrograms().stream()
                .anyMatch(p -> p.getDepartment() != null && dept.getId().equals(p.getDepartment().getId()));
    }

    /** Frontend home route for a user's role. */
    public static String homePath(User u) {
        if (u == null || u.getRole() == null) return "";
        return switch (u.getRole()) {
            case SUPER_ADMIN -> "/super-admin";
            case ADMIN -> "/admin";
            case LECTURER -> "/lect";
            default -> "/user-dashboard";
        };
    }

    /** Staff who should hear about a quiz: its creator and its course's lecturer. */
    public static List<User> quizOwners(Quiz quiz) {
        List<User> out = new ArrayList<>();
        if (quiz.getUser() != null) out.add(quiz.getUser());
        User lecturer = quiz.getCategory() != null ? quiz.getCategory().getUser() : null;
        if (lecturer != null && out.stream().noneMatch(o -> o.getId().equals(lecturer.getId()))) out.add(lecturer);
        return out;
    }
}
