package com.exam.service.attendance;

import com.exam.model.attendance.AttendanceMode;
import com.exam.model.attendance.AttendanceSession;
import com.exam.model.attendance.AttendanceStatus;
import com.exam.repository.AttendanceRecordRepository;
import com.exam.repository.AttendanceSessionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;

/**
 * Days present / days opened for report cards, worked out from class attendance.
 * <p>
 * A report card counts days, and a class can meet several times in a day (one meeting per subject),
 * so for each student: days opened = the dates on which attendance was taken for them; days present =
 * the dates on which they were present or late in at least one of those meetings. Excused, absent,
 * not accepted and still-waiting check-ins don't count as present. Only finished meetings count.
 * <p>
 * A term's meetings: its subjects' meetings, and the class's own (programme) meetings for that
 * programme, level (or every level) and term, in the academic session.
 */
@Service
public class AttendanceDays {

    @Autowired private AttendanceSessionRepository sessions;
    @Autowired private AttendanceRecordRepository records;

    public record Days(int present, int opened) {}

    /** byStudent: each student's days; classDays: dates attendance was taken for this class; meetings: how many. */
    public record TermDays(Map<Long, Days> byStudent, int classDays, int meetings) {}

    /**
     * @param courseIds the term's subjects (a sheet's courses); when empty, subjects of that programme,
     *                  level and term count instead
     */
    @Transactional(readOnly = true)
    public TermDays forTerm(Long academicSessionId, Long programId, Integer level, Integer term, Collection<Long> courseIds) {
        Set<Long> subjects = courseIds == null ? Set.of() : new HashSet<>(courseIds);
        Map<Long, LocalDate> dateOf = new HashMap<>();
        for (AttendanceSession s : sessions.findByAcademicSession_IdAndClosedAtIsNotNull(academicSessionId)) {
            if (belongs(s, programId, level, term, subjects)) dateOf.put(s.getId(), s.getOpenedAt().toLocalDate());
        }

        Map<Long, Set<LocalDate>> opened = new HashMap<>(), present = new HashMap<>();
        List<Long> ids = new ArrayList<>(dateOf.keySet());
        for (int i = 0; i < ids.size(); i += 900) {
            for (Object[] row : records.reportRows(ids.subList(i, Math.min(ids.size(), i + 900)))) {
                LocalDate day = dateOf.get((Long) row[0]);
                Long student = (Long) row[1];
                AttendanceStatus status = (AttendanceStatus) row[2];
                opened.computeIfAbsent(student, k -> new HashSet<>()).add(day);
                if (status != null && status.attended()) present.computeIfAbsent(student, k -> new HashSet<>()).add(day);
            }
        }
        Map<Long, Days> out = new HashMap<>();
        opened.forEach((student, days) -> out.put(student, new Days(present.getOrDefault(student, Set.of()).size(), days.size())));
        return new TermDays(out, new HashSet<>(dateOf.values()).size(), dateOf.size());
    }

    private static boolean belongs(AttendanceSession s, Long programId, Integer level, Integer term, Set<Long> subjects) {
        if (s.getScope() == AttendanceMode.PROGRAMME) {
            return s.getProgram() != null && s.getProgram().getId().equals(programId)
                    && (s.getLevel() == null || level == null || s.getLevel().equals(level))
                    && Objects.equals(s.getSemester(), term);
        }
        if (s.getCourse() == null) return false;
        if (!subjects.isEmpty()) return subjects.contains(s.getCourse().getCid());
        var c = s.getCourse();
        boolean inProgram = c.getPrograms() != null && c.getPrograms().stream().anyMatch(p -> p.getId().equals(programId));
        Integer courseLevel = levelNumber(c.getLevel());
        return inProgram && (level == null || courseLevel == null || courseLevel.equals(level)) && Objects.equals(c.getSemester(), term);
    }

    /** "200", "Level 200", "Form 2" → the number; null when there is none. */
    public static Integer levelNumber(String level) {
        if (level == null) return null;
        String digits = level.replaceAll("\\D", "");
        if (digits.isEmpty()) return null;
        try { return Integer.valueOf(digits); } catch (NumberFormatException e) { return null; }
    }
}
