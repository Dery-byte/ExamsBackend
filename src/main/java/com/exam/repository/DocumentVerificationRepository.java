package com.exam.repository;

import com.exam.model.academic.DocumentVerification;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DocumentVerificationRepository extends JpaRepository<DocumentVerification, Long> {
    Optional<DocumentVerification> findByCode(String code);
    boolean existsByCode(String code);
    List<DocumentVerification> findAllByOrderByIssuedAtDesc(Pageable page);
    List<DocumentVerification> findByStudentUsernameContainingIgnoreCaseOrCodeContainingIgnoreCaseOrderByIssuedAtDesc(String username, String code, Pageable page);
}
