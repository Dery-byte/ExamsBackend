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

    // Not a "DELETE ... WHERE user AND quiz": on MySQL that locks the whole index range and the gaps around it
    // until the request ends, and every student saving an answer next to it in the index waits (with a hall
    // finishing at once, 30 of 30 connections busy and 60+ requests waiting, 2026-11-19). A plain read takes
    // no locks; deleting by id locks only those rows, and nothing at all when there are none.
    default void deleteByUserIdAndQuizId(Long userId, Long quizId) {
        List<Long> ids = findIdsByUserIdAndQuizId(userId, quizId);
        if (!ids.isEmpty()) deleteAllByIdInBatch(ids);
    }

    @Query("SELECT ua.id FROM UserQuizAnswer ua WHERE ua.userId = :userId AND ua.quizId = :quizId")
    List<Long> findIdsByUserIdAndQuizId(@Param("userId") Long userId, @Param("quizId") Long quizId);




    Optional<UserQuizAnswer> findFirstByQuizIdAndUserId(Long quizId, Long userId);


    @Query("SELECT ua.violationDelayTime FROM UserQuizAnswer ua WHERE ua.quizId = :quizId AND ua.userId = :userId")
    Optional<Integer> findViolationDelayTimeByQuizIdAndUserId(@Param("quizId") Long quizId, @Param("userId") Long userId);
}