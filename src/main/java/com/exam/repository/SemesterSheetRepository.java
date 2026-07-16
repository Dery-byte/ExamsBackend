package com.exam.repository;

import com.exam.model.exam.SemesterSheet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SemesterSheetRepository extends JpaRepository<SemesterSheet, Long> {
    List<SemesterSheet> findByProgramIdAndLevelAndSemester(Long programId, String level, Integer semester);
}
