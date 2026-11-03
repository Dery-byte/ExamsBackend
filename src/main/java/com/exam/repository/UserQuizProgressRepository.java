package com.exam.repository;

import com.exam.model.exam.UserQuizAnswer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserQuizProgressRepository extends JpaRepository<UserQuizAnswer, Long> {

    Optional<UserQuizAnswer> findByUserIdAndQuestionId(Long userId, Long questionId);

    List<UserQuizAnswer> findByUserId(Long userId);

    /** With the chosen options in the same query (not one more query per answered question). */
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = "selectedOptions")
    List<UserQuizAnswer> findByUserIdAndQuizId(Long userId, Long quizId);

    /** The rows of these questions, for a batch of answer changes. */
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = "selectedOptions")
    List<UserQuizAnswer> findByUserIdAndQuestionIdIn(Long userId, java.util.Collection<Long> questionIds);

    void deleteByUserIdAndQuestionId(Long userId, Long questionId);

    @Modifying
    @Query("DELETE FROM UserQuizAnswer ua WHERE ua.userId = :userId AND ua.quizId = :quizId")
    void deleteByUserIdAndQuizId(@Param("userId") Long userId, @Param("quizId") Long quizId);




    Optional<UserQuizAnswer> findFirstByQuizIdAndUserId(Long quizId, Long userId);


    @Query("SELECT ua.violationDelayTime FROM UserQuizAnswer ua WHERE ua.quizId = :quizId AND ua.userId = :userId")
    Optional<Integer> findViolationDelayTimeByQuizIdAndUserId(@Param("quizId") Long quizId, @Param("userId") Long userId);
}