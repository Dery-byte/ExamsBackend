package com.exam.repository;

import com.exam.model.fees.BillLine;
import com.exam.model.fees.StudentBill;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface BillLineRepository extends JpaRepository<BillLine, Long> {

    /** Lines of open bills for stocked items not all handed over yet (one student, or everyone when null). */
    @Query("SELECT l FROM BillLine l JOIN FETCH l.bill b JOIN FETCH b.student s JOIN FETCH l.item i "
            + "WHERE i.stocked = true AND b.status = :open AND l.issuedQty < l.quantity AND (:studentId IS NULL OR s.id = :studentId) "
            + "ORDER BY b.createdAt, l.id")
    List<BillLine> awaitingHandOver(@Param("studentId") Long studentId, @Param("open") StudentBill.Status open);

    /** [itemId, quantity still to hand over] on open bills. */
    @Query("SELECT l.item.id, SUM(l.quantity - l.issuedQty) FROM BillLine l WHERE l.item.stocked = true AND l.bill.status = :open "
            + "AND l.issuedQty < l.quantity GROUP BY l.item.id")
    List<Object[]> owedByItem(@Param("open") StudentBill.Status open);
}
