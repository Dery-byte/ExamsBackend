package com.exam.repository;

import com.exam.model.fees.FeeAllocation;
import com.exam.model.fees.FeePayment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

/**
 * Where payments went. Only allocations of SUCCESS payments count (a voided payment's allocations stay
 * for the record). Statuses are passed as parameters: Hibernate 6.1 can't read nested-enum literals.
 */
public interface FeeAllocationRepository extends JpaRepository<FeeAllocation, Long> {

    @Query("SELECT COALESCE(SUM(a.amount), 0) FROM FeeAllocation a WHERE a.payment.student.id = :studentId "
            + "AND a.schedule.id = :scheduleId AND a.payment.status = :status")
    BigDecimal sumForSchedule(@Param("studentId") Long studentId, @Param("scheduleId") Long scheduleId, @Param("status") FeePayment.Status status);

    /** What the student has paid towards this fee. */
    default BigDecimal paidForSchedule(Long studentId, Long scheduleId) {
        return sumForSchedule(studentId, scheduleId, FeePayment.Status.SUCCESS);
    }

    @Query("SELECT a.schedule.id, SUM(a.amount) FROM FeeAllocation a WHERE a.schedule.session.id = :sessionId "
            + "AND a.payment.status = :status GROUP BY a.schedule.id")
    List<Object[]> sumBySchedule(@Param("sessionId") Long sessionId, @Param("status") FeePayment.Status status);

    /** [scheduleId, total paid] for every fee of a session. */
    default List<Object[]> collectedBySchedule(Long sessionId) {
        return sumBySchedule(sessionId, FeePayment.Status.SUCCESS);
    }

    @Query("SELECT a.payment.student.id, a.schedule.id, SUM(a.amount) FROM FeeAllocation a WHERE a.schedule.session.id = :sessionId "
            + "AND a.payment.status = :status GROUP BY a.payment.student.id, a.schedule.id")
    List<Object[]> sumByStudentAndSchedule(@Param("sessionId") Long sessionId, @Param("status") FeePayment.Status status);

    /** [studentId, scheduleId, total paid] for a session's fees. */
    default List<Object[]> paidByStudentAndSchedule(Long sessionId) {
        return sumByStudentAndSchedule(sessionId, FeePayment.Status.SUCCESS);
    }

    @Query("SELECT a.bill.id, SUM(a.amount) FROM FeeAllocation a WHERE a.bill.id IN :billIds AND a.payment.status = :status GROUP BY a.bill.id")
    List<Object[]> sumByBill(@Param("billIds") Collection<Long> billIds, @Param("status") FeePayment.Status status);

    /** [billId, total paid] for these bills. */
    default List<Object[]> paidByBill(Collection<Long> billIds) {
        return billIds.isEmpty() ? List.of() : sumByBill(billIds, FeePayment.Status.SUCCESS);
    }

    @Query("SELECT COALESCE(SUM(a.amount), 0) FROM FeeAllocation a WHERE a.payment.student.id = :studentId "
            + "AND a.schedule IS NULL AND a.bill IS NULL AND a.arrears IS NULL AND a.refund IS NULL AND a.payment.walletDeposit = false AND a.payment.status = :status")
    BigDecimal sumCredit(@Param("studentId") Long studentId, @Param("status") FeePayment.Status status);

    /** Money the student paid beyond everything owed, not yet used. */
    default BigDecimal credit(Long studentId) {
        return sumCredit(studentId, FeePayment.Status.SUCCESS);
    }

    @Query("SELECT a FROM FeeAllocation a JOIN FETCH a.payment p WHERE p.student.id = :studentId "
            + "AND a.schedule IS NULL AND a.bill IS NULL AND a.arrears IS NULL AND a.refund IS NULL AND p.walletDeposit = false AND p.status = :status AND a.amount > 0 ORDER BY p.paidAt ASC, a.id ASC")
    List<FeeAllocation> findCredit(@Param("studentId") Long studentId, @Param("status") FeePayment.Status status);

    /** The student's unused credit, oldest payment first. */
    default List<FeeAllocation> openCredit(Long studentId) {
        return findCredit(studentId, FeePayment.Status.SUCCESS);
    }

    @Query("SELECT a.payment.student.id, SUM(a.amount) FROM FeeAllocation a WHERE a.schedule IS NULL AND a.bill IS NULL AND a.arrears IS NULL AND a.refund IS NULL "
            + "AND a.payment.walletDeposit = false AND a.payment.status = :status GROUP BY a.payment.student.id HAVING SUM(a.amount) > 0")
    List<Object[]> creditByStudentAndStatus(@Param("status") FeePayment.Status status);

    /** [studentId, unused credit] of every student who has some. */
    default List<Object[]> creditByStudent() {
        return creditByStudentAndStatus(FeePayment.Status.SUCCESS);
    }

    default List<Long> studentsWithCredit() {
        return creditByStudent().stream().map(r -> (Long) r[0]).toList();
    }

    // ── Wallets: the unspent part of wallet deposits (never counted as credit) ──

    @Query("SELECT COALESCE(SUM(a.amount), 0) FROM FeeAllocation a WHERE a.payment.student.id = :studentId "
            + "AND a.schedule IS NULL AND a.bill IS NULL AND a.arrears IS NULL AND a.refund IS NULL AND a.payment.walletDeposit = true AND a.payment.status = :status")
    BigDecimal sumWallet(@Param("studentId") Long studentId, @Param("status") FeePayment.Status status);

