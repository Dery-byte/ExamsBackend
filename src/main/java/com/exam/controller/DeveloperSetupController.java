package com.exam.controller;

import com.exam.config.AuditInterceptor;
import com.exam.service.academic.ThemeService;
import com.exam.service.admin.ConfigTransferService;
import com.exam.service.admin.MaintenanceService;
import com.exam.service.monitoring.SetupChecklistService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * The developer's setup tools: the institution's colour theme, maintenance mode, the setup checklist
 * (with a test email and creating a Super Admin) and settings export / import.
 * Everything under /api/v1/developer is for the DEVELOPER role only (EndpointRules).
 */
@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/api/v1/developer")
public class DeveloperSetupController {

    @Autowired private ThemeService themeService;
    @Autowired private MaintenanceService maintenanceService;
    @Autowired private SetupChecklistService setupChecklist;
    @Autowired private ConfigTransferService configTransfer;

    // ── Colour theme ────────────────────────────────────────────────────

    @GetMapping("/theme")
    public Map<String, Object> theme() {
        return themeService.theme();
    }

    @PutMapping("/theme")
    public Map<String, Object> setTheme(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        request.setAttribute(AuditInterceptor.AUDIT_DETAILS, "brand = " + body.get("brand") + ", sidebar = " + body.get("sidebar")
                + ", accent = " + (body.get("accent") == null ? "auto" : body.get("accent")));
        return themeService.update(body);
    }

    @DeleteMapping("/theme")
    public Map<String, Object> resetTheme() {
        return themeService.reset();
    }

    // ── Maintenance mode ────────────────────────────────────────────────

    @GetMapping("/maintenance")
    public Map<String, Object> maintenance() {
        return maintenanceService.status();
    }

    @PutMapping("/maintenance")
    public Map<String, Object> setMaintenance(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        request.setAttribute(AuditInterceptor.AUDIT_DETAILS, "on = " + body.get("enabled") + ", Super Admins may sign in = " + body.get("allowAdmins"));
        return maintenanceService.update(body);
    }

    // ── Setup checklist ─────────────────────────────────────────────────

    @GetMapping("/setup")
    public Map<String, Object> setup() {
        return setupChecklist.checklist();
    }

    @PostMapping("/setup/test-email")
    public Map<String, Object> testEmail(@RequestBody Map<String, String> body, HttpServletRequest request) {
        request.setAttribute(AuditInterceptor.AUDIT_DETAILS, "to " + body.get("to"));
        return setupChecklist.sendTestEmail(body.get("to"));
    }

    @PostMapping("/setup/super-admin")
    public Map<String, Object> createSuperAdmin(@RequestBody Map<String, String> body, HttpServletRequest request) {
        request.setAttribute(AuditInterceptor.AUDIT_DETAILS, "username = " + body.get("username") + ", email = " + body.get("email"));
        return setupChecklist.createSuperAdmin(body);
    }

    // ── Settings export / import ────────────────────────────────────────

    @GetMapping("/config/export")
    public Map<String, Object> exportConfig(@RequestParam(defaultValue = "true") boolean logo) {
        return configTransfer.export(logo);
    }

    /** Shows what an import would change, without changing anything. */
    @PostMapping("/config/preview")
    public Map<String, Object> previewImport(@RequestBody Map<String, Object> file) {
        return configTransfer.importConfig(file, false);
    }

    @PostMapping("/config/import")
    public Map<String, Object> importConfig(@RequestBody Map<String, Object> file, HttpServletRequest request) {
        Map<String, Object> result = configTransfer.importConfig(file, true);
        request.setAttribute(AuditInterceptor.AUDIT_DETAILS, ((java.util.List<?>) result.get("changes")).size() + " setting(s) changed"
                + (Boolean.TRUE.equals(result.get("logo")) ? ", logo replaced" : ""));
        return result;
    }
}
