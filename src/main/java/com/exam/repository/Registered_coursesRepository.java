package com.exam.repository;

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

    List<Report> findByCategory_cid(Long categoryId);
}
