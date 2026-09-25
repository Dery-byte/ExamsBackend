package com.exam.service;

import com.exam.model.User;
import com.exam.model.exam.Quiz;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.time.Year;
import java.util.concurrent.CompletableFuture;

/**
 * Emails a student their PDF result slip once the lecturer has reviewed their quiz.
 * Sent only when the quiz has "Email PDF report" turned on by its lecturer AND the admin master switch
 * (EMAIL_REPORT_FEATURE_ENABLED, on by default) allows it.
 * Never throws: a mail problem must not break the lecturer's review save.
 */
@Service
public class ReportEmailService {

    private static final Logger log = LoggerFactory.getLogger(ReportEmailService.class);

    @Autowired private SystemSettingService systemSettingService;
    @Autowired private PdfReportService pdfReportService;
    @Autowired private JavaMailSender mailSender;

    @Value("${app.mail.from-address:optimusinforservice@gmail.com}") private String fromAddress;
    @Value("${app.mail.from-name:EduApp Support}") private String fromName;

    public boolean isEnabled() {
        return systemSettingService.getBooleanSetting(SystemSettingService.EMAIL_REPORT_FEATURE_ENABLED, true);
    }

    /**
     * If the feature is on and the student has an email address, builds the PDF (on the caller's
     * thread, where the persistence context is available) and sends it in the background.
     */
    public void sendReviewedReport(User student, Quiz quiz) {
        try {
            if (student == null || quiz == null) return;
            if (!Boolean.TRUE.equals(quiz.getEmailReportOnReview())) return;   // lecturer opted out for this quiz
            if (!isEnabled()) return;                                          // admin master switch is off
            String to = student.getEmail();
            if (to == null || to.isBlank()) {
                log.warn("[REPORT-MAIL] Student {} has no email address; skipping", student.getId());
                return;
            }

            byte[] pdf = pdfReportService.generateReportPdf(quiz.getqId(), student.getId());
            String name = ((student.getFirstname() == null ? "" : student.getFirstname()) + " "
                    + (student.getLastname() == null ? "" : student.getLastname())).trim();
            String course = quiz.getCategory() != null && quiz.getCategory().getTitle() != null
                    ? quiz.getCategory().getTitle() : "your course";
            String quizTitle = quiz.getTitle() != null ? quiz.getTitle() : "Quiz";
            String fileName = ("ResultsSlip_" + course + "_" + quizTitle).replaceAll("[^a-zA-Z0-9.-]", "_") + ".pdf";

            // Resolve the lecturer name from the quiz owner
            User lecturer = quiz.getUser();
            String lecturerName = lecturer == null ? "" :
                    ((lecturer.getFirstname() == null ? "" : lecturer.getFirstname()) + " "
                    + (lecturer.getLastname() == null ? "" : lecturer.getLastname())).trim();

            CompletableFuture.runAsync(() -> send(to, name, course, quizTitle, fileName, pdf, lecturerName));
        } catch (Exception e) {
            log.error("[REPORT-MAIL] Could not prepare result slip email for quiz {}", quiz != null ? quiz.getqId() : null, e);
        }
    }

    private void send(String to, String name, String course, String quizTitle, String fileName, byte[] pdf, String lecturerName) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromAddress, fromName);
            helper.setTo(to);
            helper.setSubject("Your result slip: " + course + " - " + quizTitle);
            String greeting = name.isEmpty() ? "Hello," : "Hello " + HtmlUtils.htmlEscape(name) + ",";
            String lecturerLine = lecturerName.isEmpty()
                    ? "Your lecturer"
                    : "<b>" + HtmlUtils.htmlEscape(lecturerName) + "</b>";
            helper.setText("<p>" + greeting + "</p>"
                    + "<p>" + lecturerLine + " has completed the review of "
                    + "<b>" + HtmlUtils.htmlEscape(quizTitle) + "</b> (" + HtmlUtils.htmlEscape(course)
                    + "). Your result slip has been attached to this email as a PDF document.</p>"
                    + "<p>Alternatively, you may download it at any time from your dashboard.</p>"
                    + "<p>Should you have any questions or concerns, please do not hesitate to contact <b>"
                    + (lecturerName == null || lecturerName.isBlank() ? "Your Lecturer" : HtmlUtils.htmlEscape(lecturerName))
                    + "</b>.</p>"
                    + "<p>Best regards,<br>"
                    + " OTC &copy; " + Year.now().getValue() + "</p>", true);
            helper.addAttachment(fileName, new ByteArrayResource(pdf), "application/pdf");
            mailSender.send(message);
            log.info("[REPORT-MAIL] Result slip sent to {}", to);
        } catch (Exception e) {
            log.error("[REPORT-MAIL] Failed to send result slip to {}", to, e);
        }
    }
}
