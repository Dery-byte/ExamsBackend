package com.exam.controller;

import com.exam.model.User;
import com.exam.service.comms.AuditService;
import com.exam.service.reports.OversightReports;
import com.exam.service.reports.ReportCatalog;
import com.exam.service.reports.ReportFilters;
import com.exam.service.reports.ReportPdfService;
import com.exam.service.reports.ReportResult;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.util.List;

/**
 * The report endpoints shared by the Super Admin, HOD and lecturer areas: the list of reports, one report as
 * data (the page builds the CSV / Excel downloads from it), and one report as a PDF. Subclasses
 * set the path and say who is asking; {@link ReportCatalog} decides what they may see.
 */
public abstract class ReportEndpoints {

    @Autowired protected ReportCatalog catalog;
    @Autowired private ReportPdfService pdfService;
    @Autowired private AuditService auditService;

    /** The signed-in user, after checking they may use reports here. */
    protected abstract User actor();

    @GetMapping
    public List<ReportCatalog.Definition> list() {
        return catalog.definitions(actor());
    }

    @GetMapping("/{key}")
    public ReportResult run(@PathVariable String key,
                            @RequestParam(required = false) Long sessionId,
                            @RequestParam(required = false) Long departmentId,
                            @RequestParam(required = false) Long programId,
                            @RequestParam(required = false) Integer level,
                            @RequestParam(required = false) Integer semester,
                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                            @RequestParam(required = false) Long courseId,
                            @RequestParam(required = false) Long quizId,
                            @RequestParam(required = false) String status,
                            HttpServletRequest request) {
        User actor = actor();
        ReportResult r = catalog.run(key, new ReportFilters(sessionId, departmentId, programId, level, semester, from, to, courseId, quizId, status), actor);
        auditAnswerKey(key, quizId, actor, OversightReports.VIEWED_ANSWER_KEY, request);
        return r;
    }

    @GetMapping("/{key}/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable String key,
                                      @RequestParam(required = false) Long sessionId,
                                      @RequestParam(required = false) Long departmentId,
                                      @RequestParam(required = false) Long programId,
                                      @RequestParam(required = false) Integer level,
                                      @RequestParam(required = false) Integer semester,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                      @RequestParam(required = false) Long courseId,
                                      @RequestParam(required = false) Long quizId,
                                      @RequestParam(required = false) String status,
                                      HttpServletRequest request) throws Exception {
        User actor = actor();
        ReportResult r = catalog.run(key, new ReportFilters(sessionId, departmentId, programId, level, semester, from, to, courseId, quizId, status), actor);
        auditAnswerKey(key, quizId, actor, OversightReports.PRINTED_ANSWER_KEY, request);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + key + "-" + LocalDate.now() + ".pdf\"")
                .body(pdfService.render(r));
    }

    /** Opening or printing a quiz's answer key is recorded (only once it was allowed and built). */
    private void auditAnswerKey(String key, Long quizId, User actor, String action, HttpServletRequest request) {
        if (!ReportCatalog.ANSWER_KEY.equals(key) || quizId == null) return;
        String forwarded = request.getHeader("X-Forwarded-For");
        String ip = forwarded != null && !forwarded.isBlank() ? forwarded.split(",")[0].trim() : request.getRemoteAddr();
        auditService.record(actor, action, request.getMethod(), request.getRequestURI(), String.valueOf(quizId), null, 200, ip);
    }
}
