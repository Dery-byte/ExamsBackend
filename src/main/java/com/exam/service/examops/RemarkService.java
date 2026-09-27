package com.exam.service.examops;

import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.exam.Quiz;
import com.exam.model.exam.Report;
import com.exam.model.examops.RemarkRequest;
import com.exam.repository.RemarkRequestRepository;
import com.exam.repository.ReportRepository;
import com.exam.service.comms.CurrentUserService;
import com.exam.service.comms.NotificationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Re-mark requests. A student may ask once per reviewed script; the quiz's lecturer (or the
 * HOD / Super Admin) re-reviews it with the normal review screen, then resolves or rejects the
 * request with a written response. Both sides are notified.
 */
@Service
public class RemarkService {

    public static final String REMARK_REQUESTED = "REMARK_REQUESTED";
    public static final String REMARK_ANSWERED  = "REMARK_ANSWERED";

    @Autowired private RemarkRequestRepository remarkRepository;
    @Autowired private ReportRepository reportRepository;
    @Autowired private NotificationService notificationService;
    @Autowired private com.exam.repository.UserRepository userRepository;

    @Transactional
    public Map<String, Object> create(User student, Long reportId, String reason) {
        if (student.getRole() != Role.NORMAL) throw new AccessDeniedException("Only students can request a re-mark.");
        if (reason == null || reason.trim().length() < 10)
            throw new IllegalArgumentException("Please explain the reason (at least 10 characters).");
        Report report = reportRepository.findById(reportId)
                .orElseThrow(() -> new IllegalArgumentException("Result not found."));
        if (report.getUser() == null || !report.getUser().getId().equals(student.getId()))
            throw new AccessDeniedException("This is not your result.");
        if (!Boolean.TRUE.equals(report.getIsReviewed()))
            throw new IllegalArgumentException("You can request a re-mark once your script has been reviewed.");
        if (remarkRepository.existsByReport_Id(reportId))
            throw new IllegalArgumentException("You have already requested a re-mark for this assessment.");

        RemarkRequest r = new RemarkRequest();
        r.setReport(report);
        r.setStudent(student);
        r.setReason(reason.trim());
        r.setScoreBefore(total(report));
        RemarkRequest saved = remarkRepository.save(r);

        Quiz quiz = report.getQuiz();
        for (User owner : ExamAccess.quizOwners(quiz)) {
            notificationService.notify(owner, REMARK_REQUESTED, "Re-mark requested: " + quiz.getTitle(),
                    CurrentUserService.displayName(student) + " asked for their script to be re-marked.",
                    ExamAccess.homePath(owner) + "/remarks");
        }
        return toDto(saved);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> mine(User student) {
        return remarkRepository.findByStudent_IdOrderByCreatedAtDesc(student.getId()).stream().map(this::toDto).toList();
    }

    /** Requests for quizzes the staff member manages, pending first. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> forStaff(User staff) {
        if (!ExamAccess.isStaff(staff)) throw new AccessDeniedException("Staff only.");
        return remarkRepository.findAllByOrderByCreatedAtDesc().stream()
                .filter(r -> ExamAccess.canManageQuiz(staff, r.getReport().getQuiz()))
                .sorted((a, b) -> Boolean.compare(b.getStatus() == RemarkRequest.Status.PENDING,
                        a.getStatus() == RemarkRequest.Status.PENDING))
                .map(this::toDto).toList();
    }

    @Transactional
    public Map<String, Object> respond(User staff, Long id, String decision, String response) {
        RemarkRequest r = remarkRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Request not found."));
        Quiz quiz = r.getReport().getQuiz();
        ExamAccess.requireQuiz(staff, quiz);
        if (r.getStatus() != RemarkRequest.Status.PENDING) throw new IllegalArgumentException("This request has already been answered.");
        if (response == null || response.isBlank()) throw new IllegalArgumentException("Please write a response to the student.");

        RemarkRequest.Status status;
        try { status = RemarkRequest.Status.valueOf(decision); }
        catch (Exception e) { throw new IllegalArgumentException("Decision must be RESOLVED or REJECTED."); }
        if (status == RemarkRequest.Status.PENDING) throw new IllegalArgumentException("Decision must be RESOLVED or REJECTED.");

        r.setStatus(status);
        r.setResponse(response.trim());
        // Use this session's copy of the user, not the detached one from the sign-in filter
        r.setRespondedBy(userRepository.getReferenceById(staff.getId()));
        r.setRespondedAt(LocalDateTime.now());
        // Re-read the report: the lecturer may have re-marked it through the review screen
        Report fresh = reportRepository.findById(r.getReport().getId()).orElse(r.getReport());
        r.setScoreAfter(total(fresh));
        RemarkRequest saved = remarkRepository.save(r);

        String outcome = status == RemarkRequest.Status.REJECTED ? "was declined"
                : (changed(saved) ? "was completed and your score changed" : "was completed; your score is unchanged");
        notificationService.notify(r.getStudent(), REMARK_ANSWERED, "Re-mark request answered: " + quiz.getTitle(),
                "Your re-mark request " + outcome + ".", "/user-dashboard/history");
        return toDto(saved);
    }

    private static BigDecimal total(Report r) {
        BigDecimal a = r.getMarks() == null ? BigDecimal.ZERO : r.getMarks();
        BigDecimal b = r.getMarksB() == null ? BigDecimal.ZERO : r.getMarksB();
        return a.add(b);
    }

    private static boolean changed(RemarkRequest r) {
        return r.getScoreBefore() != null && r.getScoreAfter() != null && r.getScoreBefore().compareTo(r.getScoreAfter()) != 0;
    }

    private Map<String, Object> toDto(RemarkRequest r) {
        Report rep = r.getReport();
        Quiz quiz = rep.getQuiz();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("reportId", rep.getId());
        m.put("quizId", quiz != null ? quiz.getqId() : null);
        m.put("quizTitle", quiz != null ? quiz.getTitle() : null);
        m.put("courseCode", quiz != null && quiz.getCategory() != null ? quiz.getCategory().getCourseCode() : null);
        m.put("studentId", r.getStudent().getId());
        m.put("studentName", CurrentUserService.displayName(r.getStudent()));
        m.put("reason", r.getReason());
        m.put("status", r.getStatus().name());
        m.put("response", r.getResponse());
        m.put("respondedBy", CurrentUserService.displayName(r.getRespondedBy()));
        m.put("scoreBefore", r.getScoreBefore());
        m.put("scoreAfter", r.getScoreAfter());
        m.put("scoreChanged", changed(r));
        m.put("createdAt", r.getCreatedAt());
        m.put("respondedAt", r.getRespondedAt());
        return m;
    }
}
