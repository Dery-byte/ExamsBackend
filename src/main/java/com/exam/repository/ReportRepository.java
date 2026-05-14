package com.exam.repository;

import com.exam.model.User;
import com.exam.model.exam.Category;
import com.exam.model.exam.Quiz;
import com.exam.model.exam.Report;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface ReportRepository extends JpaRepository<Report, Long> {
//    Optional<Report> findById(Long id);
    // ✅ Find by User ID
    List<Report> findByUser_Id(Long userId);
    // ✅ Find by User and Quiz
    List<Report> findByUser_IdAndQuiz_qId(Long userId, Long quizId);
    // ✅ Find by Quiz (entity)
    List<Report> findByQuiz(Quiz quiz);
    void deleteByQuiz(Quiz quiz);








    List<Report> findByUserUsername(String username);

    List<Report> findByUserId(Long userId);










    // ✅ Get latest report for user and quiz
    Optional<Report> findTopByUser_IdAndQuiz_qIdOrderBySubmissionDateDesc(Long userId, Long quizId);

    // ✅ Get report with answers (eager fetch)
    @Query("SELECT DISTINCT r FROM report r LEFT JOIN FETCH r.answers WHERE r.id = :reportId")
    Optional<Report> findByIdWithAnswers(@Param("reportId") Long reportId);

    // ✅ Get all reports for quiz with answers
    @Query("SELECT DISTINCT r FROM report r LEFT JOIN FETCH r.answers WHERE r.quiz.qId = :quizId")
    List<Report> findByQuizIdWithAnswers(@Param("quizId") Long quizId);

    List<Report>findByQuiz_qId(Long id);




    List<Report> findByUserAndQuiz(Optional<User> user, Optional<Quiz> quiz);
    List<Report> findByQuiz(Long quiz);

    List<Report> findByUser(User user);

Optional<Report> findByUserAndQuiz(User user, Quiz quiz);

    Report findByUser_idAndQuiz_qId(Integer id, Long quizId);


    @Modifying
    @Transactional
    @Query("DELETE FROM report r WHERE r.quiz.qId = :quizId")
    void deleteByQuizId(@Param("quizId") Long quizId);




    // fetch reports only for quizzes in a category
    List<Report> findByQuiz_Category(Category category);

    // Fetch reports for Quizzes in a category by a specific user.
    List<Report> findByUser_IdAndQuiz_Category(Long userId, Category category);

    // ✅ Spring Data JPA derives this query automatically
    List<Report> findByQuizUserId(Long lecturerId);

    // ✅ Alternative naming (same result)
    List<Report> findByQuiz_User_Id(Long lecturerId);





    // ✅ Fetch ALL student reports for quizzes created by the logged-in LECTURER
    @Query("SELECT r FROM report r WHERE r.quiz.user.id = :lecturerId")
    List<Report> findByQuizCreatorId(@Param("lecturerId") Long lecturerId);








}




