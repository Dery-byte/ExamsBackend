package com.exam.service.llm;

import com.exam.DTO.QuizEvaluationResponse;
import com.exam.model.exam.*;
import com.exam.model.User;
import com.exam.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Shared persistence + response-building logic reused by every LLM strategy.
 * <p>
 * Strategies delegate to {@link #buildResponse} after they have obtained the
 * raw {@link QuestionEvaluationResult} list from their respective AI provider.
 */
@Component
public class EvaluationPersistenceHelper {

    private static final Logger log = LoggerFactory.getLogger(EvaluationPersistenceHelper.class);

    @Autowired private AnswerRepository           answerRepository;
    @Autowired private TheoryQuestionsRepository  theoryQuestionsRepository;
    @Autowired private QuizRepository             quizRepository;
    @Autowired private ReportRepository           reportRepository;
    @Autowired private UserRepository             userRepository;

    /**
     * Persists scores / answers to the database and assembles the full API response.
     *
     * @param results          raw evaluation results from the LLM
     * @param submissions      the original parsed submissions (used to map quizId)
     * @param user             the authenticated student
     * @param evaluationMethod the provider name to record on the Report (e.g. "GPT")
     */
    @Transactional
    public QuizEvaluationResponse buildResponse(List<QuestionEvaluationResult> results,
                                                List<QuestionSubmission> submissions,
                                                User user,
                                                String evaluationMethod) {

        // ── 1. Tally scores ───────────────────────────────────────────────────
        double totalScore    = 0;
        double totalMaxMarks = 0;
        int    successful    = 0;
        for (QuestionEvaluationResult r : results) {
            if (!r.getFeedback().startsWith("Evaluation failed")
                    && !r.getFeedback().startsWith("Authentication failed")) {
                totalScore    += r.getScore();
                totalMaxMarks += r.getMaxMarks();
                successful++;
            }
        }

        // ── 2. Resolve quiz & user from DB ────────────────────────────────────
        Long quizId      = Long.valueOf(submissions.get(0).getQuizId());
        Quiz quiz        = quizRepository.findById(quizId)
                           .orElseThrow(() -> new RuntimeException("Quiz not found: " + quizId));
        User managedUser = userRepository.findById(user.getId())
                           .orElseThrow(() -> new RuntimeException("User not found"));

        // ── 3. Upsert Report ──────────────────────────────────────────────────
        Report report = reportRepository.findByUserAndQuiz(managedUser, quiz).orElse(new Report());
        report.setUser(managedUser);
        report.setQuiz(quiz);
        report.setMaxScoreSectionB(BigDecimal.valueOf(totalMaxMarks));
        report.setMarksB(BigDecimal.valueOf(totalScore));
        report.setProgress("Completed");
        report.setEvaluationMethod(evaluationMethod);
        report.setSubmissionDate(LocalDateTime.now());
        if (report.getId() == null) {
            report.setMarks(BigDecimal.valueOf(0));
        }
        report.calculatePercentageAndGrade();
        Report savedReport = reportRepository.saveAndFlush(report);

        // ── 4. Persist individual answers ─────────────────────────────────────
        int savedAnswers = 0;
        List<QuestionEvaluationResult> included = new ArrayList<>();

        for (QuestionEvaluationResult r : results) {
            if (r.getFeedback().startsWith("Evaluation failed")
                    || r.getFeedback().startsWith("Authentication failed")) {
                included.add(r);
                continue;
            }
            try {
                Long tqid = Long.valueOf(r.getTqid());
                TheoryQuestions tq = theoryQuestionsRepository.findById(tqid)
                        .orElseThrow(() -> new RuntimeException("Theory question not found: " + tqid));

                Answer answer = new Answer();
                answer.setQuesNo(r.getQuestionNumber());
                answer.setStudentAnswer(r.getStudentAnswer());
                answer.setScore(r.getScore());
                answer.setMaxMarks(r.getMaxMarks());
                answer.setFeedback(r.getFeedback());
                answer.setKeyMissed(r.getKeyMissed());
                answer.setUser(managedUser);
                answer.setQuiz(quiz);
                answer.setTheoryQuestion(tq);
                answer.setReport(savedReport);
                answerRepository.saveAndFlush(answer);
                savedAnswers++;
                included.add(r);

            } catch (Exception e) {
                log.error("Failed to save answer for tqid {}: {}", r.getTqid(), e.getMessage(), e);
                r.setFeedback(r.getFeedback() + " [Note: Failed to save to database]");
                included.add(r);
            }
        }

        // ── 5. Build response ─────────────────────────────────────────────────
        QuizEvaluationResponse response = new QuizEvaluationResponse();
        response.setReportId(savedReport.getId());
        response.setUserId(user.getId());
        response.setUsername(user.getUsername());
        response.setQuizId(quizId);
        response.setResults(included);

        QuizEvaluationResponse.Summary summary = new QuizEvaluationResponse.Summary();
        summary.setTotalScore(totalScore);
        summary.setTotalMaxMarks(totalMaxMarks);
        summary.setPercentage(savedReport.getPercentage() != null ? savedReport.getPercentage() : 0.0);
        summary.setGrade(savedReport.getGrade() != null ? savedReport.getGrade() : "N/A");
        summary.setQuestionsAnswered(successful);
        summary.setAnswersSaved(savedAnswers);
        response.setSummary(summary);
        return response;
    }

    // ═══════════════════ JSON parsing helpers (shared) ═══════════════════════

    public List<QuestionSubmission> parseSubmissions(GeminiRequest request) {
        return request.getContents().stream()
                .flatMap(content -> content.getParts().stream())
                .map(part -> {
                    String text = part.getText();
                    String marksStr = extractField(text, "Marks:", "Criteria:")
                                      .replaceAll("[^\\d.]", "").trim();
                    double marks = marksStr.isEmpty() ? 10.0 : Double.parseDouble(marksStr);
                    return new QuestionSubmission(
                            extractField(text, "quizId ", ":"),
                            extractField(text, "tqid ",   ":"),
                            extractField(text, "Question Number ", ":"),
                            extractField(text, ":", "Answer:"),
                            extractField(text, "Answer:", "Marks:"),
                            marks,
                            extractField(text, "Criteria:", null)
                    );
                })
                .collect(Collectors.toList());
    }

    public String buildEvaluationPrompt(QuestionSubmission s) {
        return String.format(
            "YOU ARE AN AUTOMATED EXAM GRADING SYSTEM.\n\n" +
            "STRICT RULES (NO EXCEPTIONS):\n" +
            "1. If the STUDENT ANSWER is empty, blank, '.', '-', 'N/A', or contains only random or meaningless text, THEN:\n" +
            "   - score MUST be exactly 0\n" +
            "   - feedback MUST say that no valid answer was provided\n" +
            "   - keyMissed MUST list the expected key points\n" +
            "2. Do NOT infer meaning from missing, unclear, or nonsense answers.\n" +
            "3. Partial credit is allowed ONLY if at least one correct, relevant point is clearly stated.\n" +
            "4. ZERO is a valid and expected score. Do not avoid zero.\n" +
            "5. Do NOT reward effort, formatting, or placeholders.\n\n" +
            "OUTPUT FORMAT (JSON ONLY, NO EXTRA TEXT):\n" +
            "{\n" +
            "  \"score\": number (0 to %.2f),\n" +
            "  \"feedback\": string,\n" +
            "  \"keyMissed\": string[]\n" +
            "}\n\n" +
            "QUESTION: %s\n" +
            "CRITERIA: %s\n" +
            "MAX MARKS: %.2f\n" +
            "STUDENT ANSWER: %s",
            s.getMaxMarks(), s.getQuestion(), s.getCriteria(), s.getMaxMarks(), s.getStudentAnswer()
        );
    }

    public QuestionEvaluationResult createFailedResult(QuestionSubmission s, int maxRetries, Exception e) {
        String msg = "Evaluation failed after " + maxRetries + " attempts: " + e.getMessage();
        return new QuestionEvaluationResult(
                s.getQuizId(), s.getTqid(), s.getQuestionNumber(),
                s.getQuestion(), s.getStudentAnswer(),
                0, s.getMaxMarks(), msg, Collections.emptyList());
    }

    // ──────────────── raw JSON extractors ────────────────────────────────────

    public double extractDoubleValue(String json, String key) {
        int start = json.indexOf("\"" + key + "\":") + key.length() + 3;
        int end   = findNextDelimiter(json, start);
        String num = json.substring(start, end).trim().replaceAll("[^\\d.-]", "");
        if (num.endsWith(".")) num = num.substring(0, num.length() - 1);
        return Double.parseDouble(num);
    }

    public String extractStringValue(String json, String key) {
        int start = json.indexOf("\"" + key + "\":\"") + key.length() + 4;
        int end   = json.indexOf("\"", start);
        return json.substring(start, end);
    }

    public List<String> extractStringArray(String json, String key) {
        try {
            int start = json.indexOf("\"" + key + "\":") + key.length() + 3;
            int end   = json.indexOf("]", start) + 1;
            String arr = json.substring(start, end).trim();
            if ("[]".equals(arr)) return Collections.emptyList();
            return Arrays.asList(arr.replaceAll("[\\[\\]\"]", "").split(",\\s*"));
        } catch (Exception ex) {
            return Collections.emptyList();
        }
    }

    private int findNextDelimiter(String json, int start) {
        int comma = json.indexOf(",", start);
        int brace = json.indexOf("}", start);
        return comma == -1 ? brace : Math.min(comma, brace);
    }

    private String extractField(String text, String start, String end) {
        int s = text.indexOf(start) + start.length();
        int e = end != null ? text.indexOf(end, s) : text.length();
        return text.substring(s, e).trim();
    }
}
