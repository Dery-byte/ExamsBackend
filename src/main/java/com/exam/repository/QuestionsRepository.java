package com.exam.repository;

import com.exam.model.exam.Questions;
import com.exam.model.exam.Quiz;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
@Repository
public interface QuestionsRepository extends JpaRepository <Questions, Long> {
    Set<Questions> findByQuiz(Quiz quiz);


    List<Questions> findByQuiz_qId(Long quizId);

    List<Questions> findByQuiz_qIdAndQuiz_Category_User_Username(
            Long quizId,
            String username
    );

    @Modifying
    @Transactional
    @Query("DELETE FROM Questions q WHERE q.quiz.qId= :quizId")
    void deleteByQuiz_Id(Long quizId);

}
