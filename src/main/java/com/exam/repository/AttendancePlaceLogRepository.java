package com.exam.repository;

import com.exam.model.attendance.AttendancePlaceLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AttendancePlaceLogRepository extends JpaRepository<AttendancePlaceLog, Long> {

    Optional<AttendancePlaceLog> findFirstBySession_IdAndStudent_IdOrderByIdDesc(Long sessionId, Long studentId);

    /** Each student's newest reading in a meeting. */
    @Query("SELECT p FROM AttendancePlaceLog p WHERE p.id IN "
            + "(SELECT MAX(q.id) FROM AttendancePlaceLog q WHERE q.session.id = :sessionId GROUP BY q.student.id)")
    List<AttendancePlaceLog> latestForSession(@Param("sessionId") Long sessionId);

    @Modifying
    @Query("DELETE FROM AttendancePlaceLog p WHERE p.session.id = :sessionId")
    void deleteBySession(@Param("sessionId") Long sessionId);
}
