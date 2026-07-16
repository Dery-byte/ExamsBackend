package com.exam.repository;

import com.exam.model.exam.StudentCourseMark;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StudentCourseMarkRepository extends JpaRepository<StudentCourseMark, Long> {
    List<StudentCourseMark> findBySemesterSheetId(Long semesterSheetId);
    List<StudentCourseMark> findBySemesterSheetIdAndStudentId(Long semesterSheetId, Long studentId);
}
