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
 * Lecturer reports, always for the courses they teach and quizzes they manage (path restricted to
 * lecturers in EndpointRules; the lock is applied in ReportCatalog). Needs the "Reports for lecturers" switch.
 */
@RestController
@RequestMapping("/api/lecturer/reports")
public class LecturerReportsController extends ReportEndpoints {

    @Autowired private CurrentUserService currentUserService;
    @Autowired private FeatureService featureService;

    @Override
    protected User actor() {
        User u = currentUserService.current().orElseThrow(() -> new AccessDeniedException("Please sign in again."));
        featureService.require(Feature.LECTURER_REPORTS, u);
        return u;
    }
}
