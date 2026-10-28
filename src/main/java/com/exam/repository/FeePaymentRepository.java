package com.exam.repository;

import com.exam.model.fees.FeePayment;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface FeePaymentRepository extends JpaRepository<FeePayment, Long> {

    Optional<FeePayment> findByReference(String reference);

    /** Row lock so the redirect check, the webhook and the reconciler never settle one payment twice. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM FeePayment p WHERE p.reference = :reference")
    Optional<FeePayment> lockByReference(@Param("reference") String reference);

    // Statuses are passed as parameters: Hibernate 6.1 can't read nested-enum literals in JPQL.

    // What has been paid towards fees and bills is in FeeAllocationRepository.

    @Query("SELECT p FROM FeePayment p JOIN FETCH p.session WHERE p.student.id = :studentId ORDER BY p.createdAt DESC")
    List<FeePayment> findForStudent(@Param("studentId") Long studentId);

    /** Recent checkouts for this amount (with their items), newest first, so a double click reopens the same one. */
    @Query("SELECT DISTINCT p FROM FeePayment p LEFT JOIN FETCH p.items WHERE p.student.id = :studentId "
            + "AND p.status = :status AND p.amount = :amount AND p.createdAt > :after ORDER BY p.createdAt DESC")
    List<FeePayment> findOpenCheckouts(@Param("studentId") Long studentId, @Param("status") FeePayment.Status status,
                                       @Param("amount") BigDecimal amount, @Param("after") LocalDateTime after);

    /** [item name, total] the student has paid for specific items of this fee. */
    @Query("SELECT i.name, SUM(i.amount) FROM FeePaymentItem i WHERE i.payment.student.id = :studentId "
            + "AND i.payment.schedule.id = :scheduleId AND i.payment.status = :status GROUP BY i.name")
    List<Object[]> sumItemsByStatus(@Param("studentId") Long studentId, @Param("scheduleId") Long scheduleId,
                                    @Param("status") FeePayment.Status status);

    default List<Object[]> paidItems(Long studentId, Long scheduleId) {
        return sumItemsByStatus(studentId, scheduleId, FeePayment.Status.SUCCESS);
    }

    /** [studentId, scheduleId, item name, total] paid for chosen items, for every fee of a session. */
    @Query("SELECT i.payment.student.id, i.payment.schedule.id, i.name, SUM(i.amount) FROM FeePaymentItem i "
            + "WHERE i.payment.schedule.session.id = :sessionId AND i.payment.status = :status GROUP BY i.payment.student.id, i.payment.schedule.id, i.name")
    List<Object[]> sumItemsBySession(@Param("sessionId") Long sessionId, @Param("status") FeePayment.Status status);

    default List<Object[]> paidItemsBySession(Long sessionId) {
        return sumItemsBySession(sessionId, FeePayment.Status.SUCCESS);
    }

    /** [item name, total] paid for specific items of this fee by anyone. */
    @Query("SELECT i.name, SUM(i.amount) FROM FeePaymentItem i WHERE i.payment.schedule.id = :scheduleId "
            + "AND i.payment.status = :status GROUP BY i.name")
    List<Object[]> sumItemsForSchedule(@Param("scheduleId") Long scheduleId, @Param("status") FeePayment.Status status);

    default List<Object[]> paidItemsForSchedule(Long scheduleId) {
        return sumItemsForSchedule(scheduleId, FeePayment.Status.SUCCESS);
    }

    /** The programme filter uses the fee paid, else the student's programme (a bill, or everything owed). */
    @Query(value = "SELECT p FROM FeePayment p JOIN FETCH p.student s JOIN FETCH p.session LEFT JOIN p.schedule sc "
            + "WHERE p.session.id = :sessionId "
            + "AND (:status IS NULL OR p.status = :status) "
            + "AND (:programId IS NULL OR sc.program.id = :programId OR (sc.id IS NULL AND s.program.id = :programId)) "
            + "AND (:level IS NULL OR p.level = :level) "
            + "AND (:q IS NULL OR LOWER(p.reference) LIKE :q OR LOWER(s.username) LIKE :q "
            + "     OR LOWER(CONCAT(s.firstname, ' ', s.lastname)) LIKE :q OR LOWER(s.email) LIKE :q)",
            countQuery = "SELECT COUNT(p) FROM FeePayment p JOIN p.student s LEFT JOIN p.schedule sc "
            + "WHERE p.session.id = :sessionId "
            + "AND (:status IS NULL OR p.status = :status) "
            + "AND (:programId IS NULL OR sc.program.id = :programId OR (sc.id IS NULL AND s.program.id = :programId)) "
            + "AND (:level IS NULL OR p.level = :level) "
            + "AND (:q IS NULL OR LOWER(p.reference) LIKE :q OR LOWER(s.username) LIKE :q "
            + "     OR LOWER(CONCAT(s.firstname, ' ', s.lastname)) LIKE :q OR LOWER(s.email) LIKE :q)")
    Page<FeePayment> search(@Param("sessionId") Long sessionId,
                            @Param("status") FeePayment.Status status,
                            @Param("programId") Long programId,
                            @Param("level") Integer level,
                            @Param("q") String q,
                            Pageable pageable);

    @Query("SELECT p.reference FROM FeePayment p WHERE p.status = :status AND p.method = :method "
            + "AND p.createdAt < :before ORDER BY p.createdAt ASC")
    List<String> referencesBefore(@Param("status") FeePayment.Status status, @Param("method") FeePayment.Method method,
                                  @Param("before") LocalDateTime before, Pageable pageable);

    /** Online payments still waiting on Paystack, oldest first. */
    default List<String> pendingReferencesBefore(LocalDateTime before, Pageable pageable) {
        return referencesBefore(FeePayment.Status.PENDING, FeePayment.Method.PAYSTACK, before, pageable);
    }

    long countBySchedule_Id(Long scheduleId);

    /** Payments received from {@code from} (inclusive) to {@code to} (exclusive), including ones cancelled since, newest first. */
    @Query("SELECT p FROM FeePayment p JOIN FETCH p.student WHERE p.status IN :statuses AND p.paidAt >= :from AND p.paidAt < :to ORDER BY p.paidAt DESC")
    List<FeePayment> paidBetweenByStatus(@Param("statuses") java.util.Collection<FeePayment.Status> statuses,
                                         @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    default List<FeePayment> paidBetween(LocalDateTime from, LocalDateTime to) {
        return paidBetweenByStatus(List.of(FeePayment.Status.SUCCESS, FeePayment.Status.VOIDED), from, to);
    }

    /** [paidAt, method, amount] of successful payments received in a period (light rows for daily totals). */
    @Query("SELECT p.paidAt, p.method, p.amount FROM FeePayment p WHERE p.status = :status AND p.paidAt >= :from AND p.paidAt < :to")
    List<Object[]> amountsBetweenByStatus(@Param("status") FeePayment.Status status, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    default List<Object[]> amountsBetween(LocalDateTime from, LocalDateTime to) {
        return amountsBetweenByStatus(FeePayment.Status.SUCCESS, from, to);
    }

    /** This cashier's successful payments not yet handed in (a cash-up), oldest first. */
    @Query("SELECT p FROM FeePayment p JOIN FETCH p.student WHERE p.recordedById = :userId AND p.cashUp IS NULL "
            + "AND p.status = :status ORDER BY p.paidAt")
    List<FeePayment> openForCashierByStatus(@Param("userId") Long userId, @Param("status") FeePayment.Status status);

    default List<FeePayment> openForCashier(Long userId) {
        return openForCashierByStatus(userId, FeePayment.Status.SUCCESS);
    }

    /** Payments voided from {@code from} (inclusive) to {@code to} (exclusive), newest first. */
    @Query("SELECT p FROM FeePayment p JOIN FETCH p.student WHERE p.status = :status AND p.voidedAt >= :from AND p.voidedAt < :to ORDER BY p.voidedAt DESC")
    List<FeePayment> voidedBetweenByStatus(@Param("status") FeePayment.Status status, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    default List<FeePayment> voidedBetween(LocalDateTime from, LocalDateTime to) {
        return voidedBetweenByStatus(FeePayment.Status.VOIDED, from, to);
    }

    /** [cashier id, cashier name, count, total] of successful payments not yet in a cash-up. */
    @Query("SELECT p.recordedById, p.recordedBy, COUNT(p), SUM(p.amount) FROM FeePayment p WHERE p.recordedById IS NOT NULL "
            + "AND p.cashUp IS NULL AND p.status = :status GROUP BY p.recordedById, p.recordedBy")
    List<Object[]> notCashedUpByStatus(@Param("status") FeePayment.Status status);

    default List<Object[]> notCashedUp() {
        return notCashedUpByStatus(FeePayment.Status.SUCCESS);
    }

    /** Successful payments received from {@code from} (inclusive) to {@code to} (exclusive), newest first. */
    @Query("SELECT p FROM FeePayment p JOIN FETCH p.student JOIN FETCH p.session WHERE p.status = :status "
            + "AND p.paidAt >= :from AND p.paidAt < :to ORDER BY p.paidAt DESC")
    List<FeePayment> receivedBetween(@Param("status") FeePayment.Status status, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

}
