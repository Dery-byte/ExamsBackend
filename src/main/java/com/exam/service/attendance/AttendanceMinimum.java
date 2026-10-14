package com.exam.service.attendance;

import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.attendance.AttendanceMode;
import com.exam.model.attendance.AttendancePolicy;
import com.exam.model.exam.Category;
import com.exam.model.exam.Department;
import com.exam.model.exam.Program;
import com.exam.model.exam.Quiz;
import com.exam.model.features.Feature;
import com.exam.repository.AttendanceRecordRepository;
import com.exam.service.academic.AcademicSessionService;
import com.exam.service.features.FeatureService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * The minimum attendance for quizzes, when the Super Admin or HOD has switched it on:
 * <ul>
 *   <li>course mode (the course's department): below the minimum for a course, no quiz of that course;</li>
 *   <li>programme mode (the student's programme's department): below the minimum for the programme, no quiz at all.</li>
 * </ul>
 * Only finished meetings of the current academic session count, and nothing blocks until at least
 * one meeting counts. Resuming an attempt already in progress is never blocked.
 */
@Service
public class AttendanceMinimum {

    @Autowired private AttendanceRecordRepository records;
    @Autowired private AttendancePolicyService policies;
    @Autowired private FeatureService features;
    @Autowired private AcademicSessionService academicSessions;

    /** A short title and the student's figures for one rule that applies. */
    public record Standing(String title, AttendanceTally tally, int minimum) {
        public boolean below() { return tally.below(minimum); }
    }

    /** Why this student may not start this quiz, or empty. */
    @Transactional(readOnly = true)
    public Optional<String> blockReason(User student, Quiz quiz) {
        if (student == null || student.getRole() != Role.NORMAL || quiz == null) return Optional.empty();
        Optional<Standing> course = forCourse(student, quiz.getCategory()).filter(Standing::below);
        if (course.isPresent()) {
            Standing s = course.get();
            return Optional.of(message(s, "to take quizzes for this course"));
        }
        Optional<Standing> programme = forProgramme(student).filter(Standing::below);
        return programme.map(s -> message(s, "to take quizzes"));
    }

    /** The course rule, when the course's department takes attendance per course with a minimum. */
    public Optional<Standing> forCourse(User student, Category course) {
        if (course == null) return Optional.empty();
        Department d = AttendanceAccess.departmentOf(course);
        AttendancePolicy p = applies(d, AttendanceMode.COURSE);
        if (p == null) return Optional.empty();
        AttendanceTally t = new AttendanceTally();
        records.courseStatuses(student.getId(), course.getCid(), academicSessions.current().getId()).forEach(t::add);
        String title = course.getCourseCode() != null && !course.getCourseCode().isBlank() ? course.getCourseCode() : course.getTitle();
        return Optional.of(new Standing(title, t, p.getMinimumPercent()));
    }

    /** The programme rule, when the student's programme's department takes attendance by programme with a minimum. */
    public Optional<Standing> forProgramme(User student) {
        Program prog = student.getProgram();
        if (prog == null) return Optional.empty();
        AttendancePolicy p = applies(prog.getDepartment(), AttendanceMode.PROGRAMME);
        if (p == null) return Optional.empty();
        AttendanceTally t = new AttendanceTally();
        records.programmeStatuses(student.getId(), prog.getId(), academicSessions.current().getId(), AttendanceMode.PROGRAMME).forEach(t::add);
        return Optional.of(new Standing(prog.getName(), t, p.getMinimumPercent()));
    }

    /** The minimum % that applies to this course's quizzes, or null when none does. */
    public Integer minimumForCourse(Category course) {
        AttendancePolicy p = course == null ? null : applies(AttendanceAccess.departmentOf(course), AttendanceMode.COURSE);
        return p == null ? null : p.getMinimumPercent();
    }

    /** The minimum % for a programme's students, or null when none applies. */
    public Integer minimumForProgramme(Program prog) {
        AttendancePolicy p = prog == null ? null : applies(prog.getDepartment(), AttendanceMode.PROGRAMME);
        return p == null ? null : p.getMinimumPercent();
    }

    /** The department's policy when attendance is on, in this mode, with the minimum switched on; else null. */
    private AttendancePolicy applies(Department d, AttendanceMode mode) {
        if (!features.isOnForDepartment(Feature.ATTENDANCE, d)) return null;
        AttendancePolicy p = policies.effective(d);
        return p.getMode() == mode && p.isMinimumRequired() ? p : null;
    }

    private static String message(Standing s, String what) {
        AttendanceTally t = s.tally();
        return "Your attendance for " + s.title() + " is " + format(t.percent()) + "% (" + t.attended() + " of " + t.counted()
                + " classes). At least " + s.minimum() + "% is needed " + what + ". If some absences should be excused, see your lecturer.";
    }

    private static String format(Double v) {
        if (v == null) return "0";
        return v == Math.floor(v) ? String.valueOf(v.intValue()) : String.valueOf(v);
    }
}
