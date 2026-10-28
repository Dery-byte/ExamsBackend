package com.exam.repository;

import com.exam.model.fees.StockMovement;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface StockMovementRepository extends JpaRepository<StockMovement, Long> {

    @Query("SELECT m FROM StockMovement m LEFT JOIN FETCH m.student WHERE m.item.id = :itemId ORDER BY m.createdAt DESC, m.id DESC")
    List<StockMovement> findForItem(@Param("itemId") Long itemId, Pageable page);

    /** [itemId, kind, total quantity] of movements in a period. */
    @Query("SELECT m.item.id, m.kind, SUM(m.quantity) FROM StockMovement m WHERE m.createdAt >= :from AND m.createdAt < :to GROUP BY m.item.id, m.kind")
    List<Object[]> totalsBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
