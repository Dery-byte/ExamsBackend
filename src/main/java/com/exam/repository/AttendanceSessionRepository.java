package com.exam.repository;

import com.exam.model.User;
import com.exam.model.attendance.AttendanceSession;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface AttendanceSessionRepository extends JpaRepository<AttendanceSession, Long> {

    List<AttendanceSession> findByClosedAtIsNull();

    List<AttendanceSession> findByClosedAtIsNullAndEndsAtBefore(LocalDateTime time);

    long countByCourse_CidAndAcademicSession_Id(Long courseId, Long academicSessionId);

    @Query("SELECT COUNT(s) FROM AttendanceSession s WHERE s.program.id = :programId AND s.academicSession.id = :sessionId"
            + " AND ((:level IS NULL AND s.level IS NULL) OR s.level = :level)")
    long countProgrammeMeetings(@Param("programId") Long programId, @Param("level") Integer level, @Param("sessionId") Long sessionId);

    @Query("SELECT s FROM AttendanceSession s ORDER BY s.openedAt DESC")
    List<AttendanceSession> recent(Pageable page);

    @Query("SELECT s FROM AttendanceSession s WHERE s.department.id = :departmentId ORDER BY s.openedAt DESC")
    List<AttendanceSession> recentInDepartment(@Param("departmentId") Long departmentId, Pageable page);

    @Query("SELECT s FROM AttendanceSession s LEFT JOIN s.course c WHERE s.openedBy.id = :userId OR c.user.id = :userId ORDER BY s.openedAt DESC")
    List<AttendanceSession> recentForLecturer(@Param("userId") Long userId, Pageable page);

    boolean existsByScheduleIdAndOpenedAtGreaterThanEqual(Long scheduleId, LocalDateTime since);

    /** Finished meetings of one academic session (report-card attendance). */
    List<AttendanceSession> findByAcademicSession_IdAndClosedAtIsNotNull(Long academicSessionId);

    /** Programme meetings from before the term was recorded on them. */
    List<AttendanceSession> findByScopeAndSemesterIsNull(com.exam.model.attendance.AttendanceMode scope);

    /** For the reports: one academic session's meetings, oldest first. */
    List<AttendanceSession> findByAcademicSession_IdOrderByOpenedAtAsc(Long academicSessionId);

    /** For the reports: meetings started in [from, to), oldest first. */
    List<AttendanceSession> findByOpenedAtGreaterThanEqualAndOpenedAtLessThanOrderByOpenedAtAsc(LocalDateTime from, LocalDateTime to);

    // ── Class lists ──────────────────────────────────────────────────────────

    /** Students registered for the course this academic session (registrations from before sessions existed count too). */
    @Query("SELECT DISTINCT r.user FROM Registered_courses r LEFT JOIN r.session rs"
            + " WHERE r.category.cid = :courseId AND (rs.id = :sessionId OR rs IS NULL)"
            + " AND r.user.role = com.exam.model.Role.NORMAL AND r.user.enabled = true")
    List<User> courseRoster(@Param("courseId") Long courseId, @Param("sessionId") Long academicSessionId);

    @Query("SELECT COUNT(r) FROM Registered_courses r LEFT JOIN r.session rs"
            + " WHERE r.category.cid = :courseId AND r.user.id = :studentId AND (rs.id = :sessionId OR rs IS NULL)")
    long countRegistration(@Param("courseId") Long courseId, @Param("studentId") Long studentId, @Param("sessionId") Long academicSessionId);

    @Query("SELECT u FROM User u WHERE u.role = com.exam.model.Role.NORMAL AND u.enabled = true"
            + " AND u.program.id = :programId AND (:level IS NULL OR u.currentLevel = :level)")
    List<User> programmeRoster(@Param("programId") Long programId, @Param("level") Integer level);
}
