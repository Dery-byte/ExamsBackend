package com.exam.repository;

import com.exam.model.attendance.AttendanceRecord;
import com.exam.model.attendance.AttendanceStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AttendanceRecordRepository extends JpaRepository<AttendanceRecord, Long> {

    @Query("SELECT r FROM AttendanceRecord r JOIN FETCH r.student WHERE r.session.id = :sessionId")
    List<AttendanceRecord> forSession(@Param("sessionId") Long sessionId);

    Optional<AttendanceRecord> findBySession_IdAndStudent_Id(Long sessionId, Long studentId);

    @Query("SELECT COUNT(r) > 0 FROM AttendanceRecord r WHERE r.session.id = :sessionId AND r.deviceHash = :hash AND r.student.id <> :studentId")
    boolean deviceUsedByOther(@Param("sessionId") Long sessionId, @Param("hash") String hash, @Param("studentId") Long studentId);

    /** This student's check-ins with this phone in classes still running (freed when staff reset the phone link). */
    List<AttendanceRecord> findByStudent_IdAndDeviceHashAndSession_ClosedAtIsNull(Long studentId, String deviceHash);

    @Query("SELECT COUNT(r) FROM AttendanceRecord r WHERE r.session.id = :sessionId AND r.status = :status")
    long countByStatus(@Param("sessionId") Long sessionId, @Param("status") AttendanceStatus status);

    /** [sessionId, status, count] for several sessions at once. */
    @Query("SELECT r.session.id, r.status, COUNT(r) FROM AttendanceRecord r WHERE r.session.id IN :ids GROUP BY r.session.id, r.status")
    List<Object[]> statusCounts(@Param("ids") java.util.Collection<Long> sessionIds);

    /** [status, count, of which passed the second check] for one session. */
    @Query("SELECT r.status, COUNT(r), SUM(CASE WHEN r.secondCheckAt IS NOT NULL THEN 1 ELSE 0 END)"
            + " FROM AttendanceRecord r WHERE r.session.id = :sessionId GROUP BY r.status")
    List<Object[]> liveCounts(@Param("sessionId") Long sessionId);

    /** A student's records in an academic session, newest meeting first. */
    @Query("SELECT r FROM AttendanceRecord r JOIN FETCH r.session s WHERE r.student.id = :studentId"
            + " AND s.academicSession.id = :academicSessionId ORDER BY s.openedAt DESC")
    List<AttendanceRecord> forStudent(@Param("studentId") Long studentId, @Param("academicSessionId") Long academicSessionId);

    /** Statuses of a student's finished meetings of one course in an academic session. */
    @Query("SELECT r.status FROM AttendanceRecord r WHERE r.student.id = :studentId AND r.session.course.cid = :courseId"
            + " AND r.session.academicSession.id = :academicSessionId AND r.session.closedAt IS NOT NULL")
    List<AttendanceStatus> courseStatuses(@Param("studentId") Long studentId, @Param("courseId") Long courseId,
                                          @Param("academicSessionId") Long academicSessionId);

    /** Statuses of a student's finished programme meetings (any level) in an academic session. */
    @Query("SELECT r.status FROM AttendanceRecord r WHERE r.student.id = :studentId AND r.session.program.id = :programId"
            + " AND r.session.scope = :scope AND r.session.academicSession.id = :academicSessionId AND r.session.closedAt IS NOT NULL")
    List<AttendanceStatus> programmeStatuses(@Param("studentId") Long studentId, @Param("programId") Long programId,
                                             @Param("academicSessionId") Long academicSessionId,
                                             @Param("scope") com.exam.model.attendance.AttendanceMode scope);

    /** [sessionId, studentId, status, flags] for the reports. */
    @Query("SELECT r.session.id, r.student.id, r.status, r.flags FROM AttendanceRecord r WHERE r.session.id IN :ids")
    List<Object[]> reportRows(@Param("ids") java.util.Collection<Long> sessionIds);

    @Modifying
    @Query("DELETE FROM AttendanceRecord r WHERE r.session.id = :sessionId")
    void deleteBySession(@Param("sessionId") Long sessionId);
}
