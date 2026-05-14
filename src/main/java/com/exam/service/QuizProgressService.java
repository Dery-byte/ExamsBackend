package com.exam.service;

import com.exam.DTO.*;
import com.exam.helper.ResourceNotFoundException;
import com.exam.model.exam.UserQuizAnswer;
import com.exam.repository.UserQuizProgressRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

        answer.setUserId(userId);
        answer.setQuestionId(request.getQuestionId());

        if (answer.getQuizId() == null && request.getQuizId() != null) {
            answer.setQuizId(request.getQuizId());
        }

        List<String> options = answer.getSelectedOptions();
        if (options == null) {
            options = new ArrayList<>();
            answer.setSelectedOptions(options);
        }
        if (request.isChecked()) {
            if (!options.contains(request.getOption())) {
                options.add(request.getOption());
            }
        } else {
            options.remove(request.getOption());
        }
        repository.save(answer);

        return new QuizProgressResponse(answer.getQuestionId(), answer.getSelectedOptions(),answer.getMatchingAnswers());
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