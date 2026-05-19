package com.exam.service;

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
        private String status;
        private int pairsCorrect;
        private int pairsTotal;
        private List<MatchingPairDto> pairs;
    }

    @Getter @AllArgsConstructor
    public static class TheoryAnswerDto {
        private String quesNo;
        private String question;
        private String studentAnswer;
        private String score;
        private String maxMarks;
        private int    scorePct;
        private List<String> keyMissed;
    }

    @Getter @AllArgsConstructor
    public static class TheoryGroupDto {
        private String prefix;
        private List<TheoryAnswerDto> questions;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public byte[] generateReportPdf(Long quizId, Long userId) throws Exception {

        // 1. Base report (scores, candidate, quiz meta)
        Report report = reportRepository.findByUser_idAndQuiz_qId(userId.intValue(), quizId);

        // 2. MCQ results with per-question status
        Map<String, Object> mcqResult = reportService.getStudentQuizResult(quizId, userId);

        // 3. Theory answers for this user only
        List<Answer> theoryAnswers = answerRepository.findByQuiz_qId(quizId).stream()
                .filter(a -> a.getUser() != null && userId.equals(a.getUser().getId()))
                .collect(Collectors.toList());

        // 4. Time allowed for theory section
        List<NumberOfTheoryToAnswer> theoryConfig = numberOfTheoryToAnswerService.findByQuizId(quizId);
        double theoryMins = theoryConfig.isEmpty() ? 0 : theoryConfig.get(0).getTimeAllowed();

        // 5. Scores
        double objScore   = report != null && report.getMarks() != null        ? report.getMarks().doubleValue()          : 0;
        double maxObj     = report != null && report.getQuiz() != null         ? safeDouble(report.getQuiz().getMaxMarks()) : 0;
        double thScore    = theoryAnswers.stream().mapToDouble(Answer::getScore).sum();
        double maxTh      = report != null && report.getMaxScoreSectionB() != null ? report.getMaxScoreSectionB().doubleValue() : 0;
        double total      = objScore + thScore;
        double totalMax   = maxObj + maxTh;
        int    totalPct   = pct(total, totalMax);
        int    objPct     = pct(objScore, maxObj);
        int    thPct      = pct(thScore, maxTh);

        // 6. Flags
        String  qt           = report != null && report.getQuiz() != null ? safeStr(report.getQuiz().getQuizType()) : "";
        boolean showSectionA = !"THEORY".equals(qt);
        boolean showSectionB = !"OBJ".equals(qt) && !theoryAnswers.isEmpty();

        // 7. Duration
        double objMins = report != null && report.getQuiz() != null ? safeDouble(report.getQuiz().getQuizTime()) : 0;
        String duration = formatDuration((int)(objMins + theoryMins));

        // 8. Submission date
        String submDate = "N/A";
        if (report != null && report.getSubmissionDate() != null) {
            submDate = report.getSubmissionDate().format(DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm"));
        }

        // 9. Build DTOs
        List<McqDto>        mcqDtos      = buildMcqDtos(mcqResult);
        List<MatchingDto>   matchingDtos = buildMatchingDtos(mcqResult);
        List<TheoryGroupDto> theoryGroups = buildTheoryGroups(theoryAnswers);

        // 10. Thymeleaf context
        Context ctx = new Context();
        String firstName = report != null && report.getUser() != null ? safeStr(report.getUser().getFirstname()) : "";
        String lastName  = report != null && report.getUser() != null ? safeStr(report.getUser().getLastname())  : "";
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
        ctx.setVariable("gradeStatus", totalPct >= 50 ? "QUALIFIED" : "REVIEW");
        ctx.setVariable("showSectionA", showSectionA && !mcqDtos.isEmpty());
        ctx.setVariable("showSectionB", showSectionB);
        ctx.setVariable("mcqQuestions",  mcqDtos);
        ctx.setVariable("matchingQuestions", matchingDtos);
        ctx.setVariable("showSectionMatching", !matchingDtos.isEmpty());
        ctx.setVariable("theoryGroups",  theoryGroups);
        ctx.setVariable("watermarkBase64", generateDiagonalWatermarkBase64(candidateIdStr));

        try {
            ClassPathResource imgFile = new ClassPathResource("static/images/ucc-logo.png");
            byte[] bytes = org.springframework.util.StreamUtils.copyToByteArray(imgFile.getInputStream());
            String base64Img = Base64.getEncoder().encodeToString(bytes);
            ctx.setVariable("uccLogoBase64", "data:image/png;base64," + base64Img);
        } catch (Exception e) {
            ctx.setVariable("uccLogoBase64", "");
        }

        // 11. Render HTML with Thymeleaf
        String html = templateEngine.process("exam-report", ctx);

        // 12. Convert to XHTML with Jsoup (required by Flying Saucer)
        Document doc = Jsoup.parse(html);
        doc.outputSettings().syntax(Document.OutputSettings.Syntax.xml);
        String xhtml = doc.html();

        // 13. PDF with Flying Saucer
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ITextRenderer renderer = new ITextRenderer();
        renderer.setDocumentFromString(xhtml);
        renderer.layout();
        renderer.createPDF(out);
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
            for (int i = 0; i < keys.length; i++) {
                Object v = q.get(keys[i]);
                if (v == null) continue;
                String txt = String.valueOf(v);
                opts.add(new OptionDto(letters[i], txt, cSet.contains(txt), sSet.contains(txt)));
            }
            dtos.add(new McqDto(num++, String.valueOf(q.getOrDefault("content", "")), status, opts));
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
            dtos.add(new MatchingDto(num++, String.valueOf(q.getOrDefault("content", "")), status, pairsCorrect, pairsTotal, pairs));
        }
        return dtos;
    }

    private List<TheoryGroupDto> buildTheoryGroups(List<Answer> answers) {
        Map<String, List<TheoryAnswerDto>> map = new LinkedHashMap<>();
        for (Answer a : answers) {
            String qNo     = a.getQuesNo() != null ? a.getQuesNo() : "OTHER";
            String prefix  = qNo.toUpperCase().matches("Q\\d+.*")
                    ? qNo.toUpperCase().replaceAll("(Q\\d+).*", "$1") : "OTHER";
            int    sPct    = a.getMaxMarks() > 0 ? (int) Math.round((a.getScore() / a.getMaxMarks()) * 100) : 0;
            String q       = a.getTheoryQuestion() != null ? a.getTheoryQuestion().getQuestion() : "";
            List<String> km = a.getKeyMissed() != null ? a.getKeyMissed() : List.of();
            map.computeIfAbsent(prefix, k -> new ArrayList<>())
               .add(new TheoryAnswerDto(qNo, q, safeStr(a.getStudentAnswer()),
                       fmt(a.getScore()), fmt(a.getMaxMarks()), sPct, km));
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
    private String gradeLabel(int p) { return p >= 70 ? "EXCELLENT" : p >= 50 ? "SATISFACTORY" : "NEEDS IMPROVEMENT"; }

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
}
