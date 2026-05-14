package com.exam.service;
import com.exam.DTO.NumberOfTheoryUpdateRequest;
import com.exam.model.User;
import com.exam.model.exam.NumberOfTheoryToAnswer;
import com.exam.model.exam.Quiz;
import com.exam.model.exam.Report;
import com.exam.repository.NumberOfTheoryToAnswerRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class NumberOfTheoryToAnswerService {
    @Autowired
    private NumberOfTheoryToAnswerRepository numberOfTheoryToAnswerRepository;

    public NumberOfTheoryToAnswer addQuestions(NumberOfTheoryToAnswer numberOfTheoryToAnswer){
        return this.numberOfTheoryToAnswerRepository.save(numberOfTheoryToAnswer);
    }









//    public NumberOfTheoryToAnswer updateNoTheoryAnswer(NumberOfTheoryToAnswer numberOfTheoryToAnswer){
//        return this.numberOfTheoryToAnswerRepository.save(numberOfTheoryToAnswer);
//    }

    public NumberOfTheoryToAnswer updateNoTheoryAnswer(NumberOfTheoryUpdateRequest request){
        NumberOfTheoryToAnswer entity = numberOfTheoryToAnswerRepository
                .findById(request.getAnswerTheoryId())
                .orElseThrow(() -> new RuntimeException("Record not found"));
        // Update only the provided fields
        if(request.getTotalQuestToAnswer() != null) {
            entity.setTotalQuestToAnswer(request.getTotalQuestToAnswer());
        }
        if(request.getTimeAllowed() != null) {
            entity.setTimeAllowed(request.getTimeAllowed());
        }
        return numberOfTheoryToAnswerRepository.save(entity);
    }






    public List<NumberOfTheoryToAnswer> TheoryTOByQuiz_Id(Quiz quiz){
        return numberOfTheoryToAnswerRepository.findByQuiz(quiz);
    }

    public List<NumberOfTheoryToAnswer> findByQuizId(Long quizId) {
        return numberOfTheoryToAnswerRepository.findByQuiz_qId(quizId);
    }
}
