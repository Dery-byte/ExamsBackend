package com.exam.controller;

import com.exam.model.User;
import com.exam.model.academic.DocumentVerification;
import com.exam.service.academic.DocumentVerificationService;
import com.exam.service.academic.InstitutionService;
import com.exam.service.academic.TermRemarkService;
import com.exam.service.comms.CurrentUserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Institution profile (name, type, logo), public document verification, the Super Admin's
 * register of issued documents, and report-card remarks per marks sheet.
 */
@RestController
@CrossOrigin(origins = "*")
public class InstitutionController {

    @Autowired private InstitutionService institutionService;
    @Autowired private DocumentVerificationService verificationService;
    @Autowired private TermRemarkService termRemarkService;
    @Autowired private CurrentUserService currentUserService;

    // ── Institution profile ─────────────────────────────────────────────

    /** Public: the login page and every signed-in page use the name and wording. */
    @GetMapping("/api/v1/auth/institution")
    public Map<String, Object> institution() {
        return institutionService.info();
    }

    @GetMapping("/api/v1/auth/institution/logo")
    public ResponseEntity<byte[]> logo(@RequestParam(required = false) String v) {
        // ?v=<logoVersion> changes with every upload, so a versioned URL can be cached for long;
        // a bare URL must be rechecked each time or a replaced logo keeps showing the old image.
        CacheControl cache = v == null ? CacheControl.noCache() : CacheControl.maxAge(30, TimeUnit.DAYS).cachePublic();
        return institutionService.logo()
                .map(l -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(l.getContentType()))
                        .cacheControl(cache)
                        .body(l.getData()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/api/v1/super-admin/institution")
    public Map<String, Object> updateInstitution(@RequestBody Map<String, Object> body) {
        return institutionService.update(body);
    }

    @PostMapping(value = "/api/v1/super-admin/institution/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> uploadLogo(@RequestParam("file") MultipartFile file) throws java.io.IOException {
        institutionService.saveLogo(file.getBytes(), file.getContentType());
        return institutionService.info();
    }

    @DeleteMapping("/api/v1/super-admin/institution/logo")
    public Map<String, Object> deleteLogo() {
        institutionService.deleteLogo();
        return institutionService.info();
    }

    // ── Document verification ───────────────────────────────────────────

    /** Public and rate-limited: anyone holding a printed document can check it. */
    @GetMapping("/api/v1/auth/verify/{code}")
    public Map<String, Object> verify(@PathVariable String code) {
        return verificationService.verify(code);
    }

    @GetMapping("/api/v1/super-admin/documents")
    public List<Map<String, Object>> issuedDocuments(@RequestParam(required = false) String q) {
        return verificationService.recent(q).stream().map(InstitutionController::toDto).toList();
    }

    /** Body {"revoked": true, "reason": "..."}; a revoked code shows as withdrawn when checked. */
    @PostMapping("/api/v1/super-admin/documents/{id}/revoke")
    public Map<String, Object> revoke(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        boolean revoked = !Boolean.FALSE.equals(body.get("revoked"));
        return toDto(verificationService.setRevoked(id, revoked, Objects.toString(body.get("reason"), null)));
    }

    private static Map<String, Object> toDto(DocumentVerification d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("code", d.getCode());
        m.put("type", d.getDocType().name());
        m.put("studentName", d.getStudentName());
        m.put("studentId", d.getStudentUsername());
        m.put("program", d.getProgramName());
        m.put("summary", d.getSummary());
        m.put("issuedAt", d.getIssuedAt().toString());
        m.put("issuedBy", d.getIssuedBy());
        m.put("revoked", d.isRevoked());
        m.put("revokedReason", d.getRevokedReason());
        return m;
    }

    // ── Report-card remarks (attendance, conduct, class teacher / head) ─

    @GetMapping("/api/marks/sheet/{sheetId}/term-remarks")
    public Map<String, Object> termRemarks(@PathVariable Long sheetId) {
        return termRemarkService.view(sheetId, me());
    }

    @PutMapping("/api/marks/sheet/{sheetId}/term-remarks")
    public Map<String, Object> saveTermRemarks(@PathVariable Long sheetId, @RequestBody Map<String, List<TermRemarkService.Row>> body) {
        int saved = termRemarkService.save(sheetId, me(), body.get("rows"));
        return Map.of("message", "Saved remarks for " + saved + " student" + (saved == 1 ? "" : "s") + ".", "saved", saved);
    }

    private User me() {
        return currentUserService.current()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please sign in."));
    }
}
