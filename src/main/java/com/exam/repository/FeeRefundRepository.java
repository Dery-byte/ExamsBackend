package com.exam.repository;

import com.exam.model.fees.FeeRefund;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface FeeRefundRepository extends JpaRepository<FeeRefund, Long> {

    @Query("SELECT r FROM FeeRefund r WHERE r.student.id = :studentId ORDER BY r.paidAt DESC")
    List<FeeRefund> findForStudent(@Param("studentId") Long studentId);

    /** Refunds paid or voided from {@code from} (inclusive) to {@code to} (exclusive). */
    @Query("SELECT r FROM FeeRefund r JOIN FETCH r.student WHERE (r.paidAt >= :from AND r.paidAt < :to) "
            + "OR (r.voidedAt >= :from AND r.voidedAt < :to) ORDER BY r.paidAt DESC")
    List<FeeRefund> touchedBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** [paidAt, method, amount] of refunds paid (not cancelled) in a period. */
    @Query("SELECT r.paidAt, r.method, r.amount FROM FeeRefund r WHERE r.status = :status AND r.paidAt >= :from AND r.paidAt < :to")
    List<Object[]> amountsBetween(@Param("status") FeeRefund.Status status, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** This person's refunds not yet in a cash-up. */
    @Query("SELECT r FROM FeeRefund r WHERE r.paidById = :userId AND r.cashUp IS NULL AND r.status = :status")
    List<FeeRefund> openForCashier(@Param("userId") Long userId, @Param("status") FeeRefund.Status status);
}