    /** What is in the student's wallet. */
    default BigDecimal wallet(Long studentId) {
        return sumWallet(studentId, FeePayment.Status.SUCCESS);
    }

    @Query("SELECT a FROM FeeAllocation a JOIN FETCH a.payment p WHERE p.student.id = :studentId "
            + "AND a.schedule IS NULL AND a.bill IS NULL AND a.arrears IS NULL AND a.refund IS NULL AND p.walletDeposit = true AND p.status = :status AND a.amount > 0 ORDER BY p.paidAt ASC, a.id ASC")
    List<FeeAllocation> findWallet(@Param("studentId") Long studentId, @Param("status") FeePayment.Status status);

    /** The student's wallet money, oldest deposit first (spent and paid back in that order). */
    default List<FeeAllocation> openWallet(Long studentId) {
        return findWallet(studentId, FeePayment.Status.SUCCESS);
    }

    @Query("SELECT COUNT(DISTINCT a.payment.student.id), COALESCE(SUM(a.amount), 0) FROM FeeAllocation a WHERE a.schedule IS NULL AND a.bill IS NULL "
            + "AND a.arrears IS NULL AND a.refund IS NULL AND a.payment.walletDeposit = true AND a.payment.status = :status AND a.amount > 0")
    List<Object[]> walletTotalsByStatus(@Param("status") FeePayment.Status status);

    /** [students with wallet money, total held in wallets]: what the school is keeping for students. */
    default Object[] walletTotals() {
        List<Object[]> rows = walletTotalsByStatus(FeePayment.Status.SUCCESS);
        return rows.isEmpty() ? new Object[]{0L, BigDecimal.ZERO} : rows.get(0);
    }

    @Query("SELECT a.arrears.id, SUM(a.amount) FROM FeeAllocation a WHERE a.arrears.id IN :ids AND a.payment.status = :status GROUP BY a.arrears.id")
    List<Object[]> sumByArrears(@Param("ids") Collection<Long> ids, @Param("status") FeePayment.Status status);

    /** [arrearsId, total paid] for these arrears. */
    default List<Object[]> paidByArrears(Collection<Long> ids) {
        return ids.isEmpty() ? List.of() : sumByArrears(ids, FeePayment.Status.SUCCESS);
    }

    @Query("SELECT a.payment.student.id, SUM(a.amount) FROM FeeAllocation a WHERE a.arrears.status = :open "
            + "AND a.payment.status = :status GROUP BY a.payment.student.id")
    List<Object[]> sumOnOpenArrearsByStudent(@Param("open") com.exam.model.fees.FeeArrears.Status open, @Param("status") FeePayment.Status status);

    /** [studentId, paid on their open arrears], for every student. */
    default List<Object[]> paidOnOpenArrearsByStudent() {
        return sumOnOpenArrearsByStudent(com.exam.model.fees.FeeArrears.Status.OPEN, FeePayment.Status.SUCCESS);
    }

    @Query("SELECT a.payment.student.id, SUM(a.amount) FROM FeeAllocation a WHERE a.bill.status = :open "
            + "AND a.payment.status = :status GROUP BY a.payment.student.id")
    List<Object[]> sumOnOpenBillsByStudent(@Param("open") com.exam.model.fees.StudentBill.Status open, @Param("status") FeePayment.Status status);

    /** [studentId, paid on their open bills], for every student. */
    default List<Object[]> paidOnOpenBillsByStudent() {
        return sumOnOpenBillsByStudent(com.exam.model.fees.StudentBill.Status.OPEN, FeePayment.Status.SUCCESS);
    }

    /** [studentId, programId, level] of the classes students paid fees for in a session (their class then). */
    @Query("SELECT DISTINCT a.payment.student.id, a.schedule.program.id, a.schedule.level FROM FeeAllocation a "
            + "WHERE a.schedule.session.id = :sessionId AND a.payment.status = :status")
    List<Object[]> classesPaidForByStatus(@Param("sessionId") Long sessionId, @Param("status") FeePayment.Status status);

    default List<Object[]> classesPaidFor(Long sessionId) {
        return classesPaidForByStatus(sessionId, FeePayment.Status.SUCCESS);
    }

    @Query("SELECT a FROM FeeAllocation a JOIN FETCH a.payment WHERE a.refund.id = :refundId")
    List<FeeAllocation> findForRefund(@Param("refundId") Long refundId);

    @Query("SELECT a FROM FeeAllocation a JOIN FETCH a.payment p WHERE p.student.id = :studentId AND p.status = :status "
            + "AND (a.schedule IS NOT NULL OR a.bill IS NOT NULL OR a.arrears IS NOT NULL) ORDER BY p.paidAt DESC, a.id DESC")
    List<FeeAllocation> findSpentByStatus(@Param("studentId") Long studentId, @Param("status") FeePayment.Status status);

    /** The student's money counted against something (fees, bills, arrears), newest first: released first when a charge goes down. */
    default List<FeeAllocation> spent(Long studentId) {
        return findSpentByStatus(studentId, FeePayment.Status.SUCCESS);
    }

    @Query("SELECT a FROM FeeAllocation a JOIN FETCH a.payment p WHERE a.bill.id = :billId AND p.status = :status")
    List<FeeAllocation> findForBillByStatus(@Param("billId") Long billId, @Param("status") FeePayment.Status status);

    default List<FeeAllocation> paidToBill(Long billId) {
        return findForBillByStatus(billId, FeePayment.Status.SUCCESS);
    }

    long countBySchedule_Id(Long scheduleId);
}
