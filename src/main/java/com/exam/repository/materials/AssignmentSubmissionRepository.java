package com.exam.repository.materials;

import com.exam.model.materials.AssignmentSubmission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AssignmentSubmissionRepository extends JpaRepository<AssignmentSubmission, Long> {

    Optional<AssignmentSubmission> findByAssignment_IdAndStudent_Id(Long assignmentId, Long studentId);

    List<AssignmentSubmission> findByAssignment_Id(Long assignmentId);

    List<AssignmentSubmission> findByAssignment_IdIn(Collection<Long> assignmentIds);

    @Query("SELECT s FROM AssignmentSubmission s WHERE s.student.id = :studentId AND s.assignment.id IN :ids")
    List<AssignmentSubmission> mine(@Param("studentId") Long studentId, @Param("ids") Collection<Long> assignmentIds);

    /** assignmentId → submissions handed in. */
    @Query("SELECT s.assignment.id, COUNT(s) FROM AssignmentSubmission s WHERE s.assignment.id IN :ids"
            + " AND s.submittedAt IS NOT NULL GROUP BY s.assignment.id")
    List<Object[]> countsFor(@Param("ids") Collection<Long> assignmentIds);

    @Query("SELECT s.assignment.id, COUNT(s) FROM AssignmentSubmission s WHERE s.assignment.id IN :ids"
            + " AND s.score IS NOT NULL GROUP BY s.assignment.id")
    List<Object[]> markedCountsFor(@Param("ids") Collection<Long> assignmentIds);

    @Query("SELECT s.id FROM AssignmentSubmission s WHERE s.aiQueued = true ORDER BY s.id")
    List<Long> queuedForAi();
}
