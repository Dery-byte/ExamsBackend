package com.exam.repository;

import com.exam.model.exam.Category;
import com.exam.model.exam.Program;
import com.exam.model.exam.Registered_courses;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CategoryRepository extends JpaRepository<Category, Long> {
    // Fetch categories for a given user ID
    List<Category> findByUser_Id(Long userId);

    // Find categories by user and level
    List<Category> findByUserIdAndLevel(Long userId, String level);

    // Filter courses for a student — program + level + semester
    List<Category> findByProgramsContainingAndLevelAndSemester(Program program, String level, Integer semester);

    // All courses for a given program
    List<Category> findByProgramsContaining(Program program);

    // All courses for a given program and level
    List<Category> findByProgramsContainingAndLevel(Program program, String level);

    // Find by level only (fallback for legacy / program-null courses)
    List<Category> findByLevel(String level);

    // Bulk normalize: strip "Level " prefix so all levels become plain numbers
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query(
        "UPDATE Category c SET c.level = TRIM(SUBSTRING(c.level, 7)) " +
        "WHERE LOWER(c.level) LIKE 'level %'"
    )
    int normalizeLevelPrefix();
}
