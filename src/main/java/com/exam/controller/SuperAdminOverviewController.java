package com.exam.controller;

import com.exam.model.Role;
import com.exam.model.fees.FeePayment;
import com.exam.repository.AttendanceRecordRepository;
import com.exam.repository.CategoryRepository;
import com.exam.repository.DepartmentRepository;
import com.exam.repository.FeePaymentRepository;
import com.exam.repository.ProgramRepository;
import com.exam.repository.QuizAttemptRepository;
import com.exam.repository.UserRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Numbers for the Super Admin's Overview page: headline counts and, for the last ten weeks
 * (Monday to Sunday, the current week last), how many quiz submissions, class check-ins and
 * successful fee payments there were. SUPER_ADMIN only via the /api/v1/super-admin/** rule.
 */
@RestController
@RequestMapping("/api/v1/super-admin")
public class SuperAdminOverviewController {

    static final int WEEKS = 10;

    private final DepartmentRepository departments;
    private final ProgramRepository programs;
    private final UserRepository users;
    private final CategoryRepository courses;
    private final QuizAttemptRepository attempts;
    private final AttendanceRecordRepository attendance;
    private final FeePaymentRepository payments;

    public SuperAdminOverviewController(DepartmentRepository departments, ProgramRepository programs, UserRepository users,
                                        CategoryRepository courses, QuizAttemptRepository attempts,
                                        AttendanceRecordRepository attendance, FeePaymentRepository payments) {
        this.departments = departments;
        this.programs = programs;
        this.users = users;
        this.courses = courses;
        this.attempts = attempts;
        this.attendance = attendance;
        this.payments = payments;
    }

    @GetMapping("/overview")
    @Transactional(readOnly = true)
    public Map<String, Object> overview() {
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("departments", departments.count());
        counts.put("programs", programs.count());
        counts.put("hods", users.countByRole(Role.ADMIN));
        counts.put("lecturers", users.countByRole(Role.LECTURER));
        counts.put("students", users.countByRole(Role.NORMAL));
        counts.put("courses", courses.count());

        LocalDate thisMonday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        List<Map<String, Object>> weeks = new ArrayList<>();
        for (int i = WEEKS - 1; i >= 0; i--) {
            LocalDate start = thisMonday.minusWeeks(i);
            LocalDateTime from = start.atStartOfDay();
            LocalDateTime to = start.plusWeeks(1).atStartOfDay().minusNanos(1000);
            Map<String, Object> w = new LinkedHashMap<>();
            w.put("weekStart", start.toString());
            w.put("submissions", attempts.countBySubmittedAtBetween(from, to));
            w.put("checkIns", attendance.countByFirstCheckAtBetween(from, to));
            w.put("payments", payments.countByStatusAndPaidAtBetween(FeePayment.Status.SUCCESS, from, to));
            weeks.add(w);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("counts", counts);
        out.put("weeks", weeks);
        return out;
    }
}
