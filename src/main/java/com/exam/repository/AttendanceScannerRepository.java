package com.exam.repository;

import com.exam.model.attendance.AttendanceScanner;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AttendanceScannerRepository extends JpaRepository<AttendanceScanner, Long> {

    Optional<AttendanceScanner> findByTokenHash(String tokenHash);

    @Query("SELECT s FROM AttendanceScanner s WHERE s.session.id = :sessionId ORDER BY s.createdAt")
    List<AttendanceScanner> forSession(@Param("sessionId") Long sessionId);

    @Modifying
    @Query("DELETE FROM AttendanceScanner s WHERE s.session.id = :sessionId")
    void deleteBySession(@Param("sessionId") Long sessionId);
}
