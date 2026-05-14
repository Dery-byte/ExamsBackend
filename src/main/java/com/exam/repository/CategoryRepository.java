package com.exam.repository;

import com.exam.model.exam.Category;
import com.exam.model.exam.Registered_courses;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CategoryRepository extends JpaRepository<Category, Long> {
    // Fetch categories for a given user ID
    List<Category> findByUser_Id(Long userId);

    // Find all categories assigned to a specific lecturer
//    List<Category> findByUserIdOrderByCourseCodeAsc(Long userId);

    // Find categories by user and level
    List<Category> findByUserIdAndLevel(Long userId, String level);

}
