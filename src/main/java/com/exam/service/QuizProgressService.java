package com.exam.service;

import com.exam.DTO.*;
import com.exam.helper.ResourceNotFoundException;
import com.exam.model.exam.UserQuizAnswer;
import com.exam.repository.UserQuizProgressRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Transactional
public class QuizProgressService {

    @Autowired
    private final UserQuizProgressRepository repository;

    public QuizProgressService(UserQuizProgressRepository repository) {
        this.repository = repository;
    }

    public QuizProgressResponse updateAnswer(Long userId, QuizProgressRequest request) {
        UserQuizAnswer answer = repository
                .findByUserIdAndQuestionId(userId, request.getQuestionId())
                .orElse(new UserQuizAnswer());
        apply(userId, answer, request);
        repository.save(answer);

        return new QuizProgressResponse(answer.getQuestionId(), answer.getSelectedOptions(),answer.getMatchingAnswers());
    }

    /** Most answer changes one request may carry; the exam page sends far fewer. */
    static final int MAX_BATCH = 500;

    /**
     * Several answer changes from the exam page's save queue in one request, applied in the order they
     * were made (so ticking then unticking an option ends unticked). One query loads the rows involved.
     * A change that can't be applied (no question id, a bad pair number) is skipped, never the batch.
     */
    public int updateAnswers(Long userId, List<QuizProgressRequest> changes) {
        if (changes == null || changes.isEmpty()) return 0;
        if (changes.size() > MAX_BATCH) throw new IllegalArgumentException("Too many answers in one save (at most " + MAX_BATCH + ").");
        Set<Long> questionIds = changes.stream()
                .filter(c -> c != null && c.getQuestionId() != null)
                .map(QuizProgressRequest::getQuestionId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (questionIds.isEmpty()) return 0;

        Map<Long, UserQuizAnswer> rows = new LinkedHashMap<>();
        for (UserQuizAnswer a : repository.findByUserIdAndQuestionIdIn(userId, questionIds)) rows.putIfAbsent(a.getQuestionId(), a);
        int applied = 0;
        for (QuizProgressRequest change : changes) {
            if (change == null || change.getQuestionId() == null) continue;
            apply(userId, rows.computeIfAbsent(change.getQuestionId(), id -> new UserQuizAnswer()), change);
            applied++;
        }
        repository.saveAll(rows.values());
        return applied;
    }

    private static void apply(Long userId, UserQuizAnswer answer, QuizProgressRequest request) {
        answer.setUserId(userId);
        answer.setQuestionId(request.getQuestionId());

        if (answer.getQuizId() == null && request.getQuizId() != null) {
            answer.setQuizId(request.getQuizId());
        }

        // Matching: the answer chosen for one pair, kept by position ("a|b|c"), which is how the exam
        // page restores it after a reload
        if (request.getPairIndex() != null) {
            String pairs = withPair(answer.getMatchingAnswers(), request.getPairIndex(),
                    request.isChecked() && request.getOption() != null ? request.getOption() : "");
            if (pairs != null) answer.setMatchingAnswers(pairs);
            return;
        }

        List<String> options = answer.getSelectedOptions();
        if (options == null) {
            options = new ArrayList<>();
            answer.setSelectedOptions(options);
        }
        if (request.isReplace()) {
            options.clear();
            if (request.getOption() != null && !request.getOption().isBlank()) options.add(request.getOption());
        } else if (request.isChecked()) {
            if (!options.contains(request.getOption())) {
                options.add(request.getOption());
            }
        } else {
            options.remove(request.getOption());
        }
    }

    /** Most pairs a matching question can have here (well above what any question uses). */
    static final int MAX_PAIRS = 100;
    private static final int MATCHING_COLUMN_LENGTH = 4000;

    /** The saved pair answers with one pair set; null when the pair number or the result is out of range. */
    static String withPair(String saved, int pairIndex, String value) {
        if (pairIndex < 0 || pairIndex >= MAX_PAIRS) return null;
        List<String> pairs = new ArrayList<>(saved == null || saved.isEmpty()
                ? List.of() : Arrays.asList(saved.split(java.util.regex.Pattern.quote("|"), -1)));
        while (pairs.size() <= pairIndex) pairs.add("");
        pairs.set(pairIndex, value);
        String joined = String.join("|", pairs);
        return joined.length() > MATCHING_COLUMN_LENGTH ? null : joined;
    }





//    public UserQuizProgressResponse getAllAnswers(Long userId) {
//        List<UserQuizAnswer> answers = repository.findByUserId(userId);
//
//        Map<Long, List<String>> answerMap = answers.stream()
//                .collect(Collectors.toMap(
//                        UserQuizAnswer::getQuestionId,
//                        UserQuizAnswer::getSelectedOptions
//                ));
//
//        return new UserQuizProgressResponse(answerMap);
//    }
//
//    public UserQuizProgressResponse getAnswersByQuiz(Long userId, Long quizId) {
//        List<UserQuizAnswer> answers = repository.findByUserIdAndQuizId(userId, quizId);
//
//        Map<Long, List<String>> answerMap = answers.stream()
//                .collect(Collectors.toMap(
//                        UserQuizAnswer::getQuestionId,
//                        UserQuizAnswer::getSelectedOptions
//                ));
//        return new UserQuizProgressResponse(answerMap);
//    }



    @Transactional(readOnly = true)
    public UserQuizProgressResponse getAllAnswers(Long userId) {
        List<UserQuizAnswer> answers = repository.findByUserId(userId);
        return buildResponse(answers);
    }

    @Transactional(readOnly = true)
    public UserQuizProgressResponse getAnswersByQuiz(Long userId, Long quizId) {
        List<UserQuizAnswer> answers = repository.findByUserIdAndQuizId(userId, quizId);
        return buildResponse(answers);
    }

    // ── Build response with both MCQ/TF answers and matching answers ──────────
    private UserQuizProgressResponse buildResponse(List<UserQuizAnswer> answers) {
        Map<Long, List<String>> answerMap = answers.stream()
                .filter(a -> a.getSelectedOptions() != null && !a.getSelectedOptions().isEmpty())
                .collect(Collectors.toMap(
                        UserQuizAnswer::getQuestionId,
                        UserQuizAnswer::getSelectedOptions,
                        (a, b) -> b));

        Map<Long, String> matchingMap = answers.stream()
                .filter(a -> a.getMatchingAnswers() != null && !a.getMatchingAnswers().isEmpty())
                .collect(Collectors.toMap(
                        UserQuizAnswer::getQuestionId,
                        UserQuizAnswer::getMatchingAnswers,
                        (a, b) -> b));

        return new UserQuizProgressResponse(answerMap, matchingMap);
    }
//
//
//    public ViolationTimerResponseDTO saveViolationDelayTime(Long quizId, Long userId, VoilationTimerRequestDTO requestDTO) {
//        UserQuizAnswer progress = repository
//                .findFirstByQuizIdAndUserId(quizId, userId)
//                .orElse(new UserQuizAnswer());
//        // If it's a new entity, set the required fields
//        if (progress.getId() == null) {
//            progress.setQuizId(quizId);
//            progress.setUserId(userId);
//        }
//        progress.setViolationDelayTime(requestDTO.getRemainingTime());
//        ViolationTimerResponseDTO reponse= repository.save(progress);
//    }
//
//
//    public Integer getViolationDelayTime(Long quizId, Long userId) {
//        return repository.findViolationDelayTimeByQuizIdAndUserId(quizId, userId)
//                .orElse(null);
//    }

    public void clearAnswers(Long userId, Long quizId) {
        repository.deleteByUserIdAndQuizId(userId, quizId);
    }





}