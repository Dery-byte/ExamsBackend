package com.exam.service.academic;

import com.exam.model.academic.AcademicSession;
import com.exam.model.exam.Registered_courses;
import com.exam.model.exam.SemesterSheet;
import com.exam.repository.AcademicSessionRepository;
import com.exam.repository.Registered_coursesRepository;
import com.exam.repository.SemesterSheetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;

/**
 * Academic sessions (years). Marks sheets and course enrolments are stamped with the current
 * session when created, so results from different years never merge.
 */
@Service
public class AcademicSessionService {

    private static final Logger log = LoggerFactory.getLogger(AcademicSessionService.class);

    @Autowired private AcademicSessionRepository sessionRepository;
    @Autowired private SemesterSheetRepository semesterSheetRepository;
    @Autowired private Registered_coursesRepository registeredCoursesRepository;

    /** The current session, creating one for the present academic year if none exists yet. */
    @Transactional
    public AcademicSession current() {
        return sessionRepository.findFirstByCurrentTrue().orElseGet(() -> {
            List<AcademicSession> all = sessionRepository.findAllByOrderByStartDateDescIdDesc();
            if (!all.isEmpty()) {                       // sessions exist but none flagged: use the latest
                AcademicSession latest = all.get(0);
                latest.setCurrent(true);
                return sessionRepository.save(latest);
            }
            LocalDate today = LocalDate.now();
            int startYear = today.getMonthValue() >= 8 ? today.getYear() : today.getYear() - 1;
            AcademicSession s = new AcademicSession();
            s.setName(startYear + "/" + (startYear + 1));
            s.setStartDate(LocalDate.of(startYear, 8, 1));
            s.setEndDate(LocalDate.of(startYear + 1, 7, 31));
            s.setCurrent(true);
            log.info("[AcademicSession] Created default current session {}", s.getName());
            return sessionRepository.save(s);
        });
    }

    public List<Map<String, Object>> list() {
        return sessionRepository.findAllByOrderByStartDateDescIdDesc().stream().map(this::toDto).toList();
    }

    @Transactional
    public Map<String, Object> create(String name, LocalDate start, LocalDate end, boolean makeCurrent) {
        String clean = validName(name);
        if (sessionRepository.existsByNameIgnoreCase(clean)) throw new IllegalArgumentException("A session named " + clean + " already exists.");
        validDates(start, end);
        AcademicSession s = new AcademicSession();
        s.setName(clean);
        s.setStartDate(start);
        s.setEndDate(end);
        AcademicSession saved = sessionRepository.save(s);
        if (makeCurrent) setCurrent(saved.getId());
        return toDto(sessionRepository.findById(saved.getId()).orElse(saved));
    }

    @Transactional
    public Map<String, Object> update(Long id, String name, LocalDate start, LocalDate end) {
        AcademicSession s = get(id);
        String clean = validName(name);
        if (!clean.equalsIgnoreCase(s.getName()) && sessionRepository.existsByNameIgnoreCase(clean))
            throw new IllegalArgumentException("A session named " + clean + " already exists.");
        validDates(start, end);
        s.setName(clean);
        s.setStartDate(start);
        s.setEndDate(end);
        return toDto(sessionRepository.save(s));
    }

    /** Makes this the current session. New marks sheets and enrolments go into it from now on. */
    @Transactional
    public Map<String, Object> setCurrent(Long id) {
        AcademicSession target = get(id);
        for (AcademicSession s : sessionRepository.findAll()) {
            boolean should = s.getId().equals(target.getId());
            if (s.isCurrent() != should) { s.setCurrent(should); sessionRepository.save(s); }
        }
        return toDto(target);
    }

    @Transactional
    public void delete(Long id) {
        AcademicSession s = get(id);
        if (s.isCurrent()) throw new IllegalArgumentException("You can't delete the current session.");
        if (semesterSheetRepository.countBySession_Id(id) > 0 || registeredCoursesRepository.countBySession_Id(id) > 0)
            throw new IllegalArgumentException("This session already has marks sheets or enrolments, so it can't be deleted.");
        sessionRepository.delete(s);
    }

    /** One-time migration: records created before sessions existed are placed in the current session. */
    @Transactional
    public void backfill() {
        List<SemesterSheet> sheets = semesterSheetRepository.findBySessionIsNull();
        List<Registered_courses> regs = registeredCoursesRepository.findBySessionIsNull();
        if (sheets.isEmpty() && regs.isEmpty()) return;
        AcademicSession cur = current();
        sheets.forEach(s -> s.setSession(cur));
        regs.forEach(r -> r.setSession(cur));
        semesterSheetRepository.saveAll(sheets);
        registeredCoursesRepository.saveAll(regs);
        log.info("[AcademicSession] Placed {} marks sheets and {} enrolments into session {}", sheets.size(), regs.size(), cur.getName());
    }

    public Map<String, Object> toDto(AcademicSession s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("name", s.getName());
        m.put("startDate", s.getStartDate());
        m.put("endDate", s.getEndDate());
        m.put("current", s.isCurrent());
        m.put("sheetCount", semesterSheetRepository.countBySession_Id(s.getId()));
        return m;
    }

    private AcademicSession get(Long id) {
        return sessionRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Session not found."));
    }

    private static String validName(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Session name is required, e.g. 2025/2026.");
        String clean = name.trim();
        if (clean.length() > 40) throw new IllegalArgumentException("Session name is too long.");
        return clean;
    }

    private static void validDates(LocalDate start, LocalDate end) {
        if (start != null && end != null && end.isBefore(start)) throw new IllegalArgumentException("The end date is before the start date.");
    }
}
