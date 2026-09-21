package com.exam.repository;

import com.exam.model.exam.Category;
import com.exam.model.exam.Quiz;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface QuizRepository  extends JpaRepository <Quiz, Long> {

    List<Quiz> findBycategory(Category category);


    public List<Quiz> findByActive(Boolean b);
    public  List<Quiz> findByCategoryAndActive(Category c , Boolean b);

    @Modifying
    @Transactional
    @Query("DELETE FROM Quiz q WHERE q.category.cid = :categoryId")
    void deleteByCategory_cid(@Param("categoryId") Long categoryId);

    List<Quiz> findByCategory_cid(Long categoryId);


    // Fetch all quizzes for a given user ID
    List<Quiz> findByUser_Id(Long userId);

    /** Candidates for the auto-open sweep: scheduled to open automatically but not yet published. */
    List<Quiz> findByAutoOpenTrueAndActiveFalse();

    /** Candidates for the auto-close sweep: open quizzes that close themselves once due. */
    List<Quiz> findByAutoCloseTrueAndActiveTrueAndStatus(com.exam.model.QuizStatus status);
}
