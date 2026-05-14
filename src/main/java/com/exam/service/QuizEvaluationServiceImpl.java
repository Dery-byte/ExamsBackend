//package com.exam.service;
//
//import com.exam.DTO.QuizEvaluationResult;
//import com.exam.model.User;
//import com.exam.model.exam.Questions;
//import com.exam.model.exam.Quiz;
//import com.exam.model.exam.Report;
//import com.exam.repository.ReportRepository;
//import com.exam.service.Impl.QuizEvaluationService;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.security.core.userdetails.UserDetailsService;
//import org.springframework.stereotype.Service;
//
//import java.math.BigDecimal;
//import java.util.Arrays;
//import java.util.Collections;
//import java.util.List;
//
//@Service
//public class QuizEvaluationServiceImpl implements QuizEvaluationService {
//
//    @Autowired
//    private UserDetailsService userDetailsService;
//
//    @Autowired
//    private QuizService quizService;
//
//    @Autowired
//    private QuestionsService questionsService;
//
//    @Autowired
//    private ReportService reportService;
//
//    @Autowired
//    private ReportRepository reportRepository;
//
//
//    @Override
//    public QuizEvaluationResult evaluateQuiz(List<Questions> questions, String username, Long quizId) {
//        if (questions == null || questions.isEmpty()) {
//            throw new IllegalArgumentException("No questions provided");
//        }
//        User user = (User) userDetailsService.loadUserByUsername(username);
//        Quiz quiz = quizService.getQuiz(quizId);
//        if (user == null || quiz == null) {
//            throw new IllegalArgumentException("User or quiz not found");
//        }
//        double marksGot = 0.0;
//        int correctAnswers = 0;
//        int attempted = 0;
//        double maxMarks =
//                questions.get(0).getQuiz().getMaxMarks(
//        );
//        for (Questions q : questions) {
//            if (q == null) continue;
//            Questions question = questionsService.get(q.getQuesId());
//            if (question == null) continue;
//            List<String> correctAnswersList =
//                    q.getcorrect_answer() != null ? Arrays.asList(q.getcorrect_answer()) : null;
//            List<String> givenAnswersList =
//                    q.getGivenAnswer() != null ? Arrays.asList(q.getGivenAnswer()) : null;
//            if (correctAnswersList != null && givenAnswersList != null) {
//                Collections.sort(correctAnswersList);
//                Collections.sort(givenAnswersList);
//                if (correctAnswersList.equals(givenAnswersList)) {
//                    correctAnswers++;
//                    double marksSingle = maxMarks / questions.size();
//                    marksGot += marksSingle;
//                }
//            }
//            if (isAttempted(givenAnswersList)) {
//                attempted++;
//            }
//        }
//        // Save report
////        Report report = new Report();
//
//        // CHECK IF REPORT EXISTS
//        Report report = reportRepository.findByUserAndQuiz(user, quiz).orElse(new Report());
//        report.setQuiz(quiz);
//        report.setUser(user);
//        report.setProgress("Completed");
//        report.setMarks(BigDecimal.valueOf(marksGot));
////        report.setMarksB(BigDecimal.ZERO);
//        reportService.addReport(report);
//
//
//
//        return new QuizEvaluationResult(
//                marksGot,
//                correctAnswers,
//                attempted,
//                maxMarks
//        );
//    }
//
//
//
//
//
//
//
//
//    private boolean isAttempted(List<String> answers) {
//        if (answers == null) return false;
//
//        return answers.stream()
//                .anyMatch(a -> a != null && !a.trim().isEmpty());
//    }
//
//
//
//
//
//}




package com.exam.service;

import com.exam.DTO.QuizEvaluationResult;
import com.exam.model.User;
import com.exam.model.exam.MatchingPair;
import com.exam.model.exam.QuestionType;
import com.exam.model.exam.Questions;
import com.exam.model.exam.Quiz;
import com.exam.model.exam.Report;
import com.exam.repository.ReportRepository;
import com.exam.service.Impl.QuizEvaluationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@Service
public class QuizEvaluationServiceImpl implements QuizEvaluationService {

    @Autowired
    private UserDetailsService userDetailsService;

