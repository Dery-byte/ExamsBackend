package com.exam.service.academic;

import com.exam.DTO.SemesterSheetDTO;
import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.academic.TermReportRemark;
import com.exam.model.exam.SemesterSheet;
import com.exam.repository.TermReportRemarkRepository;
import com.exam.service.MarksEntryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Attendance, conduct and remarks on report cards.
 * The sheet's class teacher fills in their part; HODs (for their department) and the Super Admin
 * can fill in everything, including the head's remark.
 */
@Service
public class TermRemarkService {

    @Autowired private TermReportRemarkRepository repository;
    @Autowired private MarksEntryService marksEntryService;

    public record Row(Long studentId, Integer daysPresent, Integer daysOpen, String conduct, String interest,
                      String classTeacherRemark, String headRemark) {}

    public Map<String, Object> view(Long sheetId, User user) {
        SemesterSheet sheet = sheet(sheetId);
        boolean head = canEditHead(sheet, user);
        if (!head && !isClassTeacher(sheet, user))
            throw new AccessDeniedException("Only this sheet's class teacher or the HOD can enter report remarks.");

        Map<Long, TermReportRemark> saved = new HashMap<>();
        repository.findBySheetId(sheetId).forEach(r -> saved.put(r.getStudentId(), r));

        List<Map<String, Object>> rows = new ArrayList<>();
        SemesterSheetDTO dto = marksEntryService.getSheetData(sheetId);
        if (dto != null && dto.getStudentMarks() != null) {
            for (SemesterSheetDTO.StudentMarkDTO sm : dto.getStudentMarks()) {
                TermReportRemark r = saved.get(sm.getStudentId());
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("studentId", sm.getStudentId());
                row.put("studentName", sm.getStudentName());
                row.put("username", sm.getUsername());
                row.put("daysPresent", r == null ? null : r.getDaysPresent());
                row.put("daysOpen", r == null ? null : r.getDaysOpen());
                row.put("conduct", r == null ? null : r.getConduct());
                row.put("interest", r == null ? null : r.getInterest());
                row.put("classTeacherRemark", r == null ? null : r.getClassTeacherRemark());
                row.put("headRemark", r == null ? null : r.getHeadRemark());
                rows.add(row);
            }
        }
        rows.sort(Comparator.comparing(m -> Objects.toString(m.get("studentName"), "")));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sheetId", sheetId);
        out.put("programName", sheet.getProgram() != null ? sheet.getProgram().getName() : null);
        out.put("level", sheet.getLevel());
        out.put("semester", sheet.getSemester());
        out.put("sessionName", sheet.getSession() != null ? sheet.getSession().getName() : null);
        out.put("canEditHeadRemark", head);
        out.put("rows", rows);
        return out;
    }

    @Transactional
    public int save(Long sheetId, User user, List<Row> rows) {
        SemesterSheet sheet = sheet(sheetId);
        boolean head = canEditHead(sheet, user);
        if (!head && !isClassTeacher(sheet, user))
            throw new AccessDeniedException("Only this sheet's class teacher or the HOD can enter report remarks.");

        Set<Long> enrolled = new HashSet<>();
        SemesterSheetDTO dto = marksEntryService.getSheetData(sheetId);
        if (dto != null && dto.getStudentMarks() != null)
            dto.getStudentMarks().forEach(sm -> enrolled.add(sm.getStudentId()));

        int saved = 0;
        for (Row in : rows == null ? List.<Row>of() : rows) {
            if (in == null || in.studentId() == null || !enrolled.contains(in.studentId())) continue;
            validateDays(in.daysPresent(), in.daysOpen());
            TermReportRemark r = repository.findBySheetIdAndStudentId(sheetId, in.studentId()).orElseGet(TermReportRemark::new);
            r.setSheetId(sheetId);
            r.setStudentId(in.studentId());
            r.setDaysPresent(in.daysPresent());
            r.setDaysOpen(in.daysOpen());
            r.setConduct(clean(in.conduct(), 100));
            r.setInterest(clean(in.interest(), 100));
            r.setClassTeacherRemark(clean(in.classTeacherRemark(), 500));
            if (head) r.setHeadRemark(clean(in.headRemark(), 500));   // teachers can't change the head's remark
            r.setUpdatedBy(user.getUsername());
            r.setUpdatedAt(LocalDateTime.now());
            repository.save(r);
            saved++;
        }
        return saved;
    }

    /** For the report card PDF: the student's remarks on any of the term's sheets (most recently edited first). */
    public Optional<TermReportRemark> forStudent(List<Long> sheetIds, Long studentId) {
        return sheetIds.stream()
                .map(id -> repository.findBySheetIdAndStudentId(id, studentId))
                .flatMap(Optional::stream)
                .max(Comparator.comparing(r -> r.getUpdatedAt() == null ? LocalDateTime.MIN : r.getUpdatedAt()));
    }

    private SemesterSheet sheet(Long sheetId) {
        SemesterSheet sheet = marksEntryService.getSheetById(sheetId);
        if (sheet == null) throw new IllegalArgumentException("Sheet not found.");
        return sheet;
    }

    private static boolean isClassTeacher(SemesterSheet sheet, User user) {
        return sheet.getClassTeacher() != null && Objects.equals(sheet.getClassTeacher().getId(), user.getId());
    }

    private static boolean canEditHead(SemesterSheet sheet, User user) {
        if (user.getRole() == Role.SUPER_ADMIN) return true;
        if (user.getRole() != Role.ADMIN) return false;
        var dept = sheet.getProgram() != null ? sheet.getProgram().getDepartment() : null;
        return dept == null || user.getDepartment() == null || Objects.equals(dept.getId(), user.getDepartment().getId());
    }

    private static void validateDays(Integer present, Integer open) {
        if (present != null && present < 0 || open != null && open < 0)
            throw new IllegalArgumentException("Attendance can't be negative.");
        if (present != null && open != null && present > open)
            throw new IllegalArgumentException("Days present can't be more than the days school opened.");
    }

    private static String clean(String s, int max) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
