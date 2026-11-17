package com.exam.service.materials;

import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.academic.AcademicSession;
import com.exam.model.materials.CourseSpace;
import com.exam.model.materials.TermCalendar;
import com.exam.repository.materials.TermCalendarRepository;
import com.exam.service.academic.AcademicSessionService;
import com.exam.service.comms.CurrentUserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/**
 * Term dates (Super Admin, Academic Settings) and the week calendar of a course's materials:
 * week n runs Monday–Sunday, counted from the Monday of week 1's first day.
 */
@Service
public class MaterialCalendar {

    public static final int DEFAULT_WEEKS = 14;
    public static final int MAX_WEEKS = 30;

    @Autowired private TermCalendarRepository terms;
    @Autowired private AcademicSessionService academicSessions;

    // ── Term dates ──────────────────────────────────────────────────────────

    /** The current session's terms 1–3 (startDate null = not set). */
    @Transactional
    public Map<String, Object> termDates() {
        AcademicSession s = academicSessions.current();
        Map<Integer, TermCalendar> byTerm = new HashMap<>();
        terms.findByAcademicSession_IdOrderBySemester(s.getId()).forEach(t -> byTerm.put(t.getSemester(), t));
        List<Map<String, Object>> list = new ArrayList<>();
        for (int n = 1; n <= 3; n++) {
            TermCalendar t = byTerm.get(n);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("semester", n);
            m.put("startDate", t == null ? null : t.getStartDate());
            m.put("weeks", t == null ? DEFAULT_WEEKS : t.getWeeks());
            m.put("endDate", t == null ? null : mondayOf(t.getStartDate()).plusWeeks(t.getWeeks()).minusDays(1));
            m.put("updatedBy", t == null ? null : t.getUpdatedBy());
            list.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", s.getId());
        out.put("sessionName", s.getName());
        out.put("terms", list);
        return out;
    }

    /** startDate null clears the term's dates. */
    @Transactional
    public Map<String, Object> saveTerm(User actor, int semester, LocalDate startDate, Integer weeks) {
        if (actor == null || actor.getRole() != Role.SUPER_ADMIN) throw new AccessDeniedException("Only the Super Admin sets term dates.");
        if (semester < 1 || semester > 3) throw new IllegalArgumentException("Choose semester 1, 2 or 3.");
        AcademicSession s = academicSessions.current();
        Optional<TermCalendar> existing = terms.findByAcademicSession_IdAndSemester(s.getId(), semester);
        if (startDate == null) {
            existing.ifPresent(terms::delete);
            return termDates();
        }
        int w = weeks == null ? DEFAULT_WEEKS : weeks;
        if (w < 1 || w > MAX_WEEKS) throw new IllegalArgumentException("A term has between 1 and " + MAX_WEEKS + " weeks.");
        TermCalendar t = existing.orElseGet(TermCalendar::new);
        t.setAcademicSession(s);
        t.setSemester(semester);
        t.setStartDate(startDate);
        t.setWeeks(w);
        t.setUpdatedBy(CurrentUserService.displayName(actor));
        t.setUpdatedAt(LocalDateTime.now());
        terms.save(t);
        return termDates();
    }

    // ── A course's weeks ────────────────────────────────────────────────────

    /** Week 1's Monday for this course, or null when neither the course nor the term has dates. */
    public LocalDate weekOneMonday(CourseSpace space) {
        if (space.getWeekOneStart() != null) return mondayOf(space.getWeekOneStart());
        TermCalendar t = termFor(space);
        return t == null ? null : mondayOf(t.getStartDate());
    }

    public int weeksCount(CourseSpace space) {
        if (space.getWeeksCount() != null) return clampWeeks(space.getWeeksCount());
        TermCalendar t = termFor(space);
        return t == null ? DEFAULT_WEEKS : clampWeeks(t.getWeeks());
    }

    public TermCalendar termFor(CourseSpace space) {
        Integer sem = space.getCourse() == null ? null : space.getCourse().getSemester();
        if (sem == null || space.getAcademicSession() == null) return null;
        return terms.findByAcademicSession_IdAndSemester(space.getAcademicSession().getId(), sem).orElse(null);
    }

    /** The week running today (0 when the term hasn't started or there are no dates). */
    public int currentWeek(CourseSpace space, LocalDate today) {
        LocalDate one = weekOneMonday(space);
        if (one == null || today.isBefore(one)) return 0;
        return (int) (ChronoUnit.DAYS.between(one, today) / 7) + 1;
    }

    public static LocalDate weekStart(LocalDate weekOneMonday, int week) {
        return weekOneMonday == null || week < 1 ? null : weekOneMonday.plusWeeks(week - 1L);
    }

    public static LocalDate mondayOf(LocalDate d) {
        return d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    private static int clampWeeks(int n) {
        return Math.max(1, Math.min(MAX_WEEKS, n));
    }
}
