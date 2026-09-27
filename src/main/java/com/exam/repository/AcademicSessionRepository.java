package com.exam.repository;

import com.exam.model.academic.AcademicSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AcademicSessionRepository extends JpaRepository<AcademicSession, Long> {
    Optional<AcademicSession> findFirstByCurrentTrue();
    List<AcademicSession> findAllByOrderByStartDateDescIdDesc();
    boolean existsByNameIgnoreCase(String name);
}
