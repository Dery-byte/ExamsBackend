package com.exam.controller;

import com.exam.model.User;
import com.exam.service.comms.CurrentUserService;
import com.exam.service.fees.FinanceAccess;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The accounts office's reports (the catalog gives accountants the fee reports only). */
@RestController
@RequestMapping("/api/finance/reports")
public class FinanceReportsController extends ReportEndpoints {

    @Autowired private CurrentUserService currentUserService;

    @Override
    protected User actor() {
        User u = currentUserService.current().orElseThrow(() -> new AccessDeniedException("Please sign in again."));
        FinanceAccess.requireFinance(u);
        return u;
    }
}