    @Autowired
    private QuizService quizService;

    @Autowired
    private QuestionsService questionsService;

    @Autowired
    private ReportService reportService;

    @Autowired
    private ReportRepository reportRepository;

    @Override
    public QuizEvaluationResult evaluateQuiz(List<Questions> questions, String username, Long quizId) {
        if (questions == null || questions.isEmpty()) {
            throw new IllegalArgumentException("No questions provided");
        }

        User user = (User) userDetailsService.loadUserByUsername(username);
        Quiz quiz = quizService.getQuiz(quizId);

        if (user == null || quiz == null) {
            throw new IllegalArgumentException("User or quiz not found");
        }

        double maxMarks   = questions.get(0).getQuiz().getMaxMarks();
        int    totalQs    = questions.size();

        double marksGot        = 0.0;
        int    correctAnswers  = 0;   // fully-correct question count (MCQ/TF)
        // for MATCHING: counts as 1 only if ALL pairs correct
        int    attempted       = 0;

        // Mark per question = maxMarks / totalQs
        // MATCHING distributes that per-question mark across its pairs
        double markPerQuestion = maxMarks / totalQs;

        for (Questions q : questions) {
            if (q == null) continue;

            // Always resolve from DB to get matchingPairs + correct_answer
            Questions persisted = questionsService.get(q.getQuesId());
            if (persisted == null) continue;

            List<String> givenList = q.getGivenAnswer() != null
                    ? Arrays.asList(q.getGivenAnswer())
                    : null;

            if (isAttempted(givenList)) attempted++;

            QuestionType type = persisted.getQuestionType() != null
                    ? persisted.getQuestionType()
                    : QuestionType.MCQ;                       // legacy fallback

            if (type == QuestionType.MATCHING) {
                // ── Per-pair partial scoring ───────────────────────────────
                // givenAnswer[] is ordered by pairOrder:
                //   givenAnswer[0] = student's answer for pair 0
                //   givenAnswer[1] = student's answer for pair 1  … etc.
                List<MatchingPair> pairs = persisted.getMatchingPairs();
                if (pairs == null || pairs.isEmpty()) continue;

                int correctPairs = 0;
                String[] given   = q.getGivenAnswer() != null ? q.getGivenAnswer() : new String[0];

                for (int i = 0; i < pairs.size(); i++) {
                    String expected = pairs.get(i).getAnswer();
                    String student  = i < given.length ? given[i] : null;
                    if (expected != null && expected.equals(student)) correctPairs++;
                }

                // Partial mark = (correctPairs / totalPairs) * markPerQuestion
                double partialMark = ((double) correctPairs / pairs.size()) * markPerQuestion;
                marksGot += partialMark;

                // Count as a "correct question" only if every pair was right
                if (correctPairs == pairs.size()) correctAnswers++;

            } else {
                // ── MCQ / TRUE_FALSE: all-or-nothing ──────────────────────
                List<String> correctList = persisted.getcorrect_answer() != null
                        ? Arrays.asList(persisted.getcorrect_answer())
                        : null;

                if (correctList != null && givenList != null) {
                    List<String> sortedCorrect = new java.util.ArrayList<>(correctList);
                    List<String> sortedGiven   = new java.util.ArrayList<>(givenList);
                    Collections.sort(sortedCorrect);
                    Collections.sort(sortedGiven);

                    if (sortedCorrect.equals(sortedGiven)) {
                        correctAnswers++;
                        marksGot += markPerQuestion;
                    }
                }
            }
        }

        // ── Save / update report ──────────────────────────────────────────────
        Report report = reportRepository.findByUserAndQuiz(user, quiz).orElse(new Report());
        report.setQuiz(quiz);
        report.setUser(user);
        report.setProgress("Completed");
        report.setMarks(BigDecimal.valueOf(marksGot));
        reportService.addReport(report);

        return new QuizEvaluationResult(marksGot, correctAnswers, attempted, maxMarks);
    }

    // ─────────────────────────────────────────────────────────────────────────

    private boolean isAttempted(List<String> answers) {
        if (answers == null) return false;
        return answers.stream().anyMatch(a -> a != null && !a.trim().isEmpty());
    }
}
