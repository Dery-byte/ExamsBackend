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
import com.exam.model.exam.*;
import com.exam.repository.ReportRepository;
import com.exam.repository.StudentAnswerRepository;
import com.exam.service.Impl.QuizEvaluationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.exam.helper.QuestionDraw;

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

    @Autowired
    private StudentAnswerRepository studentAnswerRepository;

    @Autowired
    private AttemptService attemptService;

    @Autowired
    private com.exam.repository.QuestionsRepository questionsRepository;

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    private static final int ANSWER_ROW_BATCH = 50;

    @Override
    @org.springframework.transaction.annotation.Transactional
    public QuizEvaluationResult evaluateQuiz(List<Questions> questions, String username, Long quizId) {
        User user = (User) userDetailsService.loadUserByUsername(username);
        Quiz quiz = quizService.getQuiz(quizId);

        if (user == null || quiz == null) {
            throw new IllegalArgumentException("User or quiz not found");
        }

        // Marked against the quiz's own questions (a question-pool quiz: the attempt's draw), each with the
        // answer the browser sent for it. The browser's list never decides what counts: leaving questions
        // out, repeating one or adding another quiz's can't change the marks.
        int totalQs;
        if (quiz.drawsQuestions()) {
            questions = alignToDraw(questions, user, quiz);
            totalQs   = Math.max(1, questions.size());
        } else {
            if (questions == null || questions.isEmpty()) {
                throw new IllegalArgumentException("No questions provided");
            }
            questions = markable(questionsRepository.findByQuiz_qId(quiz.getqId()), sentAnswers(questions));
            if (questions.isEmpty()) {
                throw new IllegalArgumentException("This quiz has no objective questions");
            }
            totalQs = questions.size();
        }

        // Every question to mark, with its matching pairs, in one query rather than one per question
        Map<Long, Questions> persistedById = new HashMap<>();
        for (Questions p : questionsService.findInOrder(questions.stream()
                .filter(java.util.Objects::nonNull).map(Questions::getQuesId).toList())) {
            persistedById.put(p.getQuesId(), p);
        }
        List<StudentAnswer> answerRows = new ArrayList<>();

        double maxMarks   = quiz.getMaxMarks();

        double marksGot        = 0.0;
        int    correctAnswers  = 0;   // fully-correct question count (MCQ/TF)
        // for MATCHING: counts as 1 only if ALL pairs correct
        int    attempted       = 0;

        // A new attempt replaces the previous attempt's answers (the ledger keeps every attempt's marks).
        studentAnswerRepository.deleteByStudentAndQuiz(user.getId(), quiz.getqId());

        // Mark per question = maxMarks / totalQs
        // MATCHING distributes that per-question mark across its pairs
        double markPerQuestion = maxMarks / totalQs;

        for (Questions q : questions) {
            if (q == null) continue;

            // Always marked from the stored question (matchingPairs + correct_answer)
            Questions persisted = persistedById.get(q.getQuesId());
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

                if (correctList != null && givenList != null
                        && AnswerMatcher.isCorrect(type, persisted.getcorrect_answer(), persisted.getTolerance(), givenList)) {
                    correctAnswers++;
                    marksGot += markPerQuestion;
                }
            }

            // ── Save Student Answer ──────────────────────────────────────────────
            StudentAnswer studentAnswer = new StudentAnswer();
            studentAnswer.setUser(user);
            studentAnswer.setQuestion(persisted);
            studentAnswer.setSelectedOptions(q.getGivenAnswer());
            answerRows.add(studentAnswer);
        }
        // The answer rows go to the database as one batch (on MySQL one multi-row INSERT, see
        // MySqlBatchInserts). Batching is switched on for this request's session only, nowhere else.
        if (entityManager != null) entityManager.unwrap(org.hibernate.Session.class).setJdbcBatchSize(ANSWER_ROW_BATCH);
        studentAnswerRepository.saveAll(answerRows);

        // ── Save / update report ──────────────────────────────────────────────
        // Rejects (and rolls this whole submission back) if no attempt is available or it was already submitted.
        attemptService.recordObjective(user, quiz, BigDecimal.valueOf(marksGot));

        Report report = reportRepository.findByUserAndQuiz(user, quiz).orElse(new Report());
        report.setQuiz(quiz);
        report.setUser(user);
        report.setProgress("Completed");
        report.setMarks(BigDecimal.valueOf(marksGot));
        reportService.addReport(report);

        return new QuizEvaluationResult(marksGot, correctAnswers, attempted, maxMarks);
    }

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * The questions to mark on a question-pool quiz, each carrying the student's answer (none = blank):
     * the attempt's drawn set, so each is worth maxMarks / (questions drawn). Anything sent that was not
     * drawn is ignored, and a drawn question deleted since is left out. If no draw was recorded (an attempt
     * that never opened the questions page), only the sent questions that belong to this quiz count, at most
     * Number of Questions of them, and they are still marked out of Number of Questions.
     */
    private List<Questions> alignToDraw(List<Questions> sent, User user, Quiz quiz) {
        Map<Long, String[]> given = sentAnswers(sent);

        List<Long> drawn = attemptService.drawnForActiveAttempt(user, quiz);
        List<Questions> toMark;
        if (drawn != null) {
            toMark = questionsService.findInOrder(drawn);
        } else {
            int n = QuestionDraw.size(quiz);
            toMark = questionsService.findInOrder(new ArrayList<>(given.keySet())).stream()
                    .filter(q -> q.getQuiz() != null && quiz.getqId().equals(q.getQuiz().getqId()))
                    .limit(n)
                    .toList();
            // Pad to Number of Questions with unanswered stand-ins so the marks per question stay maxMarks / n
            List<Questions> padded = new ArrayList<>(markable(toMark, given));
            return padded.size() >= n || n == Integer.MAX_VALUE ? padded : withBlanks(padded, n);
        }
        return markable(toMark, given);
    }

    /** The answer sent for each question id (the first one, if a question was sent twice). */
    private static Map<Long, String[]> sentAnswers(List<Questions> sent) {
        Map<Long, String[]> given = new HashMap<>();
        if (sent != null) for (Questions q : sent)
            if (q != null && q.getQuesId() != null) given.putIfAbsent(q.getQuesId(), q.getGivenAnswer());
        return given;
    }

    private static List<Questions> markable(List<Questions> persisted, Map<Long, String[]> given) {
        List<Questions> out = new ArrayList<>();
        for (Questions p : persisted) {
            Questions q = new Questions(given.getOrDefault(p.getQuesId(), new String[0]));
            q.setQuesId(p.getQuesId());
            out.add(q);
        }
        return out;
    }

    /** Adds null stand-ins, which the marking loop skips, so they only count towards the number marked out of. */
    private static List<Questions> withBlanks(List<Questions> list, int n) {
        while (list.size() < n) list.add(null);
        return list;
    }

    private boolean isAttempted(List<String> answers) {
        if (answers == null) return false;
        return answers.stream().anyMatch(a -> a != null && !a.trim().isEmpty());
    }
}
