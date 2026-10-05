package com.exam.controller;

import com.exam.exception.ErrorMessage;
import com.exam.model.User;
import com.exam.service.admin.AccountService;
import com.exam.service.admin.BulkEnrollService;
import com.exam.service.admin.ImportService;
import com.exam.service.comms.CurrentUserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Admin productivity: bulk import, bulk enrolment, results export and account
 * deactivation. Super Admin and HODs only; HODs are limited to their department.
 */
@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/api")
public class DataToolsController {

    @Autowired private CurrentUserService currentUserService;
    @Autowired private ImportService importService;
    @Autowired private BulkEnrollService bulkEnrollService;
    @Autowired private AccountService accountService;
    @Autowired private com.exam.service.features.FeatureService featureService;

    public static class ImportRequest {
        public List<Map<String, String>> rows;
    }

    /** type = students | lecturers | courses; commit=false validates only; notify emails new users their login details. */
    @PostMapping("/admin-tools/import/{type}")
    public ResponseEntity<?> importRows(@PathVariable String type, @RequestParam(defaultValue = "false") boolean commit,
                                        @RequestParam(defaultValue = "true") boolean notify,
                                        @RequestBody ImportRequest req) {
        return withUser(u -> { featureService.require(com.exam.model.features.Feature.HOD_DATA_TOOLS, u); return ResponseEntity.ok(importService.run(u, type, req.rows, commit, notify)); });
    }

    public static class BulkEnrollRequest {
        public Long programId;
        public Integer level;
        public Integer semester;
    }

    @PostMapping("/admin-tools/bulk-enroll")
    public ResponseEntity<?> bulkEnroll(@RequestParam(defaultValue = "false") boolean commit, @RequestBody BulkEnrollRequest req) {
        return withUser(u -> { featureService.require(com.exam.model.features.Feature.HOD_DATA_TOOLS, u); return ResponseEntity.ok(bulkEnrollService.enroll(u, req.programId, req.level, req.semester, commit)); });
    }

    @GetMapping("/admin-tools/results-summary")
    public ResponseEntity<?> resultsSummary(@RequestParam Long programId, @RequestParam(required = false) Integer level) {
        return withUser(u -> { featureService.require(com.exam.model.features.Feature.HOD_DATA_TOOLS, u); return ResponseEntity.ok(bulkEnrollService.resultsSummary(u, programId, level)); });
    }

    @PostMapping("/accounts/{id}/deactivate")
    public ResponseEntity<?> deactivate(@PathVariable Long id, @RequestBody(required = false) Map<String, String> body) {
        return withUser(u -> ResponseEntity.ok(accountService.deactivate(u, id, body == null ? null : body.get("reason"))));
    }

    @PostMapping("/accounts/{id}/reactivate")
    public ResponseEntity<?> reactivate(@PathVariable Long id) {
        return withUser(u -> ResponseEntity.ok(accountService.reactivate(u, id)));
    }

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
        }
    }
}
