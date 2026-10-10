package com.exam.controller;

import com.exam.model.User;
import com.exam.service.comms.CurrentUserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Super Admin reports, across the institution (path restricted to the Super Admin in EndpointRules). */
@RestController
@RequestMapping("/api/v1/super-admin/reports")
public class SuperAdminReportsController extends ReportEndpoints {

    @Autowired private CurrentUserService currentUserService;

    @Override
    protected User actor() {
        return currentUserService.current().orElseThrow(() -> new AccessDeniedException("Please sign in again."));
    }
}
