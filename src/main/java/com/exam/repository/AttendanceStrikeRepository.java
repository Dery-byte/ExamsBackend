package com.exam.repository;

import com.exam.model.attendance.AttendanceStrike;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AttendanceStrikeRepository extends JpaRepository<AttendanceStrike, Long> {

    Optional<AttendanceStrike> findBySession_IdAndStudent_Id(Long sessionId, Long studentId);

    @Query("SELECT s FROM AttendanceStrike s WHERE s.session.id = :sessionId")
    List<AttendanceStrike> forSession(@Param("sessionId") Long sessionId);

    @Modifying
    @Query("DELETE FROM AttendanceStrike s WHERE s.session.id = :sessionId")
    void deleteBySession(@Param("sessionId") Long sessionId);
}
