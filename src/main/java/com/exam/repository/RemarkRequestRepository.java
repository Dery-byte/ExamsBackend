package com.exam.repository;

import com.exam.model.examops.RemarkRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RemarkRequestRepository extends JpaRepository<RemarkRequest, Long> {
    List<RemarkRequest> findByStudent_IdOrderByCreatedAtDesc(Long studentId);
    List<RemarkRequest> findAllByOrderByCreatedAtDesc();
    boolean existsByReport_Id(Long reportId);
}
