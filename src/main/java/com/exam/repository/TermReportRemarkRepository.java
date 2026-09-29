package com.exam.repository;

import com.exam.model.academic.TermReportRemark;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TermReportRemarkRepository extends JpaRepository<TermReportRemark, Long> {
    List<TermReportRemark> findBySheetId(Long sheetId);
    Optional<TermReportRemark> findBySheetIdAndStudentId(Long sheetId, Long studentId);
    void deleteBySheetId(Long sheetId);
}
