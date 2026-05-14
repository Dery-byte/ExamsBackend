package com.exam.service;

import com.exam.DTO.AnswerDTO;
import com.exam.model.exam.Answer;
import com.exam.model.exam.TheoryQuestions;
import com.exam.repository.AnswerRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class AnswerService {

    @Autowired
private AnswerRepository answerRepository;

//    public List<Answer> getAnswersByUserAndQuiz(Long userId, Long quizId) {
//        return answerRepository.findByUser_IdAndQuiz_qId(userId, quizId);
//    }
//
//    public List<AnswerDTO> getAnswersByUserAndQuiz(Long userId, Long quizId) {
//        List<Answer> answers = answerRepository.findByUser_IdAndQuiz_qId(userId, quizId);
//        return answers.stream()
//                .map(a -> new AnswerDTO(
//                        a.getAnswerId(),
//                        a.getStudentAnswer(),
//                        a.getScore(),
//                        a.getMaxMarks(),
//                        a.getFeedback(),
//                        a.getKeyMissed(),
//                        a.getTheoryQuestion().getTqId(),
//                        a.getQuiz().getqId()
//                ))
//                .collect(Collectors.toList());
//    }

    public List<AnswerDTO> getAnswersByUserAndQuiz(Long userId, Long quizId) {
        List<Answer> answers = answerRepository.findByUser_IdAndQuiz_qId(userId, quizId);

        return answers.stream()
                .map(a -> {
                    TheoryQuestions tq = a.getTheoryQuestion();
                    return new AnswerDTO(
                            a.getAnswerId(),
                            tq.getQuesNo(),
                            tq.getQuestion(),
                            a.getStudentAnswer(),
                            a.getMaxMarks(),
                            a.getScore(),
                            a.getKeyMissed(),
                            a.getQuiz().getqId(),
                            tq.getTqId(),
                            a.getFeedback()

                    );
                })
                .collect(Collectors.toList());
    }

}
