package com.exam.repository;

import com.exam.model.fees.BillBatch;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface BillBatchRepository extends JpaRepository<BillBatch, Long> {

    /** Newest first; sessionId null = every session. */
    @Query("SELECT b FROM BillBatch b LEFT JOIN FETCH b.session WHERE (:sessionId IS NULL OR b.session.id = :sessionId) ORDER BY b.issuedAt DESC, b.id DESC")
    List<BillBatch> findRecent(@Param("sessionId") Long sessionId, Pageable page);
}
