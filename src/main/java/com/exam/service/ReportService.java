package com.exam.service;

import com.exam.DTO.ReportDTO;
import com.exam.model.AnswerStatus;
import com.exam.model.User;
import com.exam.model.exam.*;
import com.exam.repository.AnswerRepository;
import com.exam.repository.ReportRepository;
import com.exam.repository.StudentAnswerRepository;
import com.exam.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Service;

import java.security.Principal;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ReportService {
    @Autowired
    @Lazy
    private ReportRepository reportRepository;
    @Autowired
    private AnswerRepository answerRepository;
    @Autowired
    private QuizService quizService;
    @Autowired
    private QuestionsService questionsService;
    @Autowired
    private StudentAnswerRepository studentAnswerRepository;

    @Autowired
    private UserRepository userRepository;


    public Map<String, Object> getQuizReportsWithAnswers(Long quizId) {
        // Get all reports for this quiz
        List<Report> reports = reportRepository.findByQuiz_qId(quizId);
        // Get all answers for this quiz
        List<Answer> answers = answerRepository.findByQuiz_qId(quizId);

        // Group answers by report
        Map<Long, List<Answer>> answersByReport = answers.stream()
                .filter(a -> a.getReport() != null)
                .collect(Collectors.groupingBy(a -> a.getReport().getId()));

        // Build response
        List<Map<String, Object>> reportData = reports.stream()
                .map(report -> {
                    Map<String, Object> data = new HashMap<>();
                    data.put("reportId", report.getId());
                    data.put("userId", report.getUser().getId());
                    data.put("username", report.getUser().getUsername());
                    data.put("totalScore", report.getMarks());
                    data.put("totalMaxMarks", report.getMarksB());
                    data.put("percentage", report.getPercentage());
                    data.put("grade", report.getGrade());
                    data.put("submissionDate", report.getSubmissionDate());

                    // Add answers for this report
                    List<Answer> reportAnswers = answersByReport.getOrDefault(report.getId(), new ArrayList<>());
                    data.put("answers", reportAnswers.stream()
                            .map(this::createAnswerDTO)
                            .collect(Collectors.toList()));
                    data.put("answerCount", reportAnswers.size());

                    return data;
                })
                .collect(Collectors.toList());

        Map<String, Object> result = new HashMap<>();
        result.put("quizId", quizId);
        result.put("totalReports", reports.size());
        result.put("totalAnswers", answers.size());
        result.put("reports", reportData);

        return result;
    }

    /**
     * Get specific report with all its answers
     */
    public Map<String, Object> getReportWithAnswers(Long reportId) {
        Report report = reportRepository.findById(reportId)
                .orElseThrow(() -> new RuntimeException("Report not found: " + reportId));

        List<Answer> answers = answerRepository.findByReport_Id(reportId);

        Map<String, Object> result = new HashMap<>();
        result.put("reportId", report.getId());
        result.put("userId", report.getUser().getId());
        result.put("username", report.getUser().getUsername());
        result.put("quizId", report.getQuiz().getqId());
        result.put("quizTitle", report.getQuiz().getTitle());
        result.put("totalScore", report.getMarks());
        result.put("totalMaxMarks", report.getMarksB());
        result.put("percentage", report.getPercentage());
        result.put("grade", report.getGrade());
        result.put("submissionDate", report.getSubmissionDate());
        result.put("evaluationMethod", report.getEvaluationMethod());
        result.put("answers", answers.stream()
                .map(this::createAnswerDTO)
                .collect(Collectors.toList()));
        result.put("answerCount", answers.size());

        return result;
    }

    /**
     * Get all reports for a user with quiz ID filter
     */
    public List<Map<String, Object>> getUserReportsForQuiz(Long userId, Long quizId) {
        List<Report> reports = reportRepository.findByUser_IdAndQuiz_qId(userId, quizId);

        return reports.stream()
                .map(report -> {
                    List<Answer> answers = answerRepository.findByReport_Id(report.getId());

                    Map<String, Object> data = new HashMap<>();
                    data.put("reportId", report.getId());
                    data.put("totalScore", report.getMarks());
                    data.put("totalMaxMarks", report.getMarksB());
                    data.put("percentage", report.getPercentage());
                    data.put("grade", report.getGrade());
//                    data.put("QuesNo", report)
                    data.put("submissionDate", report.getSubmissionDate());
                    data.put("answers", answers.stream()
                            .map(this::createAnswerDTO)
                            .collect(Collectors.toList()));

                    return data;
                })
                .collect(Collectors.toList());
    }

    /**
     * Get latest report for user and quiz with answers
     */
    public Map<String, Object> getLatestReportForQuiz(Long userId, Long quizId) {
        Optional<Report> reportOpt = reportRepository.findTopByUser_IdAndQuiz_qIdOrderBySubmissionDateDesc(userId, quizId);

        if (reportOpt.isEmpty()) {
            throw new RuntimeException("No report found for user " + userId + " and quiz " + quizId);
        }

        Report report = reportOpt.get();
        List<Answer> answers = answerRepository.findByReport_Id(report.getId());

        Map<String, Object> result = new HashMap<>();
        result.put("reportId", report.getId());
        result.put("userId", userId);
        result.put("quizId", quizId);
        result.put("totalScore", report.getMarks());
        result.put("percentage", report.getPercentage());
        result.put("grade", report.getGrade());
        result.put("submissionDate", report.getSubmissionDate());
        result.put("isLatest", true);
        result.put("answers", answers.stream()
                .map(this::createAnswerDTO)
                .collect(Collectors.toList()));

        return result;
    }

    // Helper method
    private Map<String, Object> createAnswerDTO(Answer answer) {
        Map<String, Object> dto = new HashMap<>();
        dto.put("quesNo", answer.getQuesNo());
        dto.put("answerId", answer.getAnswerId());
        dto.put("questionId", answer.getTheoryQuestion().getTqId());
        dto.put("questionText", answer.getTheoryQuestion().getQuestion());
        dto.put("studentAnswer", answer.getStudentAnswer());
        dto.put("score", answer.getScore());
        dto.put("maxMarks", answer.getMaxMarks());
        dto.put("percentage", answer.getMaxMarks() > 0
                ? (answer.getScore() / answer.getMaxMarks()) * 100 : 0);
        dto.put("feedback", answer.getFeedback());
        dto.put("keyMissed", answer.getKeyMissed());
        return dto;
    }
































































    public  Report userQuizIDs(Long rid){
        return this.reportRepository.findById(rid).get();
    }
    public List<Report> getUserIdAndQuizId(){
        return this.reportRepository.findAll();
    }
    public Report addReport(Report report){
        return reportRepository.save(report);
    }

    public List<Report> getReportByUserAndType(Optional<User> user, Optional<Quiz> quiz){
        return reportRepository.findByUserAndQuiz(user,quiz);
    }


    //report By Quiz
    public List<Report> reportByQuiz_Id(Long quiz){
        return reportRepository.findByQuiz_qId(quiz);
    }
    ///Report By User
    public List<Report> reportByUser_id(User user){
        return reportRepository.findByUser(user);
    }
    public List<Report> findReportsByUserAndQuiz(User user, Quiz quiz) {
        return reportRepository.findByUserAndQuiz(Optional.ofNullable(user), Optional.ofNullable(quiz));
    }

    public Report findByUserAndQuiz(Integer id, Long quizId) {
        return reportRepository.findByUser_idAndQuiz_qId(id,quizId);
    }













    /**
     * Fetches a student's quiz results with marks, correctness, and selected answers.
     */
//    public Map<String, Object> getStudentQuizResult(Long quizId, Long studentId) {
//        Quiz quiz = quizService.getQuiz(quizId);
//        if (quiz == null) throw new RuntimeException("Quiz not found");
//
//        // Fetch all questions of the quiz
//        List<Questions> questions = questionsService.getQuestionsByQuizId(quizId);
//        // Fetch all student answers for this quiz
//        List<StudentAnswer> answers = studentAnswerRepository.findByStudentAndQuiz(studentId, quizId);
//        Map<Long, StudentAnswer> answerMap = answers.stream()
//                .collect(Collectors.toMap(a -> a.getQuestion().getQuesId(), a -> a));
//        double marksGot = 0.0;
//        int correctAnswers = 0;
//        int attempted = 0;
//        double maxMarks = quiz.getMaxMarks();
//        List<Map<String, Object>> resultList = new ArrayList<>();
//        for (Questions question : questions) {
//            StudentAnswer studentAnswer = answerMap.get(question.getQuesId());
//            String[] selected = studentAnswer != null ? studentAnswer.getSelectedOptions() : null;
//            List<String> correctList = question.getcorrect_answer() != null
//                    ? Arrays.asList(question.getcorrect_answer())
//                    : new ArrayList<>();
//            List<String> selectedList = selected != null ? Arrays.asList(selected) : new ArrayList<>();
//
//            // -------------------------------
//            // Compute AnswerStatus
//            // -------------------------------
//            AnswerStatus status;
//            if (selectedList.isEmpty()) {
//                status = AnswerStatus.SKIPPED;
//            } else {
//                attempted++;
//                Set<String> correctSet = new HashSet<>(correctList);
//                Set<String> selectedSet = new HashSet<>(selectedList);
//                boolean hasAnyCorrect = selectedSet.stream().anyMatch(correctSet::contains);
//                boolean hasWrong = selectedSet.stream().anyMatch(a -> !correctSet.contains(a));
//                if (!hasAnyCorrect) {
//                    status = AnswerStatus.WRONG;
//                } else if (!hasWrong && selectedSet.size() == correctSet.size()) {
//                    status = AnswerStatus.CORRECT;
//                    correctAnswers++;
//                    marksGot += maxMarks / questions.size();
//                } else {
//                    status = AnswerStatus.PARTIAL;
//                }
//            }
//
//            // -------------------------------
//            // Prepare response map
//            // -------------------------------
//            Map<String, Object> questionMap = new HashMap<>();
//            questionMap.put("quesId", question.getQuesId());
//            questionMap.put("content", question.getContent());
//            questionMap.put("image", question.getImage());
//            questionMap.put("option1", question.getOption1());
//            questionMap.put("option2", question.getOption2());
//            questionMap.put("option3", question.getOption3());
//            questionMap.put("option4", question.getOption4());
//            questionMap.put("correct_answer", question.getcorrect_answer());
//            questionMap.put("selectedAnswers", selected);
//            questionMap.put("status", status.name()); // CORRECT / PARTIAL / WRONG / SKIPPED
//            resultList.add(questionMap);
//        }
//
//        // -------------------------------
//        // Build final response
//        // -------------------------------
//        Map<String, Object> response = new HashMap<>();
//        response.put("studentId", studentId);
//        response.put("quizId", quizId);
//        response.put("marksGot", marksGot);
//        response.put("correctAnswers", correctAnswers);
//        response.put("attempted", attempted);
//        response.put("maxMarks", maxMarks);
//        response.put("results", resultList);
//        return response;
//    }


    public Map<String, Object> getStudentQuizResult(Long quizId, Long studentId) {

        Quiz quiz = quizService.getQuiz(quizId);
        if (quiz == null) throw new RuntimeException("Quiz not found");

        List<Questions>     questions = questionsService.getQuestionsByQuizId(quizId);
        List<StudentAnswer> answers   = studentAnswerRepository.findByStudentAndQuiz(studentId, quizId);

        // Map questionId → StudentAnswer for O(1) lookup
        Map<Long, StudentAnswer> answerMap = answers.stream()
                .collect(Collectors.toMap(a -> a.getQuestion().getQuesId(), a -> a));

        double maxMarks = Optional.ofNullable(quiz.getMaxMarks()).orElse(0.0);

        int    totalQs         = questions.size();
        double markPerQuestion = totalQs > 0 ? maxMarks / totalQs : 0;

        double marksGot       = 0.0;
        int    correctAnswers = 0;   // fully-correct question count
        int    attempted      = 0;

        List<Map<String, Object>> resultList = new ArrayList<>();

        for (Questions question : questions) {

            StudentAnswer studentAnswer = answerMap.get(question.getQuesId());
            String[]      selected      = studentAnswer != null ? studentAnswer.getSelectedOptions() : null;
            List<String>  selectedList  = selected != null ? Arrays.asList(selected) : new ArrayList<>();

            QuestionType type = question.getQuestionType() != null
                    ? question.getQuestionType()
                    : QuestionType.MCQ;                          // legacy fallback

            Map<String, Object> questionMap = new LinkedHashMap<>();
            questionMap.put("quesId",      question.getQuesId());
            questionMap.put("content",     question.getContent());
            questionMap.put("image",       question.getImage());
            questionMap.put("questionType", type);

            // ── MATCHING ─────────────────────────────────────────────────────────
            if (type == QuestionType.MATCHING) {

                List<MatchingPair> pairs      = question.getMatchingPairs();
                int                pairsTotal = pairs != null ? pairs.size() : 0;
                int                pairsCorrect = 0;

                AnswerStatus status;

                if (selectedList.isEmpty() || pairsTotal == 0) {
                    status = AnswerStatus.SKIPPED;
                } else {
                    attempted++;
                    String[] given = selected; // index-aligned with pairOrder

                    for (int i = 0; i < pairsTotal; i++) {
                        String expected = pairs.get(i).getAnswer();
                        String student  = (given != null && i < given.length) ? given[i] : null;
                        if (expected != null && expected.equals(student)) pairsCorrect++;
                    }

                    double earnedMark = ((double) pairsCorrect / pairsTotal) * markPerQuestion;
                    marksGot += earnedMark;

                    if (pairsCorrect == pairsTotal) {
                        status = AnswerStatus.CORRECT;
                        correctAnswers++;
                    } else if (pairsCorrect > 0) {
                        status = AnswerStatus.PARTIAL;
                    } else {
                        status = AnswerStatus.WRONG;
                    }

                    questionMap.put("pairsCorrect", pairsCorrect);
                    questionMap.put("pairsTotal",   pairsTotal);
                    questionMap.put("earnedMark",   earnedMark);
                }

                // Include pairs with per-pair correctness for frontend rendering
                if (pairs != null) {
                    List<Map<String, Object>> pairResults = new ArrayList<>();
                    for (int i = 0; i < pairsTotal; i++) {
                        MatchingPair pair    = pairs.get(i);
                        String       student = (selected != null && i < selected.length) ? selected[i] : null;
                        boolean      correct = pair.getAnswer().equals(student);

                        Map<String, Object> pairMap = new LinkedHashMap<>();
                        pairMap.put("pairOrder",      pair.getPairOrder());
                        pairMap.put("prompt",         pair.getPrompt());
                        pairMap.put("correctAnswer",  pair.getAnswer());
                        pairMap.put("studentAnswer",  student);
                        pairMap.put("correct",        correct);
                        pairResults.add(pairMap);
                    }
                    questionMap.put("matchingPairs", pairResults);
                }

                questionMap.put("selectedAnswers", selected);
                questionMap.put("status",          status.name());

                // ── MCQ / TRUE_FALSE ─────────────────────────────────────────────────
            } else {

                List<String> correctList = question.getcorrect_answer() != null
                        ? Arrays.asList(question.getcorrect_answer())
                        : new ArrayList<>();

                AnswerStatus status;

                if (selectedList.isEmpty()) {
                    status = AnswerStatus.SKIPPED;
                } else {
                    attempted++;
                    Set<String> correctSet  = new HashSet<>(correctList);
                    Set<String> selectedSet = new HashSet<>(selectedList);

                    boolean hasAnyCorrect = selectedSet.stream().anyMatch(correctSet::contains);
                    boolean hasWrong      = selectedSet.stream().anyMatch(a -> !correctSet.contains(a));

                    if (!hasAnyCorrect) {
                        status = AnswerStatus.WRONG;
                    } else if (!hasWrong && selectedSet.size() == correctSet.size()) {
                        status = AnswerStatus.CORRECT;
                        correctAnswers++;
                        marksGot += markPerQuestion;
                    } else {
                        status = AnswerStatus.PARTIAL;
                    }
                }

                questionMap.put("option1",         question.getOption1());
                questionMap.put("option2",         question.getOption2());
                questionMap.put("option3",         question.getOption3());
                questionMap.put("option4",         question.getOption4());
                questionMap.put("correct_answer",  question.getcorrect_answer());
                questionMap.put("selectedAnswers", selected);
                questionMap.put("status",          status.name());
            }

            resultList.add(questionMap);
        }

        // ── Summary response ──────────────────────────────────────────────────────
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("studentId",      studentId);
        response.put("quizId",         quizId);
        response.put("marksGot",       marksGot);
        response.put("maxMarks",       maxMarks);
        response.put("correctAnswers", correctAnswers);
        response.put("attempted",      attempted);
        response.put("totalQuestions", totalQs);
        response.put("results",        resultList);
        return response;
    }










//GET QUIZ REPORT FOR CURRENT USER
//        public List<Report> getReportsForCurrentUser(Principal principal) {
//            if (principal == null) {
//                throw new RuntimeException("User not authenticated");
//            }
//            return reportRepository.findByUserUsername(principal.getName());
//        }



    public List<Report> getReportsForCurrentUser(Principal principal) {
        if (principal == null) {
            throw new RuntimeException("User not authenticated");
        }
        String username = principal.getName();
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));
        return reportRepository.findByUserId(user.getId());
    }





    public List<ReportDTO> getReportsForMyQuizzes(Long lecturerId) {
        List<Report> reports = reportRepository.findByQuizUserId(lecturerId);
        // Convert to DTOs
        return reports.stream()
                .map(ReportDTO::new)
                .collect(Collectors.toList());
    }





}
