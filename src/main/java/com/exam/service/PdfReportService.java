package com.exam.service;

import com.exam.helper.TheoryGroups;
import com.exam.model.exam.*;
import com.exam.repository.AnswerRepository;
import com.exam.repository.ReportRepository;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.xhtmlrenderer.pdf.ITextRenderer;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.core.io.ClassPathResource;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

@Service
public class PdfReportService {

    @Autowired private ReportRepository reportRepository;
    @Autowired private AnswerRepository answerRepository;
    @Autowired @Lazy private ReportService reportService;
    @Autowired private NumberOfTheoryToAnswerService numberOfTheoryToAnswerService;
    @Autowired private TemplateEngine templateEngine;
    @Autowired private QuestionImageService questionImageService;
    @Autowired private com.exam.service.academic.InstitutionService institutionService;
    @Autowired private com.exam.service.academic.ThemeService themeService;
    @Autowired private com.exam.service.academic.DocumentVerificationService verificationService;
    @Autowired @Lazy private com.exam.service.academic.TermRemarkService termRemarkService;
    @Autowired @Lazy private MarksEntryService marksEntryService;

    private static final int IMG_MAX_W_PT = 380;
    private static final int IMG_MAX_H_PT = 200;

    // ── Inner DTOs ────────────────────────────────────────────────────────────

    @Getter @AllArgsConstructor
    public static class OptionDto {
        private String letter;
        private String text;
        private boolean correct;
        private boolean selected;
    }

    @Getter @AllArgsConstructor
    public static class McqDto {
        private int number;
        private String content;
        private QuestionImageService.PdfImage image;
        private String status;
        private List<OptionDto> options;
    }

    @Getter @AllArgsConstructor
    public static class MatchingPairDto {
        private String prompt;
        private String expected;
        private String selected;
        private boolean correct;
    }

    @Getter @AllArgsConstructor
    public static class MatchingDto {
        private int number;
        private String content;
        private QuestionImageService.PdfImage image;
        private String status;
        private int pairsCorrect;
        private int pairsTotal;
        private List<MatchingPairDto> pairs;
    }

    @Getter @AllArgsConstructor
    public static class TheoryAnswerDto {
        private String quesNo;
        private String question;
        private QuestionImageService.PdfImage image;
        private String studentAnswer;
        private String score;
        private String maxMarks;
        private int    scorePct;
        private List<String> keyMissed;
        private String feedback;
        private String lecturerComment;
    }

