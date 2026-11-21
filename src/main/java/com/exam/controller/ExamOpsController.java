package com.exam.controller;

import com.exam.exception.ErrorMessage;
import com.exam.model.Role;
import com.exam.model.User;
import com.exam.service.QuizTimerService;
import com.exam.service.comms.CurrentUserService;
import com.exam.service.examops.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

/**
 * Exam operations: timetable, question bank, proctoring reports and re-mark requests.
 * All paths are under /api (not the public /api/v1/auth), so a signed-in user is required;
 * role and ownership checks live in the services.
 */
@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/api")
public class ExamOpsController {

    @Autowired private CurrentUserService currentUserService;
    @Autowired private TimetableService timetableService;
    @Autowired private QuestionBankService questionBankService;
    @Autowired private ProctoringService proctoringService;
    @Autowired private RemarkService remarkService;
    @Autowired private com.exam.service.features.FeatureService featureService;
    @Autowired private QuizTimerService quizTimerService;

    // ── Timetable ────────────────────────────────────────────────────────────

    @GetMapping("/timetable")
    public ResponseEntity<?> timetable(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                       @RequestParam(required = false) Long departmentId,
                                       @RequestParam(required = false) Long programId,
                                       @RequestParam(required = false) String level) {
        return withUser(u -> {
            if (u.getRole() == com.exam.model.Role.NORMAL) featureService.require(com.exam.model.features.Feature.STUDENT_TIMETABLE, u);
            return ResponseEntity.ok(timetableService.timetable(u, from, to, departmentId, programId, level));
        });
    }

    // ── Question bank ────────────────────────────────────────────────────────

    @GetMapping("/question-bank/courses")
    public ResponseEntity<?> bankCourses() {
        return withUser(u -> { featureService.require(com.exam.model.features.Feature.QUESTION_BANK, u); return ResponseEntity.ok(questionBankService.myCourses(u)); });
    }

    @GetMapping("/question-bank/course/{courseId}")
    public ResponseEntity<?> bankForCourse(@PathVariable Long courseId) {
        return withUser(u -> { featureService.require(com.exam.model.features.Feature.QUESTION_BANK, u); return ResponseEntity.ok(questionBankService.list(u, courseId)); });
    }

    @PostMapping("/question-bank/course/{courseId}")
    public ResponseEntity<?> addBankQuestion(@PathVariable Long courseId, @RequestBody QuestionBankService.BankQuestionRequest req) {
        return withUser(u -> { featureService.require(com.exam.model.features.Feature.QUESTION_BANK, u); return ResponseEntity.ok(questionBankService.create(u, courseId, req)); });
    }

    @PostMapping("/question-bank/upload/course/{courseId}")
    public ResponseEntity<?> uploadBankQuestions(@PathVariable Long courseId, @RequestBody QuestionBankService.UploadRequest req) {
        return withUser(u -> { featureService.require(com.exam.model.features.Feature.QUESTION_BANK, u); return ResponseEntity.ok(questionBankService.upload(u, courseId, req)); });
    }

    @PutMapping("/question-bank/{id}")
    public ResponseEntity<?> updateBankQuestion(@PathVariable Long id, @RequestBody QuestionBankService.BankQuestionRequest req) {
        return withUser(u -> { featureService.require(com.exam.model.features.Feature.QUESTION_BANK, u); return ResponseEntity.ok(questionBankService.update(u, id, req)); });
    }

    @DeleteMapping("/question-bank/{id}")
    public ResponseEntity<?> deleteBankQuestion(@PathVariable Long id) {
        return withUser(u -> { featureService.require(com.exam.model.features.Feature.QUESTION_BANK, u); questionBankService.delete(u, id); return ResponseEntity.ok(Map.of("message", "Deleted.")); });
    }

    @PostMapping("/question-bank/import/quiz/{quizId}")
    public ResponseEntity<?> importQuiz(@PathVariable Long quizId, @RequestBody(required = false) Map<String, String> body) {
        Map<String, String> b = body == null ? Map.of() : body;
        return withUser(u -> { featureService.require(com.exam.model.features.Feature.QUESTION_BANK, u); return ResponseEntity.ok(questionBankService.importFromQuiz(u, quizId, b.get("topic"), b.get("difficulty"))); });
    }

    @PostMapping("/question-bank/draw/quiz/{quizId}")
    public ResponseEntity<?> drawIntoQuiz(@PathVariable Long quizId, @RequestBody QuestionBankService.DrawRequest req) {
        return withUser(u -> { featureService.require(com.exam.model.features.Feature.QUESTION_BANK, u); return ResponseEntity.ok(questionBankService.drawIntoQuiz(u, quizId, req)); });
    }

    // ── Proctoring ───────────────────────────────────────────────────────────

    public static class ProctoringEventRequest {
        public Long quizId;
        public String type;
        public Integer violationNumber;
        /** The reset number the exam page knew when it queued the event (null from older pages: 0). */
        public Integer resetNo;
    }

