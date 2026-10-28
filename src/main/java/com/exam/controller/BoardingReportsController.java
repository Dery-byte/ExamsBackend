package com.exam.controller;

import com.exam.model.User;
import com.exam.service.comms.CurrentUserService;
import com.exam.service.reports.ReportCatalog;
import com.exam.service.reports.ReportFilters;
import com.exam.service.reports.ReportResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Boarding reports for boarding staff (housemasters, the head of boarding, the Super Admin), from the
 * Boarding pages: only the boarding group, locked to the houses each person looks after, and not
 * tied to the HOD / lecturer report switches. Boarding off → refused in the catalog.
 */
@RestController
@RequestMapping("/api/boarding/reports")
public class BoardingReportsController extends ReportEndpoints {

    @Autowired private CurrentUserService currentUserService;

    @Override
    protected User actor() {
        return currentUserService.current().orElseThrow(() -> new AccessDeniedException("Please sign in again."));
    }

    @Override
    protected List<ReportCatalog.Definition> definitions(User actor) {
        return catalog.boardingDefinitions(actor);
    }

    @Override
    protected ReportResult runReport(String key, ReportFilters f, User actor) {
        return catalog.runBoarding(key, f, actor);
    }
}
