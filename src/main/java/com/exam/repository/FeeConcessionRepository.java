package com.exam.repository;

import com.exam.model.fees.FeeConcession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface FeeConcessionRepository extends JpaRepository<FeeConcession, Long> {

    @Query("SELECT c FROM FeeConcession c WHERE c.student.id = :studentId AND c.session.id = :sessionId AND c.active = true ORDER BY c.id")
    List<FeeConcession> findActive(@Param("studentId") Long studentId, @Param("sessionId") Long sessionId);

    /** Every active concession of a session, loaded once for many students. */
    @Query("SELECT c FROM FeeConcession c WHERE c.session.id = :sessionId AND c.active = true ORDER BY c.id")
    List<FeeConcession> findActiveBySession(@Param("sessionId") Long sessionId);

    @Query("SELECT c FROM FeeConcession c JOIN FETCH c.student s LEFT JOIN FETCH s.program JOIN FETCH c.session "
            + "WHERE c.session.id = :sessionId ORDER BY c.active DESC, c.createdAt DESC")
    List<FeeConcession> findAllBySession(@Param("sessionId") Long sessionId);

    @Query("SELECT c FROM FeeConcession c JOIN FETCH c.session WHERE c.student.id = :studentId ORDER BY c.createdAt DESC")
    List<FeeConcession> findForStudent(@Param("studentId") Long studentId);
}
