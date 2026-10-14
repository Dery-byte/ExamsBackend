package com.exam.controller;

import com.exam.exception.ErrorMessage;
import com.exam.model.User;
import com.exam.service.attendance.AttendancePolicyService;
import com.exam.service.attendance.AttendanceService;
import com.exam.service.attendance.ClassScheduleService;
import com.exam.service.attendance.ClassroomService;
import com.exam.service.comms.CurrentUserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

/**
 * Class attendance. /student: check in and own record; /manage: staff run sessions, classrooms and
 * class times; /settings: the Super Admin's and HODs' rules. Role checks are in {@code EndpointRules}
 * and, with ownership, in the services.
 */
@RestController
@RequestMapping("/api/attendance")
public class AttendanceController {

    @Autowired private CurrentUserService currentUserService;
    @Autowired private AttendanceService attendance;
    @Autowired private AttendancePolicyService policies;
    @Autowired private ClassroomService classrooms;
    @Autowired private ClassScheduleService schedules;

    // ── Student ──────────────────────────────────────────────────────────────

    @GetMapping("/student/open")
    public ResponseEntity<?> openForMe() {
        return withUser(u -> ResponseEntity.ok(attendance.openForStudent(u)));
    }

    /** Always 200: a refusal carries ok=false and a message (a counted wrong code must still be saved). */
    @PostMapping("/student/check-in")
    public ResponseEntity<?> checkIn(@RequestBody AttendanceService.CheckInRequest req, HttpServletRequest http) {
        return withUser(u -> ResponseEntity.ok(attendance.checkIn(u, req, clientIp(http))));
    }

    /** Scanned classes: the student's key for drawing their changing QR code (after the phone and location checks). */
    @PostMapping("/student/qr-key")
    public ResponseEntity<?> qrKey(@RequestBody AttendanceService.QrKeyRequest req) {
        return withUser(u -> ResponseEntity.ok(attendance.qrKey(u, req)));
    }

    @GetMapping("/student/summary")
    public ResponseEntity<?> mySummary() {
        return withUser(u -> ResponseEntity.ok(attendance.studentSummary(u)));
    }

    // ── Staff: sessions ──────────────────────────────────────────────────────

    @GetMapping("/manage/options")
    public ResponseEntity<?> options() {
        return withUser(u -> ResponseEntity.ok(attendance.options(u)));
    }

    @GetMapping("/manage/sessions")
    public ResponseEntity<?> sessions() {
        return withUser(u -> ResponseEntity.ok(attendance.list(u)));
    }

    @PostMapping("/manage/sessions")
    public ResponseEntity<?> open(@RequestBody AttendanceService.OpenRequest req) {
        return withUser(u -> ResponseEntity.ok(attendance.open(u, req)));
    }

    @GetMapping("/manage/sessions/{id}")
    public ResponseEntity<?> detail(@PathVariable Long id) {
        return withUser(u -> ResponseEntity.ok(attendance.detail(u, id)));
    }

    @GetMapping("/manage/sessions/{id}/code")
    public ResponseEntity<?> code(@PathVariable Long id) {
        return withUser(u -> ResponseEntity.ok(attendance.codeView(u, id)));
    }

    @PostMapping("/manage/sessions/{id}/close-check-in")
    public ResponseEntity<?> closeCheckIn(@PathVariable Long id) {
        return withUser(u -> ResponseEntity.ok(attendance.closeCheckIn(u, id)));
    }

    @PostMapping("/manage/sessions/{id}/extend")
    public ResponseEntity<?> extend(@PathVariable Long id, @RequestBody Map<String, Integer> body) {
        Integer minutes = body == null ? null : body.get("minutes");
        return withUser(u -> ResponseEntity.ok(attendance.extendCheckIn(u, id, minutes == null ? 5 : minutes)));
    }

    @PostMapping("/manage/sessions/{id}/second-check")
    public ResponseEntity<?> startSecondCheck(@PathVariable Long id) {
        return withUser(u -> ResponseEntity.ok(attendance.startSecondCheck(u, id)));
    }

