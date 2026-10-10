package com.exam.controller;

import com.exam.model.User;
import com.exam.service.reports.ReportCatalog;
import com.exam.service.reports.ReportFilters;
import com.exam.service.reports.ReportPdfService;
import com.exam.service.reports.ReportResult;
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
 * The report endpoints shared by the Super Admin and HOD areas: the list of reports, one report as
 * data (the page builds the CSV / Excel downloads from it), and one report as a PDF. Subclasses
 * set the path and say who is asking; {@link ReportCatalog} decides what they may see.
 */
public abstract class ReportEndpoints {

    @Autowired protected ReportCatalog catalog;
    @Autowired private ReportPdfService pdfService;

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
                            @RequestParam(required = false) String status) {
        return catalog.run(key, new ReportFilters(sessionId, departmentId, programId, level, semester, from, to, courseId, quizId, status), actor());
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
                                      @RequestParam(required = false) String status) throws Exception {
        ReportResult r = catalog.run(key, new ReportFilters(sessionId, departmentId, programId, level, semester, from, to, courseId, quizId, status), actor());
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + key + "-" + LocalDate.now() + ".pdf\"")
                .body(pdfService.render(r));
    }
}
