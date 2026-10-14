package com.exam.service.attendance;

import com.exam.model.User;
import com.exam.model.attendance.AttendanceMode;
import com.exam.model.attendance.ClassSchedule;
import com.exam.model.attendance.Classroom;
import com.exam.model.exam.Category;
import com.exam.model.exam.Department;
import com.exam.model.exam.Program;
import com.exam.repository.CategoryRepository;
import com.exam.repository.ClassScheduleRepository;
import com.exam.repository.ClassroomRepository;
import com.exam.repository.ProgramRepository;
import com.exam.service.comms.CurrentUserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Weekly class times. Whoever may start a class's attendance may also set its times:
 * course lecturers (and their HOD) for courses, the HOD and Super Admin for programmes.
 */
@Service
public class ClassScheduleService {

    @Autowired private ClassScheduleRepository schedules;
    @Autowired private ClassroomRepository classrooms;
    @Autowired private CategoryRepository categories;
    @Autowired private ProgramRepository programs;
    @Autowired private AttendancePolicyService policyService;

    public record ScheduleRequest(String scope, Long courseId, Long programId, Integer level, String dayOfWeek,
                                  String startTime, String endTime, Long classroomId) {}

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(User actor) {
        if (!AttendanceAccess.isStaff(actor)) throw new AccessDeniedException("Staff only.");
        return schedules.findAllByOrderByDayOfWeekAscStartTimeAsc().stream()
                .filter(s -> AttendanceService.canManageSchedule(actor, s))
                .map(ClassScheduleService::dto).toList();
    }

    @Transactional
    public Map<String, Object> create(User actor, ScheduleRequest req) {
        ClassSchedule s = new ClassSchedule();
        s.setCreatedById(actor.getId());
        s.setCreatedByName(CurrentUserService.displayName(actor));
        apply(s, req, actor);
        return dto(schedules.save(s));
    }

    @Transactional
    public Map<String, Object> update(User actor, Long id, ScheduleRequest req) {
        ClassSchedule s = editable(actor, id);
        apply(s, req, actor);
        return dto(schedules.save(s));
    }

    @Transactional
    public void delete(User actor, Long id) {
        schedules.delete(editable(actor, id));
    }

    private ClassSchedule editable(User actor, Long id) {
        if (!AttendanceAccess.isStaff(actor)) throw new AccessDeniedException("Staff only.");
        ClassSchedule s = schedules.findById(id).orElseThrow(() -> new IllegalArgumentException("Class time not found."));
        if (!AttendanceService.canManageSchedule(actor, s)) throw new AccessDeniedException("You don't manage this class.");
        return s;
    }

    private void apply(ClassSchedule s, ScheduleRequest r, User actor) {
        if (!AttendanceAccess.isStaff(actor)) throw new AccessDeniedException("Staff only.");
        if (r == null) throw new IllegalArgumentException("Nothing to save.");
        AttendanceMode scope;
        try { scope = AttendanceMode.valueOf(String.valueOf(r.scope()).trim().toUpperCase()); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Choose a course or a programme."); }

        Department dept;
        if (scope == AttendanceMode.COURSE) {
            if (r.courseId() == null) throw new IllegalArgumentException("Choose the course.");
            Category c = categories.findById(r.courseId()).orElseThrow(() -> new IllegalArgumentException("Course not found."));
            if (!AttendanceAccess.canOpenCourse(actor, c)) throw new AccessDeniedException("You don't teach this course.");
            s.setCourse(c);
            s.setProgram(null);
            s.setLevel(null);
            dept = AttendanceAccess.departmentOf(c);
        } else {
            if (r.programId() == null) throw new IllegalArgumentException("Choose the programme.");
            Program p = programs.findById(r.programId()).orElseThrow(() -> new IllegalArgumentException("Programme not found."));
            if (!AttendanceAccess.canOpenProgramme(actor, p)) throw new AccessDeniedException("Only the HOD or the Super Admin sets programme class times.");
            if (r.level() != null && !p.getConfiguredLevels().contains(r.level()))
                throw new IllegalArgumentException("Level " + r.level() + " is not part of " + p.getName() + ".");
            s.setProgram(p);
            s.setLevel(r.level());
            s.setCourse(null);
            dept = p.getDepartment();
        }
        if (policyService.modeFor(dept) != scope) {
            throw new IllegalArgumentException(scope == AttendanceMode.COURSE
                    ? "This department takes attendance by programme, so class times are set per programme."
                    : "This department takes attendance per course, so class times are set per course.");
        }
        s.setScope(scope);

        try { s.setDayOfWeek(DayOfWeek.valueOf(String.valueOf(r.dayOfWeek()).trim().toUpperCase())); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Choose the day."); }
        LocalTime start = time(r.startTime(), "start");
        LocalTime end = time(r.endTime(), "end");
        if (!end.isAfter(start.plusMinutes(14))) throw new IllegalArgumentException("The class must end at least 15 minutes after it starts.");
        s.setStartTime(start);
        s.setEndTime(end);

        if (r.classroomId() != null) {
            Classroom room = classrooms.findById(r.classroomId()).orElseThrow(() -> new IllegalArgumentException("Classroom not found."));
            s.setClassroom(room);
        } else {
            s.setClassroom(null);
        }
    }

    private static LocalTime time(String s, String what) {
        try { return LocalTime.parse(String.valueOf(s).trim()); }
        catch (DateTimeParseException e) { throw new IllegalArgumentException("Enter the " + what + " time."); }
    }

    static Map<String, Object> dto(ClassSchedule s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("scope", s.getScope().name());
        m.put("courseId", s.getCourse() != null ? s.getCourse().getCid() : null);
        m.put("courseCode", s.getCourse() != null ? s.getCourse().getCourseCode() : null);
        m.put("programId", s.getProgram() != null ? s.getProgram().getId() : null);
        m.put("level", s.getLevel());
        String title = s.getCourse() != null
                ? ((s.getCourse().getCourseCode() != null ? s.getCourse().getCourseCode() + " " : "") + (s.getCourse().getTitle() != null ? s.getCourse().getTitle() : "")).trim()
                : s.getProgram() != null ? s.getProgram().getName() + (s.getLevel() != null ? " · Level " + s.getLevel() : "") : "Class";
        m.put("title", title);
        m.put("dayOfWeek", s.getDayOfWeek().name());
        m.put("startTime", s.getStartTime().toString());
        m.put("endTime", s.getEndTime().toString());
        m.put("classroomId", s.getClassroom() != null ? s.getClassroom().getId() : null);
        m.put("classroomName", s.getClassroom() != null ? s.getClassroom().getName() : null);
        return m;
    }
}
