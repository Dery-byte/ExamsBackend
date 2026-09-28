package com.exam.controller;

import com.exam.exception.ErrorMessage;
import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.exam.Department;
import com.exam.service.comms.*;
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
 * Communication & oversight endpoints: notifications, announcements, analytics (all under
 * /api, so they require a signed-in user) and the audit log (under /api/v1/super-admin,
 * so Super Admin only).
 */
@RestController
@CrossOrigin(origins = "*")
public class CommsController {

    @Autowired private CurrentUserService currentUserService;
    @Autowired private NotificationService notificationService;
    @Autowired private AnnouncementService announcementService;
    @Autowired private AnalyticsService analyticsService;
    @Autowired private AuditService auditService;
    @Autowired private com.exam.service.features.FeatureService featureService;

    // ── Notifications ────────────────────────────────────────────────────────

    @GetMapping("/api/notifications")
    public ResponseEntity<?> myNotifications(@RequestParam(defaultValue = "30") int limit) {
        return withUser(u -> ResponseEntity.ok(notificationService.recentFor(u.getId(), limit)));
    }

    @GetMapping("/api/notifications/unread-count")
    public ResponseEntity<?> unreadCount() {
        return withUser(u -> ResponseEntity.ok(Map.of("count", notificationService.unreadCount(u.getId()))));
    }

    @PostMapping("/api/notifications/{id}/read")
    public ResponseEntity<?> markRead(@PathVariable Long id) {
        return withUser(u -> { notificationService.markRead(id, u.getId()); return ResponseEntity.ok().build(); });
    }

    @PostMapping("/api/notifications/read-all")
    public ResponseEntity<?> markAllRead() {
        return withUser(u -> { notificationService.markAllRead(u.getId()); return ResponseEntity.ok().build(); });
    }

    // ── Announcements ────────────────────────────────────────────────────────

    @GetMapping("/api/announcements")
    public ResponseEntity<?> myAnnouncements() {
        return withUser(u -> ResponseEntity.ok(announcementService.visibleTo(u)));
    }

    @GetMapping("/api/announcements/manage")
    public ResponseEntity<?> manageableAnnouncements() {
        return withUser(u -> ResponseEntity.ok(announcementService.manageableBy(u)));
    }

    @PostMapping("/api/announcements")
    public ResponseEntity<?> postAnnouncement(@RequestBody AnnouncementService.CreateRequest req) {
        return withUser(u -> ResponseEntity.ok(announcementService.create(u, req)));
    }

    @DeleteMapping("/api/announcements/{id}")
    public ResponseEntity<?> deleteAnnouncement(@PathVariable Long id) {
        return withUser(u -> { announcementService.delete(u, id); return ResponseEntity.ok(Map.of("message", "Deleted.")); });
    }

    // ── Analytics ────────────────────────────────────────────────────────────

    /** Super Admin: whole university, or one department via ?departmentId. HOD: own department only. */
    @GetMapping("/api/analytics/overview")
    public ResponseEntity<?> analytics(@RequestParam(required = false) Long departmentId) {
        return withUser(u -> {
            Department dept;
            if (u.getRole() == Role.SUPER_ADMIN) {
                dept = departmentId == null ? null : analyticsService.department(departmentId)
                        .orElseThrow(() -> new IllegalArgumentException("Department not found."));
            } else if (u.getRole() == Role.ADMIN) {
                featureService.require(com.exam.model.features.Feature.HOD_ANALYTICS, u);
                dept = u.getDepartment();
                if (dept == null) throw new AccessDeniedException("Your account is not linked to a department.");
            } else {
                throw new AccessDeniedException("Only the Super Admin and HODs can view analytics.");
            }
            return ResponseEntity.ok(analyticsService.overview(dept));
        });
    }

    // ── Audit log (Super Admin; path is restricted in SecurityConfiguration) ─

    @GetMapping("/api/v1/super-admin/audit-logs")
    public ResponseEntity<?> auditLogs(@RequestParam(required = false) String actor,
                                       @RequestParam(required = false) String action,
                                       @RequestParam(required = false) String role,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(auditService.search(actor, action, role, from, to, page, size));
    }

    @GetMapping("/api/v1/super-admin/audit-logs/actions")
    public ResponseEntity<?> auditActions() {
        return ResponseEntity.ok(auditService.actions());
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
        }
    }
}
