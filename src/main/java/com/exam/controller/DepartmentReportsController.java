package com.exam.controller;

import com.exam.model.User;
import com.exam.model.features.Feature;
import com.exam.service.comms.CurrentUserService;
import com.exam.service.features.FeatureService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HOD reports, always for the HOD's own department (path restricted to HODs in EndpointRules;
 * the department lock is applied in ReportCatalog). Needs the "Department reports for HODs" switch.
 */
@RestController
@RequestMapping("/api/hod/reports")
public class DepartmentReportsController extends ReportEndpoints {

    @Autowired private CurrentUserService currentUserService;
    @Autowired private FeatureService featureService;

    @Override
    protected User actor() {
        User u = currentUserService.current().orElseThrow(() -> new AccessDeniedException("Please sign in again."));
        featureService.require(Feature.HOD_REPORTS, u);
        if (u.getDepartment() == null) throw new AccessDeniedException("Your account is not linked to a department.");
        return u;
    }
}
