package com.exam.controller;

import com.exam.exception.ErrorMessage;
import com.exam.model.Role;
import com.exam.model.User;
import com.exam.repository.UserRepository;
import com.exam.service.PdfReportService;
import com.exam.service.SystemSettingService;
import com.exam.service.academic.AcademicRecordService;
import com.exam.service.academic.AcademicSessionService;
import com.exam.service.academic.GradingService;
import com.exam.service.comms.CurrentUserService;
import com.exam.service.comms.NotificationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Academic core: sessions, grading scale, transcripts (GPA/CGPA), carry-overs and promotion
 * eligibility. Settings are Super Admin only; records are visible to the student (published
 * results only), their HOD and the Super Admin.
 */
@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/api/academic")
public class AcademicController {

    @Autowired private CurrentUserService currentUserService;
    @Autowired private AcademicSessionService sessionService;
    @Autowired private GradingService gradingService;
    @Autowired private AcademicRecordService recordService;
    @Autowired private PdfReportService pdfReportService;
    @Autowired private SystemSettingService systemSettingService;
    @Autowired private UserRepository userRepository;

    // ── Sessions ─────────────────────────────────────────────────────────────

    public static class SessionRequest {
        public String name;
        public LocalDate startDate;
        public LocalDate endDate;
        public Boolean makeCurrent;
    }

    @GetMapping("/sessions")
    public ResponseEntity<?> sessions() {
        return withUser(u -> { sessionService.current(); return ResponseEntity.ok(sessionService.list()); });
    }

    @PostMapping("/sessions")
    public ResponseEntity<?> createSession(@RequestBody SessionRequest req) {
        return withSuperAdmin(u -> ResponseEntity.ok(
                sessionService.create(req.name, req.startDate, req.endDate, Boolean.TRUE.equals(req.makeCurrent))));
    }

    @PutMapping("/sessions/{id}")
    public ResponseEntity<?> updateSession(@PathVariable Long id, @RequestBody SessionRequest req) {
        return withSuperAdmin(u -> ResponseEntity.ok(sessionService.update(id, req.name, req.startDate, req.endDate)));
    }

    @PostMapping("/sessions/{id}/current")
    public ResponseEntity<?> makeCurrent(@PathVariable Long id) {
        return withSuperAdmin(u -> ResponseEntity.ok(sessionService.setCurrent(id)));
    }

    @DeleteMapping("/sessions/{id}")
    public ResponseEntity<?> deleteSession(@PathVariable Long id) {
        return withSuperAdmin(u -> { sessionService.delete(id); return ResponseEntity.ok(Map.of("message", "Deleted.")); });
    }

    // ── Grading scale & rules ────────────────────────────────────────────────

    public static class GradingRequest {
        public List<GradingService.BandRow> bands;
        public List<GradingService.ClassRow> classes;
        public Integer defaultCreditUnits;
        public Integer maxCarryoversForPromotion;
        public BigDecimal minCgpaForPromotion;
    }

    @GetMapping("/grading")
    public ResponseEntity<?> grading() {
        return withUser(u -> ResponseEntity.ok(gradingService.settings()));
    }

    @GetMapping("/grading/preset/{key}")
    public ResponseEntity<?> preset(@PathVariable String key) {
        return withSuperAdmin(u -> ResponseEntity.ok(gradingService.preset(key)));
    }

    @PutMapping("/grading")
    public ResponseEntity<?> updateGrading(@RequestBody GradingRequest req) {
        return withSuperAdmin(u -> ResponseEntity.ok(gradingService.update(req.bands, req.classes,
                req.defaultCreditUnits, req.maxCarryoversForPromotion, req.minCgpaForPromotion)));
    }

    /** Re-grades unpublished marks with the current scale. Published grades are kept. */
    @PostMapping("/grading/recalculate")
    public ResponseEntity<?> recalculate() {
        return withSuperAdmin(u -> ResponseEntity.ok(Map.of("updated", gradingService.recalculate())));
    }

    // ── Transcripts ──────────────────────────────────────────────────────────

    @GetMapping("/me/transcript")
    public ResponseEntity<?> myTranscript() {
        return withUser(u -> {
            requireStudentResultsVisible(u);
            return ResponseEntity.ok(recordService.transcript(u.getId(), false));
        });
    }

