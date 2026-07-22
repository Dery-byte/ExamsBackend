package com.exam.repository;

import com.exam.model.exam.Program;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProgramRepository extends JpaRepository<Program, Long> {
    List<Program> findByDepartment_Id(Long departmentId);
    boolean existsByCode(String code);

    // Used by non-SA routes to hide disabled programs
    List<Program> findByEnabledTrue();
    List<Program> findByDepartment_IdAndEnabledTrue(Long departmentId);
}
