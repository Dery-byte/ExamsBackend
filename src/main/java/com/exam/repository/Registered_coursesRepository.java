package com.exam.repository;

import com.exam.model.User;
import com.exam.model.exam.Category;
import com.exam.model.exam.Registered_courses;
import com.exam.model.exam.Report;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface Registered_coursesRepository extends JpaRepository<Registered_courses, Long> {

    @Modifying
    @Transactional
    @Query("DELETE FROM Registered_courses r WHERE r.category.cid = :categoryId")
    void deleteByCategory_cid(@Param("categoryId") Long categoryId);

    @Modifying
    @Transactional
    @Query("DELETE FROM Registered_courses rc WHERE rc.user.id = :userId")
    void deleteByUserId(@Param("userId") Integer userId);

    @Modifying
    @Transactional
    @Query("DELETE FROM Registered_courses r WHERE r.category.cid = :categoryId AND r.user.id = :userId")
    void deleteByCategoryIdAndUserId(@Param("categoryId") Long categoryId, @Param("userId") Long userId);

    List<Report> findByCategory_cid(Long categoryId);

    /** Check if a student is already enrolled in a specific course */
    @Query("SELECT COUNT(r) FROM Registered_courses r WHERE r.category = :category AND r.user = :user")
    long countByCategoryAndUser(@Param("category") Category category, @Param("user") User user);

    /** All registrations for a given student */
    @Query("SELECT r FROM Registered_courses r WHERE r.user = :user")
    List<Registered_courses> findRegistrationsByUser(@Param("user") User user);

    /** All registrations for a given student id */
    @Query("SELECT r FROM Registered_courses r WHERE r.user.id = :userId")
    List<Registered_courses> findRegistrationsByUserId(@Param("userId") Long userId);

    /** Find registrations by category */
    List<Registered_courses> findByCategory(Category category);
}