    /**
     * Called by the exam page each time a violation is detected. A numbered violation also stores the
     * student's violation count (one request per violation), so it carries over to any device.
     */
    @PostMapping("/proctoring/events")
    public ResponseEntity<?> recordEvent(@RequestBody ProctoringEventRequest req, HttpServletRequest http) {
        if (req.quizId == null) return ResponseEntity.badRequest().body(new ErrorMessage("quizId is required."));
        return withUser(u -> {
            if (u.getRole() != Role.NORMAL) return ResponseEntity.ok().build();
            // The count first: it says whether the event is from before a staff reset
            int sentResetNo = req.resetNo == null ? 0 : req.resetNo;
            int count;
            int resetNo;
            if (req.violationNumber != null) {
                QuizTimerService.ViolationState st = quizTimerService.recordViolationCount(req.quizId, u.getId(), req.violationNumber, req.resetNo);
                count = st.count();
                resetNo = st.resetNo();
            } else {
                var st = quizTimerService.getViolationState(req.quizId, u.getId());
                count = st.getTotalViolationCount();
                resetNo = st.getViolationResetNo();
            }
            proctoringService.record(u, req.quizId, req.type, req.violationNumber, clientIp(http), sentResetNo < resetNo);
            // The exam page takes the count from here when staff have reset it
            return ResponseEntity.ok(Map.of("totalViolationCount", count, "violationResetNo", resetNo));
        });
    }

    /**
     * Staff: set a student's violation count back to 0, or down to a lower number ("count"), while
     * they are sitting the quiz (reason required).
     */
    @PostMapping("/proctoring/quiz/{quizId}/student/{studentId}/reset-violations")
    public ResponseEntity<?> resetViolations(@PathVariable Long quizId, @PathVariable Long studentId,
                                             @RequestBody(required = false) Map<String, Object> body, HttpServletRequest http) {
        Object r = body == null ? null : body.get("reason");
        String reason = r == null ? null : String.valueOf(r);
        Object c = body == null ? null : body.get("count");
        Integer count;
        try {
            count = c == null || String.valueOf(c).isBlank() ? 0 : (int) Double.parseDouble(String.valueOf(c));
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().body(new ErrorMessage("The new count must be a whole number."));
        }
        http.setAttribute(com.exam.config.AuditInterceptor.AUDIT_DETAILS,
                "quizId=" + quizId + ", studentId=" + studentId + ", setTo=" + count + ", reason=" + (reason == null ? "" : reason.trim()));
        return withUser(u -> ResponseEntity.ok(proctoringService.resetViolations(u, quizId, studentId, reason, count)));
    }

    @GetMapping("/proctoring/quiz/{quizId}")
    public ResponseEntity<?> proctoringReport(@PathVariable Long quizId) {
        return withUser(u -> ResponseEntity.ok(proctoringService.quizReport(u, quizId)));
    }

    @GetMapping("/proctoring/quiz/{quizId}/student/{studentId}")
    public ResponseEntity<?> proctoringTimeline(@PathVariable Long quizId, @PathVariable Long studentId) {
        return withUser(u -> ResponseEntity.ok(proctoringService.studentTimeline(u, quizId, studentId)));
    }

    // ── Re-mark requests ─────────────────────────────────────────────────────

    @PostMapping("/remarks")
    public ResponseEntity<?> requestRemark(@RequestBody Map<String, Object> body) {
        Object reportId = body.get("reportId");
        if (!(reportId instanceof Number n)) return ResponseEntity.badRequest().body(new ErrorMessage("reportId is required."));
        return withUser(u -> ResponseEntity.ok(remarkService.create(u, n.longValue(), (String) body.get("reason"))));
    }

    @GetMapping("/remarks/mine")
    public ResponseEntity<?> myRemarks() {
        return withUser(u -> ResponseEntity.ok(remarkService.mine(u)));
    }

    @GetMapping("/remarks/manage")
    public ResponseEntity<?> remarksToManage() {
        return withUser(u -> ResponseEntity.ok(remarkService.forStaff(u)));
    }

    @PostMapping("/remarks/{id}/respond")
    public ResponseEntity<?> respondRemark(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return withUser(u -> ResponseEntity.ok(remarkService.respond(u, id, body.get("decision"), body.get("response"))));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private interface UserAction { ResponseEntity<?> apply(User u); }

    private ResponseEntity<?> withUser(UserAction action) {
        Optional<User> user = currentUserService.current();
        if (user.isEmpty()) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorMessage("Please sign in."));
        try {
            return action.apply(user.get());
        } catch (AccessDeniedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ErrorMessage(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ErrorMessage(e.getMessage()));
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            // Log the full cause and tell the user what failed, instead of Spring's empty /error page
            log.error("[ExamOps] Request failed", e);
            Throwable root = e;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorMessage("Something went wrong: " + (root.getMessage() != null ? root.getMessage() : root.getClass().getSimpleName())));
        }
    }

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ExamOpsController.class);

    private static String clientIp(HttpServletRequest request) {
        String fwd = request.getHeader("X-Forwarded-For");
        return fwd != null && !fwd.isBlank() ? fwd.split(",")[0].trim() : request.getRemoteAddr();
    }
}