    @DeleteMapping("/manage/sessions/{id}/second-check")
    public ResponseEntity<?> cancelSecondCheck(@PathVariable Long id) {
        return withUser(u -> ResponseEntity.ok(attendance.cancelSecondCheck(u, id)));
    }

    @PutMapping("/manage/sessions/{id}/headcount")
    public ResponseEntity<?> headcount(@PathVariable Long id, @RequestBody Map<String, Integer> body) {
        return withUser(u -> ResponseEntity.ok(attendance.setHeadcount(u, id, body == null ? null : body.get("headcount"))));
    }

    @PutMapping("/manage/sessions/{id}/students/{studentId}")
    public ResponseEntity<?> mark(@PathVariable Long id, @PathVariable Long studentId, @RequestBody Map<String, String> body) {
        Map<String, String> b = body == null ? Map.of() : body;
        return withUser(u -> ResponseEntity.ok(attendance.mark(u, id, studentId, b.get("status"), b.get("note"))));
    }

    @PostMapping("/manage/sessions/{id}/approve-pending")
    public ResponseEntity<?> approvePending(@PathVariable Long id) {
        return withUser(u -> ResponseEntity.ok(attendance.approvePending(u, id)));
    }

    @PostMapping("/manage/sessions/{id}/students/{studentId}/unlock")
    public ResponseEntity<?> unlock(@PathVariable Long id, @PathVariable Long studentId) {
        return withUser(u -> ResponseEntity.ok(attendance.unlock(u, id, studentId)));
    }

    @PostMapping("/manage/sessions/{id}/students/{studentId}/reset-phone")
    public ResponseEntity<?> resetPhone(@PathVariable Long id, @PathVariable Long studentId) {
        return withUser(u -> ResponseEntity.ok(attendance.resetPhone(u, id, studentId)));
    }

    @PostMapping("/manage/sessions/{id}/end")
    public ResponseEntity<?> end(@PathVariable Long id) {
        return withUser(u -> ResponseEntity.ok(attendance.end(u, id)));
    }

    /** The lecturer scanned a student's QR code. Always 200: a refusal carries ok=false and why. */
    @PostMapping("/manage/sessions/{id}/scan")
    public ResponseEntity<?> scan(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return withUser(u -> ResponseEntity.ok(attendance.scanByStaff(u, id, body == null ? null : body.get("code"))));
    }

    @PostMapping("/manage/sessions/{id}/helpers")
    public ResponseEntity<?> addHelper(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return withUser(u -> ResponseEntity.ok(attendance.addHelper(u, id, body == null ? null : body.get("label"))));
    }

    @DeleteMapping("/manage/sessions/{id}/helpers/{helperId}")
    public ResponseEntity<?> revokeHelper(@PathVariable Long id, @PathVariable Long helperId) {
        return withUser(u -> ResponseEntity.ok(attendance.revokeHelper(u, id, helperId)));
    }

    @DeleteMapping("/manage/sessions/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        return withUser(u -> { attendance.delete(u, id); return ResponseEntity.ok(Map.of("message", "Session deleted.")); });
    }

    // ── Staff: classrooms and class times ────────────────────────────────────

    @GetMapping("/manage/classrooms")
    public ResponseEntity<?> classrooms() {
        return withUser(u -> ResponseEntity.ok(classrooms.list(u)));
    }

    @PostMapping("/manage/classrooms")
    public ResponseEntity<?> addClassroom(@RequestBody ClassroomService.ClassroomRequest req) {
        return withUser(u -> ResponseEntity.ok(classrooms.create(u, req)));
    }

    @PutMapping("/manage/classrooms/{id}")
    public ResponseEntity<?> updateClassroom(@PathVariable Long id, @RequestBody ClassroomService.ClassroomRequest req) {
        return withUser(u -> ResponseEntity.ok(classrooms.update(u, id, req)));
    }

