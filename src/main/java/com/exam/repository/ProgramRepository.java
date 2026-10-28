package com.exam.repository;

import com.exam.model.exam.Program;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProgramRepository extends JpaRepository<Program, Long> {
    List<Program> findByDepartment_Id(Long departmentId);
    boolean existsByCode(String code);

    // Used by non-SA routes to hide disabled programs
    List<Program> findByEnabledTrue();
    List<Program> findByDepartment_IdAndEnabledTrue(Long departmentId);

    /** Classes that were promoted into a deleted class go back to "the next class in order". */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Program p set p.nextProgramId = null where p.nextProgramId = :id")
    int clearNextProgram(@Param("id") Long id);
}