    @GetMapping("/me/transcript/pdf")
    public ResponseEntity<?> myTranscriptPdf() {
        return withUser(u -> {
            requireStudentResultsVisible(u);
            return pdf(recordService.transcript(u.getId(), false), u.getUsername());
        });
    }

    @GetMapping("/students/{id}/transcript")
    public ResponseEntity<?> studentTranscript(@PathVariable Long id) {
        return withUser(u -> ResponseEntity.ok(recordService.transcript(requireRecordAccess(u, id).getId(), true)));
    }

    @GetMapping("/students/{id}/transcript/pdf")
    public ResponseEntity<?> studentTranscriptPdf(@PathVariable Long id) {
        return withUser(u -> {
            User s = requireRecordAccess(u, id);
            return pdf(recordService.transcript(s.getId(), true), s.getUsername());
        });
    }

    @GetMapping("/students/{id}/eligibility")
    public ResponseEntity<?> eligibility(@PathVariable Long id) {
        return withUser(u -> ResponseEntity.ok(recordService.eligibility(requireRecordAccess(u, id))));
    }

    /** Promotion preview for everyone at one program + level. */
    @GetMapping("/promotion-preview")
    public ResponseEntity<?> promotionPreview(@RequestParam Long programId, @RequestParam Integer level) {
        return withUser(u -> {
            if (u.getRole() != Role.SUPER_ADMIN && u.getRole() != Role.ADMIN) throw new AccessDeniedException("Staff only.");
            List<Map<String, Object>> rows = userRepository.findByRole(Role.NORMAL).stream()
                    .filter(s -> s.getProgram() != null && s.getProgram().getId().equals(programId))
                    .filter(s -> level.equals(s.getCurrentLevel()))
                    .filter(s -> u.getRole() == Role.SUPER_ADMIN || NotificationService.inDepartment(s, u.getDepartment()))
                    .map(recordService::eligibility)
                    .toList();
            return ResponseEntity.ok(Map.of(
                    "students", rows,
                    "eligible", rows.stream().filter(r -> Boolean.TRUE.equals(r.get("eligible"))).count(),
                    "maxCarryovers", gradingService.maxCarryoversForPromotion(),
                    "minCgpa", gradingService.minCgpaForPromotion()));
        });
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private ResponseEntity<byte[]> pdf(Map<String, Object> transcript, String candidateId) {
        try {
            byte[] bytes = pdfReportService.generateTranscriptPdf(transcript, candidateId);
            String file = "transcript-" + Objects.toString(transcript.get("username"), "student").replaceAll("[^A-Za-z0-9_-]", "_") + ".pdf";
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file + "\"")
                    .contentType(MediaType.APPLICATION_PDF)
                    .body(bytes);
        } catch (Exception e) {
            throw new IllegalStateException("Could not generate the transcript PDF: " + e.getMessage());
        }
    }

    /** Super Admin: any student. HOD: students in their department. Nobody else. */
    private User requireRecordAccess(User staff, Long studentId) {
        User s = userRepository.findById(studentId).orElseThrow(() -> new IllegalArgumentException("Student not found."));
        boolean ok = staff.getRole() == Role.SUPER_ADMIN
                || (staff.getRole() == Role.ADMIN && NotificationService.inDepartment(s, staff.getDepartment()));
        if (!ok) throw new AccessDeniedException("You can't view this student's record.");
        return s;
    }

    /** Students see results only while the Super Admin shows them report cards. */
    private void requireStudentResultsVisible(User u) {
        if (u.getRole() != Role.NORMAL) throw new AccessDeniedException("Only students have a personal transcript.");
        if (!systemSettingService.getBooleanSetting(SystemSettingService.MARKS_SHEET_VISIBLE_STUDENT, true))
            throw new AccessDeniedException("Results are not available to students at the moment.");
    }

    private interface UserAction { ResponseEntity<?> apply(User u); }

    private ResponseEntity<?> withSuperAdmin(UserAction action) {
        return withUser(u -> {
            if (u.getRole() != Role.SUPER_ADMIN) throw new AccessDeniedException("Only the Super Admin can change academic settings.");
            return action.apply(u);
        });
    }

    private ResponseEntity<?> withUser(UserAction action) {
        Optional<User> user = currentUserService.current();
        if (user.isEmpty()) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorMessage("Please sign in."));
        try {
            return action.apply(user.get());
        } catch (AccessDeniedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ErrorMessage(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ErrorMessage(e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ErrorMessage(e.getMessage()));
        }
    }
}
