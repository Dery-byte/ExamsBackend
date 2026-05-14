package com.exam.repository;

import com.exam.model.exam.NumberOfTheoryToAnswer;
import com.exam.model.exam.Quiz;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository

public interface NumberOfTheoryToAnswerRepository extends JpaRepository<NumberOfTheoryToAnswer, Long> {
    List<NumberOfTheoryToAnswer> findByQuiz(Quiz quiz);

//    void findByQuizId(Long quizId);

    List<NumberOfTheoryToAnswer> findByQuiz_qId(Long quizId);

    @Modifying
    @Transactional
    @Query("DELETE FROM NumberOfTheoryToAnswer n WHERE n.quiz.qId= :quizId")
       void deleteByQuiz_Id( @Param("quizId") Long quizId);

//    void deleteByQuizCategory_cid(Long categoryId);




//    void deleteByQuiz_Id(Long quizId);

}
