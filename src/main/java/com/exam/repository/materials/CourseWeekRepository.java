package com.exam.repository.materials;

import com.exam.model.materials.CourseWeek;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CourseWeekRepository extends JpaRepository<CourseWeek, Long> {
    List<CourseWeek> findBySpace_IdOrderByNumber(Long spaceId);
    List<CourseWeek> findBySpace_IdIn(java.util.Collection<Long> spaceIds);
    Optional<CourseWeek> findBySpace_IdAndNumber(Long spaceId, int number);
}
