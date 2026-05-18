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
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

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
        List<TheoryGroupDto> theoryGroups = buildTheoryGroups(theoryAnswers);

        // 10. Thymeleaf context
        Context ctx = new Context();
        String firstName = report != null && report.getUser() != null ? safeStr(report.getUser().getFirstname()) : "";
        String lastName  = report != null && report.getUser() != null ? safeStr(report.getUser().getLastname())  : "";
        ctx.setVariable("candidateName",   (firstName + " " + lastName).trim().isEmpty() ? "N/A" : (firstName + " " + lastName).trim());
        ctx.setVariable("candidateId",     report != null && report.getUser() != null ? report.getUser().getUsername().toUpperCase() : "N/A");
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
        ctx.setVariable("theoryGroups",  theoryGroups);

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
}