    @DeleteMapping("/manage/classrooms/{id}")
    public ResponseEntity<?> deleteClassroom(@PathVariable Long id) {
        return withUser(u -> { classrooms.delete(u, id); return ResponseEntity.ok(Map.of("message", "Classroom deleted.")); });
    }

    @GetMapping("/manage/schedules")
    public ResponseEntity<?> schedules() {
        return withUser(u -> ResponseEntity.ok(schedules.list(u)));
    }

    @PostMapping("/manage/schedules")
    public ResponseEntity<?> addSchedule(@RequestBody ClassScheduleService.ScheduleRequest req) {
        return withUser(u -> ResponseEntity.ok(schedules.create(u, req)));
    }

    @PutMapping("/manage/schedules/{id}")
    public ResponseEntity<?> updateSchedule(@PathVariable Long id, @RequestBody ClassScheduleService.ScheduleRequest req) {
        return withUser(u -> ResponseEntity.ok(schedules.update(u, id, req)));
    }

    @DeleteMapping("/manage/schedules/{id}")
    public ResponseEntity<?> deleteSchedule(@PathVariable Long id) {
        return withUser(u -> { schedules.delete(u, id); return ResponseEntity.ok(Map.of("message", "Class time deleted.")); });
    }

    // ── Helper scanner links (no account; the secret link is the key) ───────

    @GetMapping("/scan/{token}")
    public ResponseEntity<?> helperView(@PathVariable String token) {
        return publicCall(() -> ResponseEntity.ok(attendance.helperView(token)));
    }

    @PostMapping("/scan/{token}")
    public ResponseEntity<?> helperScan(@PathVariable String token, @RequestBody AttendanceService.HelperScanRequest req) {
        return publicCall(() -> ResponseEntity.ok(attendance.scanByHelper(token, req)));
    }

    private ResponseEntity<?> publicCall(java.util.function.Supplier<ResponseEntity<?>> action) {
        try {
            return action.get();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.GONE).body(new ErrorMessage(e.getMessage()));
        } catch (DataIntegrityViolationException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorMessage("That was already recorded."));
        } catch (RuntimeException e) {
            log.error("[Attendance] Helper scan failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ErrorMessage("Something went wrong. Please try again."));
        }
    }

    // ── Rules (Super Admin, HODs) ────────────────────────────────────────────

    @GetMapping("/settings")
    public ResponseEntity<?> settings() {
        return withUser(u -> ResponseEntity.ok(policies.view(u)));
    }

    @PutMapping("/settings/system")
    public ResponseEntity<?> saveSystem(@RequestBody AttendancePolicyService.PolicyRequest req) {
        return withUser(u -> ResponseEntity.ok(policies.saveSystem(u, req)));
    }

    @PutMapping("/settings/departments/{departmentId}")
    public ResponseEntity<?> saveDepartment(@PathVariable Long departmentId, @RequestBody AttendancePolicyService.PolicyRequest req) {
        return withUser(u -> ResponseEntity.ok(policies.saveDepartment(u, departmentId, req)));
    }

    @DeleteMapping("/settings/departments/{departmentId}")
    public ResponseEntity<?> resetDepartment(@PathVariable Long departmentId) {
        return withUser(u -> { policies.resetDepartment(u, departmentId); return ResponseEntity.ok(Map.of("message", "The department follows the default rules again.")); });
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
        } catch (DataIntegrityViolationException e) {
            // Two taps at once, or a phone linked by another student a moment earlier
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorMessage("That was already recorded. Refresh the page and try again."));
        } catch (RuntimeException e) {
            log.error("[Attendance] Request failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ErrorMessage("Something went wrong. Please try again."));
        }
    }

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AttendanceController.class);

    private static String clientIp(HttpServletRequest request) {
        String fwd = request.getHeader("X-Forwarded-For");
        return fwd != null && !fwd.isBlank() ? fwd.split(",")[0].trim() : request.getRemoteAddr();
    }
}
