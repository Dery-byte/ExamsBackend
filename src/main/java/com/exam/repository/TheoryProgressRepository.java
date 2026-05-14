package com.exam.repository;


import com.exam.model.exam.Quiz;
import com.exam.model.User;
import com.exam.model.exam.TheoryProgress;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TheoryProgressRepository extends JpaRepository<TheoryProgress, Long> {

//    List<TheoryProgress> findByUserAndQuiz(User user, Quiz quiz);
//
//    Optional<TheoryProgress> findByUserAndQuizAndQuesNo(User user, Quiz quiz, String quesNo);

    void deleteByUserAndQuiz(User user, Quiz quiz);



//    @Modifying
//    @Query("DELETE FROM TheoryProgress ta WHERE ta.user = :user AND ta.quiz = :quiz")
//    void deleteByUserAndQuiz(@Param("user") User user, @Param("quiz") Quiz quiz);

    @Modifying
    @Query("DELETE FROM TheoryProgress ta WHERE ta.user = :user")
    void deleteAllByUser(@Param("user") User user);

    long countByUserAndQuiz(User user, Quiz quiz);



    // Find all answers for a user and quiz
    List<TheoryProgress> findByUserAndQuiz(User user, Quiz quiz);

    // Find specific answer by question number
    Optional<TheoryProgress> findByUserAndQuizAndQuesNo(User user, Quiz quiz, String quesNo);

    // Batch fetch multiple questions (optimized)
    List<TheoryProgress> findByUserAndQuizAndQuesNoIn(User user, Quiz quiz, List<String> quesNos);

    // Delete all answers for a quiz
}