    @Getter @AllArgsConstructor
    public static class TheoryGroupDto {
        private String prefix;
        private List<TheoryAnswerDto> questions;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public byte[] generateReportPdf(Long quizId, Long userId) throws Exception {

        System.out.println("[PDF-SVC] ▶ Step A: Looking up report for quizId=" + quizId + ", userId=" + userId);
        Report report = reportRepository.findByUser_idAndQuiz_qId(userId.intValue(), quizId);
        System.out.println("[PDF-SVC] ✔ Step A Done — report found=" + (report != null));

        System.out.println("[PDF-SVC] ▶ Step B: Getting MCQ results");
        Map<String, Object> mcqResult = reportService.getStudentQuizResult(quizId, userId);
        System.out.println("[PDF-SVC] ✔ Step B Done");

        System.out.println("[PDF-SVC] ▶ Step C: Loading theory answers");
        List<Answer> theoryAnswers = answerRepository.findByQuiz_qId(quizId).stream()
                .filter(a -> a.getUser() != null && userId.equals(a.getUser().getId()))
                .collect(Collectors.toList());
        System.out.println("[PDF-SVC] ✔ Step C Done — theoryAnswers count=" + theoryAnswers.size());

        List<NumberOfTheoryToAnswer> theoryConfig = numberOfTheoryToAnswerService.findByQuizId(quizId);
        double theoryMins = theoryConfig.isEmpty() ? 0 : theoryConfig.get(0).getTimeAllowed();

        double objScore   = report != null && report.getMarks() != null        ? report.getMarks().doubleValue()          : 0;
        double maxObj     = report != null && report.getQuiz() != null         ? safeDouble(report.getQuiz().getMaxMarks()) : 0;
        double thScore    = theoryAnswers.stream().mapToDouble(Answer::getScore).sum();
        double maxTh      = report != null && report.getMaxScoreSectionB() != null ? report.getMaxScoreSectionB().doubleValue() : 0;
        double total      = objScore + thScore;
        double totalMax   = maxObj + maxTh;
        int    totalPct   = pct(total, totalMax);
        int    objPct     = pct(objScore, maxObj);
        int    thPct      = pct(thScore, maxTh);

        String  qt           = report != null && report.getQuiz() != null ? safeStr(report.getQuiz().getQuizType()) : "";
        boolean showSectionA = !"THEORY".equals(qt);
        boolean showSectionB = !"OBJ".equals(qt) && !theoryAnswers.isEmpty();

        double objMins = report != null && report.getQuiz() != null ? safeDouble(report.getQuiz().getQuizTime()) : 0;
        String duration = formatDuration((int)(objMins + theoryMins));

        String submDate = "N/A";
        if (report != null && report.getSubmissionDate() != null) {
            submDate = report.getSubmissionDate().format(DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm"));
        }

        System.out.println("[PDF-SVC] ▶ Step D: Building DTOs (mcq, matching, theory)");
        List<McqDto>         mcqDtos      = buildMcqDtos(mcqResult);
        List<MatchingDto>    matchingDtos = buildMatchingDtos(mcqResult);
        List<TheoryGroupDto> theoryGroups = buildTheoryGroups(theoryAnswers);
        System.out.println("[PDF-SVC] ✔ Step D Done — mcq=" + mcqDtos.size() + ", matching=" + matchingDtos.size() + ", theory=" + theoryGroups.size());

        System.out.println("[PDF-SVC] ▶ Step E: Building Thymeleaf context");
        Context ctx = new Context();
        String firstName      = report != null && report.getUser() != null ? safeStr(report.getUser().getFirstname()) : "";
        String lastName       = report != null && report.getUser() != null ? safeStr(report.getUser().getLastname())  : "";
        String candidateIdStr = report != null && report.getUser() != null ? report.getUser().getUsername().toUpperCase() : "N/A";
        ctx.setVariable("candidateName",   (firstName + " " + lastName).trim().isEmpty() ? "N/A" : (firstName + " " + lastName).trim());
        ctx.setVariable("candidateId",     candidateIdStr);
        ctx.setVariable("courseCode",      report != null && report.getQuiz() != null && report.getQuiz().getCategory() != null ? safeStr(report.getQuiz().getCategory().getCourseCode()) : "N/A");
        ctx.setVariable("courseTitle",     report != null && report.getQuiz() != null && report.getQuiz().getCategory() != null ? safeStr(report.getQuiz().getCategory().getTitle()) : "N/A");
        ctx.setVariable("assessmentTitle", report != null && report.getQuiz() != null ? safeStr(report.getQuiz().getTitle()) : "N/A");
        ctx.setVariable("duration",        duration);
        ctx.setVariable("submissionDate",  submDate);
        ctx.setVariable("refId",           String.format("%06d", quizId));
        ctx.setVariable("generatedDate",   java.time.LocalDate.now().format(DateTimeFormatter.ofPattern("dd MMM yyyy")));

        // Resolve lecturer name from the quiz owner (the user who created the quiz)
        com.exam.model.User quizOwner = report != null && report.getQuiz() != null ? report.getQuiz().getUser() : null;
        String lecturerFirstName = quizOwner != null ? safeStr(quizOwner.getFirstname()) : "";
        String lecturerLastName  = quizOwner != null ? safeStr(quizOwner.getLastname())  : "";
        String lecturerName      = (lecturerFirstName + " " + lecturerLastName).trim();
        ctx.setVariable("lecturerName", lecturerName.isEmpty() ? "N/A" : lecturerName);
        ctx.setVariable("objScore",   fmt(objScore));
        ctx.setVariable("maxObj",     fmt(maxObj));
        ctx.setVariable("objPct",     objPct);
        ctx.setVariable("thScore",    fmt(thScore));
        ctx.setVariable("maxTh",      fmt(maxTh));
        ctx.setVariable("thPct",      thPct);
        ctx.setVariable("totalScore", fmt(total));
        ctx.setVariable("totalMax",   fmt(totalMax));
        ctx.setVariable("totalPct",   totalPct);
        ctx.setVariable("gradeLabel", gradeLabel(totalPct));
        ctx.setVariable("gradeStatus", totalPct >= 50 ? "QUALIFIED" : "BELOW AVERAGE");
        ctx.setVariable("showSectionA", showSectionA && !mcqDtos.isEmpty());
        ctx.setVariable("showSectionB", showSectionB);
        ctx.setVariable("mcqQuestions",  mcqDtos);
        ctx.setVariable("matchingQuestions", matchingDtos);
        ctx.setVariable("showSectionMatching", !matchingDtos.isEmpty());
        ctx.setVariable("theoryGroups",  theoryGroups);
        ctx.setVariable("watermarkBase64", generateDiagonalWatermarkBase64(candidateIdStr));
        System.out.println("[PDF-SVC] ✔ Step E Done");

        // The institution's own logo and name (the bundled UCC crest only while it is still UCC)
        ctx.setVariable("uccLogoBase64", institutionService.logoDataUrl());
        ctx.setVariable("institutionName", institutionService.name());
        ctx.setVariable("institutionSubtitle", institutionService.subtitle());

        System.out.println("[PDF-SVC] ▶ Step F: Rendering Thymeleaf HTML template");
        String html = themeService.recolor(templateEngine.process("exam-report", ctx));
        System.out.println("[PDF-SVC] ✔ Step F Done — HTML length=" + html.length());

        System.out.println("[PDF-SVC] ▶ Step G: Converting HTML to XHTML via Jsoup");
        Document doc = Jsoup.parse(html);
        doc.outputSettings().syntax(Document.OutputSettings.Syntax.xml);
        doc.outputSettings().escapeMode(org.jsoup.nodes.Entities.EscapeMode.xhtml);
        String xhtml = doc.html();
        System.out.println("[PDF-SVC] ✔ Step G Done — XHTML length=" + xhtml.length());

        System.out.println("[PDF-SVC] ▶ Step H: Rendering PDF via Flying Saucer / iText");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ITextRenderer renderer = new ITextRenderer();
        renderer.setDocumentFromString(xhtml);
        renderer.layout();
        renderer.createPDF(out);
        System.out.println("[PDF-SVC] ✔ Step H Done — PDF generated successfully");
        return out.toByteArray();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<McqDto> buildMcqDtos(Map<String, Object> mcqResult) {
        List<Map<String, Object>> results = (List<Map<String, Object>>) mcqResult.getOrDefault("results", List.of());
        List<McqDto> dtos = new ArrayList<>();
        int num = 1;
        for (Map<String, Object> q : results) {
            String qType = String.valueOf(q.getOrDefault("questionType", "MCQ"));
            if ("MATCHING".equals(qType)) continue;
            String   status   = String.valueOf(q.getOrDefault("status", "SKIPPED"));
            String[] correct  = toArr(q.get("correct_answer"));
            String[] selected = toArr(q.get("selectedAnswers"));
            Set<String> cSet = correct  != null ? new HashSet<>(Arrays.asList(correct))  : Set.of();
            Set<String> sSet = selected != null ? new HashSet<>(Arrays.asList(selected)) : Set.of();
            String[] keys    = {"option1","option2","option3","option4"};
            String[] letters = {"A","B","C","D"};
            List<OptionDto> opts = new ArrayList<>();
            if ("FILL_BLANK".equals(qType) || "NUMERIC".equals(qType)) {
                // Typed answers: show what the student wrote and what was accepted
                String given = selected != null && selected.length > 0 && selected[0] != null ? selected[0] : "(no answer)";
                String key = correct == null ? "" : String.join(" / ", correct);
                Object tol = q.get("tolerance");
                if ("NUMERIC".equals(qType) && tol != null && ((Number) tol).doubleValue() > 0) key += " (± " + tol + ")";
                opts.add(new OptionDto("Your answer", given, "CORRECT".equals(status), true));
                opts.add(new OptionDto("Accepted", key, true, false));
                dtos.add(new McqDto(num++, String.valueOf(q.getOrDefault("content", "")),
                        questionImageService.loadForPdf((String) q.get("image"), IMG_MAX_W_PT, IMG_MAX_H_PT), status, opts));
                continue;
            }
            for (int i = 0; i < keys.length; i++) {
                Object v = q.get(keys[i]);
                if (v == null) continue;
                String txt = String.valueOf(v);
                opts.add(new OptionDto(letters[i], txt, cSet.contains(txt), sSet.contains(txt)));
            }
            dtos.add(new McqDto(num++, String.valueOf(q.getOrDefault("content", "")),
                    questionImageService.loadForPdf((String) q.get("image"), IMG_MAX_W_PT, IMG_MAX_H_PT), status, opts));
        }
        return dtos;
    }

    @SuppressWarnings("unchecked")
    private List<MatchingDto> buildMatchingDtos(Map<String, Object> mcqResult) {
        List<Map<String, Object>> results = (List<Map<String, Object>>) mcqResult.getOrDefault("results", List.of());
        List<MatchingDto> dtos = new ArrayList<>();
        int num = 1;
        for (Map<String, Object> q : results) {
            String qType = String.valueOf(q.getOrDefault("questionType", "MCQ"));
            if (!"MATCHING".equals(qType)) {
                if (!"THEORY".equals(qType)) num++; // Keep numbering aligned with all questions
                continue;
            }
            String status = String.valueOf(q.getOrDefault("status", "SKIPPED"));
            int pairsCorrect = (int) Double.parseDouble(String.valueOf(q.getOrDefault("pairsCorrect", 0)));
            int pairsTotal = (int) Double.parseDouble(String.valueOf(q.getOrDefault("pairsTotal", 0)));
            
            List<Map<String, Object>> pairsList = (List<Map<String, Object>>) q.getOrDefault("matchingPairs", List.of());
            List<MatchingPairDto> pairs = new ArrayList<>();
            for (Map<String, Object> p : pairsList) {
                String prompt = safeStr(p.get("prompt"));
                String expected = safeStr(p.get("correctAnswer"));
                String student = safeStr(p.get("studentAnswer"));
                boolean correct = Boolean.parseBoolean(String.valueOf(p.get("correct")));
                pairs.add(new MatchingPairDto(prompt, expected, student, correct));
            }
            dtos.add(new MatchingDto(num++, String.valueOf(q.getOrDefault("content", "")),
                    questionImageService.loadForPdf((String) q.get("image"), IMG_MAX_W_PT, IMG_MAX_H_PT), status, pairsCorrect, pairsTotal, pairs));
        }
        return dtos;
    }

    private List<TheoryGroupDto> buildTheoryGroups(List<Answer> answers) {
        Map<String, List<TheoryAnswerDto>> map = new LinkedHashMap<>();
        for (Answer a : answers) {
            String qNo     = a.getQuesNo() != null ? a.getQuesNo() : "OTHER";
            String prefix  = TheoryGroups.key(a.getQuesNo());
            int    sPct    = a.getMaxMarks() > 0 ? (int) Math.round((a.getScore() / a.getMaxMarks()) * 100) : 0;
            String q       = a.getTheoryQuestion() != null ? a.getTheoryQuestion().getQuestion() : "";
            QuestionImageService.PdfImage qImg = a.getTheoryQuestion() != null
                    ? questionImageService.loadForPdf(a.getTheoryQuestion().getImage(), IMG_MAX_W_PT, IMG_MAX_H_PT) : null;
            List<String> km = a.getKeyMissed() != null ? a.getKeyMissed() : List.of();
            map.computeIfAbsent(prefix, k -> new ArrayList<>())
               .add(new TheoryAnswerDto(qNo, q, qImg, safeStr(a.getStudentAnswer()),
                       fmt(a.getScore()), fmt(a.getMaxMarks()), sPct, km, safeStr(a.getFeedback()), safeStr(a.getLecturerComment())));
        }
        return map.entrySet().stream()
                .map(e -> new TheoryGroupDto(e.getKey(), e.getValue()))
                .collect(Collectors.toList());
    }

    private String[] toArr(Object o) {
        if (o == null) return null;
        if (o instanceof String[]) return (String[]) o;
        if (o instanceof List)     return ((List<?>) o).stream().map(Object::toString).toArray(String[]::new);
        return null;
    }

    private double safeDouble(Object v) { try { return v == null ? 0 : Double.parseDouble(v.toString()); } catch (Exception e) { return 0; } }
    private String safeStr(Object v)    { return v == null ? "" : v.toString(); }
    private int    pct(double g, double m) { return m > 0 ? (int) Math.round(g / m * 100) : 0; }
    private String fmt(double v) { return v == Math.floor(v) ? String.valueOf((int) v) : String.format("%.1f", v); }
    private String formatDuration(int m) { int h = m / 60; int mm = m % 60; return h > 0 ? h + " hr " + mm + " min" : mm + " min"; }
    private String gradeLabel(int p) { return p >= 70 ? "EXCELLENT" : p >= 50 ? "SATISFACTORY" : "BELOW AVERAGE"; }

    private String generateDiagonalWatermarkBase64(String text) {
        try {
            int width = 800;
            int height = 1100;
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2d = image.createGraphics();

            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            // Create a large spaced out string
            StringBuilder spacedText = new StringBuilder();
            for (char c : text.toCharArray()) {
                spacedText.append(c).append(" ");
            }
            String finalText = spacedText.toString().trim();

            int fontSize = 120;
            Font font = new Font("Georgia", Font.BOLD, fontSize);
            g2d.setFont(font);
            FontMetrics fm = g2d.getFontMetrics();
            int textWidth = fm.stringWidth(finalText);

            // Scale down font size if text is too long to fit the diagonal
            while (textWidth > 1000 && fontSize > 20) {
                fontSize -= 4;
                font = new Font("Georgia", Font.BOLD, fontSize);
                g2d.setFont(font);
                fm = g2d.getFontMetrics();
                textWidth = fm.stringWidth(finalText);
            }

            g2d.setColor(new Color(230, 230, 245, 120)); // Light purplish grey, translucent
            
            AffineTransform transform = new AffineTransform();
            transform.translate(width / 2.0, height / 2.0);
            transform.rotate(-Math.PI / 4); // -45 degrees
            g2d.setTransform(transform);

            g2d.drawString(finalText, -textWidth / 2, (fm.getAscent() - fm.getDescent()) / 2);

            g2d.dispose();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "png", baos);
            byte[] imageBytes = baos.toByteArray();
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(imageBytes);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Generates a PDF for the student's semester report card.
     */
    public byte[] generateSemesterReportCardPdf(
            java.util.Map<String, Object> data, String candidateId) throws Exception {

        Context ctx = new Context();
        ctx.setVariable("studentName",  data.get("studentName"));
        ctx.setVariable("username",     data.get("username"));
        ctx.setVariable("programName",  data.get("programName"));
        ctx.setVariable("level",        data.get("level"));
        ctx.setVariable("semester",     data.get("semester"));
        ctx.setVariable("sections",     data.get("sections"));
        ctx.setVariable("courseMarks",  data.get("courseMarks"));
        ctx.setVariable("generatedDate",data.get("generatedDate"));
        ctx.setVariable("sessionName",  data.get("sessionName"));
        ctx.setVariable("gpa",          data.get("gpa"));
        ctx.setVariable("cgpa",         data.get("cgpa"));
        ctx.setVariable("creditUnits",  data.get("creditUnits"));
        ctx.setVariable("watermarkBase64", generateDiagonalWatermarkBase64(candidateId != null ? candidateId : "UCC"));

        applyInstitution(ctx);
        addTermExtras(data);
        ctx.setVariable("semesterName", data.get("semesterName"));
        ctx.setVariable("position",     data.get("position"));
        ctx.setVariable("classSize",    data.get("classSize"));
        ctx.setVariable("remark",       data.get("remark"));
        issueCode(ctx, com.exam.model.academic.DocumentVerification.Type.REPORT_CARD, data, termSummary(data));

        String html  = themeService.recolor(templateEngine.process("semester-report-card", ctx));
        Document doc = Jsoup.parse(html);
        doc.outputSettings().syntax(Document.OutputSettings.Syntax.xml);
        doc.outputSettings().escapeMode(org.jsoup.nodes.Entities.EscapeMode.xhtml);
        String xhtml = doc.html();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ITextRenderer renderer = new ITextRenderer();
        renderer.setDocumentFromString(xhtml);
        renderer.layout();
        renderer.createPDF(out);
        return out.toByteArray();
    }

    // ── Institution, verification codes, position and remarks on academic documents ──

    private void applyInstitution(Context ctx) {
        ctx.setVariable("uccLogoBase64", institutionService.logoDataUrl());
        ctx.setVariable("institutionName", institutionService.name());
        ctx.setVariable("institutionShort", institutionService.shortName());
        ctx.setVariable("institutionSubtitle", institutionService.subtitle());
        ctx.setVariable("isSchool", institutionService.isSchool());
        ctx.setVariable("terms", institutionService.terms());
        // Printed inside a CSS string in the page footer
        ctx.setVariable("pageFooterName", institutionService.name().replaceAll("[\"\\\\]", ""));
        ctx.setVariable("verificationCode", null);
        ctx.setVariable("verifyUrl", null);
    }

    /** Adds semesterName, position/classSize (when switched on) and the term's remarks to a report's data. */
    private void addTermExtras(Map<String, Object> data) {
        data.put("semesterName", institutionService.semesterName(data.get("semester")));
        Long studentId = toLong(data.get("studentId"));
        Long programId = toLong(data.get("programId"));
        if (studentId == null) return;
        String level = Objects.toString(data.get("level"), null);
        Integer semester = toLong(data.get("semester")) == null ? null : toLong(data.get("semester")).intValue();
        String session = (String) data.get("sessionName");
        try {
            if (institutionService.showPosition()) {
                int[] pos = marksEntryService.classPosition(studentId, programId, level, semester, session);
                if (pos != null) {
                    data.put("position", ordinal(pos[0]));
                    data.put("classSize", pos[1]);
                }
            }
            List<Long> sheetIds = new ArrayList<>(marksEntryService.sheetIdsForTerm(programId, level, semester, session));
            Long sheetId = toLong(data.get("sheetId"));
            if (sheetId != null && !sheetIds.contains(sheetId)) sheetIds.add(sheetId);
            termRemarkService.forStudent(sheetIds, studentId).ifPresent(r -> data.put("remark", r));
        } catch (Exception e) {
            System.err.println("[PDF-SVC] position/remarks skipped: " + e.getMessage());
        }
    }

    private String termSummary(Map<String, Object> data) {
        Map<String, String> terms = institutionService.terms();
        StringBuilder sb = new StringBuilder();
        sb.append(terms.get("level")).append(' ').append(Objects.toString(data.get("level"), "-"))
          .append(", ").append(institutionService.semesterName(data.get("semester")));
        if (data.get("sessionName") != null) sb.append(' ').append(data.get("sessionName"));
        if (data.get("courseMarks") instanceof List<?> marks)
            sb.append(" · ").append(marks.size()).append(' ').append(marks.size() == 1 ? terms.get("course").toLowerCase() : terms.get("courses").toLowerCase());
        if (data.get("gpa") != null) sb.append(" · GPA ").append(data.get("gpa"));
        if (data.get("position") != null) sb.append(" · Position ").append(data.get("position")).append(" of ").append(data.get("classSize"));
        return sb.toString();
    }

    private void issueCode(Context ctx, com.exam.model.academic.DocumentVerification.Type type, Map<String, Object> who, String summary) {
        try {
            verificationService.issue(type, toLong(who.get("studentId")), Objects.toString(who.get("studentName"), null),
                    Objects.toString(who.get("username"), null), Objects.toString(who.get("programName"), null), summary)
                .ifPresent(issued -> {
                    ctx.setVariable("verificationCode", issued.code());
                    ctx.setVariable("verifyUrl", issued.url());
                });
        } catch (Exception e) {
            // A document without a code is better than no document
            System.err.println("[PDF-SVC] verification code not issued: " + e.getMessage());
        }
    }

    static String ordinal(int n) {
        int mod100 = n % 100;
        String suffix = (mod100 >= 11 && mod100 <= 13) ? "th" : switch (n % 10) { case 1 -> "st"; case 2 -> "nd"; case 3 -> "rd"; default -> "th"; };
        return n + suffix;
    }

    private static Long toLong(Object o) {
        if (o instanceof Number n) return n.longValue();
        try { return o == null ? null : Long.parseLong(o.toString().trim()); } catch (NumberFormatException e) { return null; }
    }

    /**
     * Generates the student's academic transcript (see AcademicRecordService#transcript).
     */
    public byte[] generateTranscriptPdf(java.util.Map<String, Object> transcript, String candidateId) throws Exception {
        Context ctx = new Context();
        ctx.setVariable("t", transcript);
        ctx.setVariable("generatedDate", java.time.LocalDate.now().format(DateTimeFormatter.ofPattern("dd MMM yyyy")));
        ctx.setVariable("watermarkBase64", generateDiagonalWatermarkBase64(candidateId != null ? candidateId : "UCC"));
        applyInstitution(ctx);
        StringBuilder summary = new StringBuilder("CGPA ").append(Objects.toString(transcript.get("cgpa"), "-"))
                .append(" · ").append(Objects.toString(transcript.get("creditsEarned"), "0")).append(" credit units earned");
        if (transcript.get("degreeClass") != null) summary.append(" · ").append(transcript.get("degreeClass"));
        if (Boolean.TRUE.equals(transcript.get("includesApproved"))) summary.append(" · includes approved results not yet published");
        Map<String, Object> who = new HashMap<>();
        who.put("studentId", transcript.get("studentId"));
        who.put("studentName", transcript.get("studentName"));
        who.put("username", transcript.get("username"));
        who.put("programName", transcript.get("program"));
        issueCode(ctx, com.exam.model.academic.DocumentVerification.Type.TRANSCRIPT, who, summary.toString());

        String html  = themeService.recolor(templateEngine.process("transcript", ctx));
        Document doc = Jsoup.parse(html);
        doc.outputSettings().syntax(Document.OutputSettings.Syntax.xml);
        doc.outputSettings().escapeMode(org.jsoup.nodes.Entities.EscapeMode.xhtml);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ITextRenderer renderer = new ITextRenderer();
        renderer.setDocumentFromString(doc.html());
        renderer.layout();
        renderer.createPDF(out);
        return out.toByteArray();
    }

    /**
     * Generates a combined PDF for all the student's semester report cards.
     */
    public byte[] generateCombinedSemesterReportCardPdf(
            List<java.util.Map<String, Object>> allData, String candidateId) throws Exception {

        Context ctx = new Context();
        ctx.setVariable("allReports", allData);
        ctx.setVariable("generatedDate", java.time.LocalDate.now().format(DateTimeFormatter.ofPattern("dd MMM yyyy")));
        ctx.setVariable("watermarkBase64", generateDiagonalWatermarkBase64(candidateId != null ? candidateId : "UCC"));

        applyInstitution(ctx);
        for (java.util.Map<String, Object> report : allData) addTermExtras(report);
        if (!allData.isEmpty()) {
            java.util.Map<String, Object> last = allData.get(allData.size() - 1);
            String summary = allData.size() + " " + (institutionService.isSchool() ? "term" : "semester") + (allData.size() == 1 ? "" : "s")
                    + (last.get("cgpa") != null ? " · latest CGPA " + last.get("cgpa") : "")
                    + " · up to " + termSummary(last);
            issueCode(ctx, com.exam.model.academic.DocumentVerification.Type.CUMULATIVE_REPORT, allData.get(0), summary);
        }

        String html  = themeService.recolor(templateEngine.process("combined-semester-report-card", ctx));
        Document doc = Jsoup.parse(html);
        doc.outputSettings().syntax(Document.OutputSettings.Syntax.xml);
        doc.outputSettings().escapeMode(org.jsoup.nodes.Entities.EscapeMode.xhtml);
        String xhtml = doc.html();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ITextRenderer renderer = new ITextRenderer();
        renderer.setDocumentFromString(xhtml);
        renderer.layout();
        renderer.createPDF(out);
        return out.toByteArray();
    }
}

