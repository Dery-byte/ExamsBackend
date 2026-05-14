package com.exam.service;


import com.exam.DTO.TheoryProgressDTO;
import com.exam.model.exam.Quiz;
import com.exam.model.User;
import com.exam.model.exam.TheoryProgress;
import com.exam.repository.TheoryProgressRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class TheoryProgressService {

    @Autowired
    private TheoryProgressRepository theoryProgressRepository;

    @Transactional
    public void saveAnswers(List<TheoryProgressDTO> answers, User user, Quiz quiz) {
        for (TheoryProgressDTO dto : answers) {
            // Find existing answer or create new one
            TheoryProgress answer = theoryProgressRepository
                    .findByUserAndQuizAndQuesNo(user, quiz, dto.getQuesNo())
                    .orElse(new TheoryProgress());
            answer.setQuesNo(dto.getQuesNo());
            answer.setGivenAnswer(dto.getGivenAnswer());
            answer.setUser(user);
            answer.setQuiz(quiz);
            theoryProgressRepository.save(answer);
        }
    }

    public List<TheoryProgressDTO> getAnswers(User user, Quiz quiz) {
        List<TheoryProgress> answers = theoryProgressRepository.findByUserAndQuiz(user, quiz);
        return answers.stream()
                .map(answer -> new TheoryProgressDTO(answer.getQuesNo(), answer.getGivenAnswer()))
                .collect(Collectors.toList());
    }

    @Transactional
    public void clearAnswers(User user, Quiz quiz) {
        theoryProgressRepository.deleteByUserAndQuiz(user, quiz);
    }




}