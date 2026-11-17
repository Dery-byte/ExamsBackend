package com.exam.repository.materials;

import com.exam.model.materials.CourseSpace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CourseSpaceRepository extends JpaRepository<CourseSpace, Long> {

    Optional<CourseSpace> findByCourse_CidAndAcademicSession_Id(Long courseId, Long sessionId);

    List<CourseSpace> findByCourse_CidIn(java.util.Collection<Long> courseIds);

    List<CourseSpace> findByAutoReleaseTrue();

    /** The same course's materials in other sessions, newest first (copy from an earlier term). */
    @Query("SELECT s FROM CourseSpace s WHERE s.course.cid = :courseId AND s.academicSession.id <> :sessionId"
            + " ORDER BY s.academicSession.startDate DESC")
    List<CourseSpace> otherSessions(@Param("courseId") Long courseId, @Param("sessionId") Long sessionId);

    // ── Courses and registrations (kept here so the materials code reads in one place) ──

    @Query("SELECT DISTINCT c FROM Category c JOIN c.programs p WHERE p.department.id = :deptId")
    List<com.exam.model.exam.Category> coursesInDepartment(@Param("deptId") Long departmentId);

    /** A student registered for the course this session (registrations from before sessions existed count too). */
    @Query("SELECT COUNT(r) FROM Registered_courses r LEFT JOIN r.session rs WHERE r.category.cid = :courseId"
            + " AND r.user.id = :studentId AND (rs.id = :sessionId OR rs IS NULL)")
    long registrations(@Param("courseId") Long courseId, @Param("studentId") Long studentId, @Param("sessionId") Long sessionId);

    @Query("SELECT DISTINCT r.category FROM Registered_courses r LEFT JOIN r.session rs WHERE r.user.id = :studentId"
            + " AND (rs.id = :sessionId OR rs IS NULL)")
    List<com.exam.model.exam.Category> coursesOf(@Param("studentId") Long studentId, @Param("sessionId") Long sessionId);

    /** Marks sheets of this session (or older ones without a session) that include the course. */
    @Query("SELECT DISTINCT s FROM SemesterSheet s JOIN s.courses c LEFT JOIN s.session ss WHERE c.cid = :courseId"
            + " AND (ss.id = :sessionId OR ss IS NULL)")
    List<com.exam.model.exam.SemesterSheet> sheetsWithCourse(@Param("courseId") Long courseId, @Param("sessionId") Long sessionId);

    /** The student has a quiz of this course open right now (started recently, not handed in). */
    @Query("SELECT COUNT(a) FROM QuizAttempt a WHERE a.user.id = :studentId AND a.quiz.category.cid = :courseId"
            + " AND a.status = com.exam.model.exam.AttemptStatus.IN_PROGRESS AND a.startedAt >= :since")
    long liveAttempts(@Param("studentId") Long studentId, @Param("courseId") Long courseId, @Param("since") java.time.LocalDateTime since);
}
