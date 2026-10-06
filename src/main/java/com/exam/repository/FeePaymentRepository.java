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

    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM FeePayment p "
            + "WHERE p.student.id = :studentId AND p.schedule.id = :scheduleId AND p.status = :status")
    BigDecimal sumByStatus(@Param("studentId") Long studentId, @Param("scheduleId") Long scheduleId,
                           @Param("status") FeePayment.Status status);

    /** What the student has paid towards this fee. */
    default BigDecimal sumPaid(Long studentId, Long scheduleId) {
        return sumByStatus(studentId, scheduleId, FeePayment.Status.SUCCESS);
    }

    @Query("SELECT p.schedule.id, SUM(p.amount) FROM FeePayment p "
            + "WHERE p.session.id = :sessionId AND p.status = :status GROUP BY p.schedule.id")
    List<Object[]> sumBySchedule(@Param("sessionId") Long sessionId, @Param("status") FeePayment.Status status);

    /** [scheduleId, total paid] for every schedule in a session. */
    default List<Object[]> collectedBySchedule(Long sessionId) {
        return sumBySchedule(sessionId, FeePayment.Status.SUCCESS);
    }

    @Query("SELECT p FROM FeePayment p JOIN FETCH p.session WHERE p.student.id = :studentId ORDER BY p.createdAt DESC")
    List<FeePayment> findForStudent(@Param("studentId") Long studentId);

    /** Recent checkouts for this amount (with their items), newest first, so a double click reopens the same one. */
    @Query("SELECT DISTINCT p FROM FeePayment p LEFT JOIN FETCH p.items WHERE p.student.id = :studentId "
            + "AND p.schedule.id = :scheduleId AND p.status = :status AND p.amount = :amount AND p.createdAt > :after "
            + "ORDER BY p.createdAt DESC")
    List<FeePayment> findOpenCheckouts(@Param("studentId") Long studentId, @Param("scheduleId") Long scheduleId,
                                       @Param("status") FeePayment.Status status, @Param("amount") BigDecimal amount,
                                       @Param("after") LocalDateTime after);

    /** [item name, total] the student has paid for specific items of this fee. */
    @Query("SELECT i.name, SUM(i.amount) FROM FeePaymentItem i WHERE i.payment.student.id = :studentId "
            + "AND i.payment.schedule.id = :scheduleId AND i.payment.status = :status GROUP BY i.name")
    List<Object[]> sumItemsByStatus(@Param("studentId") Long studentId, @Param("scheduleId") Long scheduleId,
                                    @Param("status") FeePayment.Status status);

    default List<Object[]> paidItems(Long studentId, Long scheduleId) {
        return sumItemsByStatus(studentId, scheduleId, FeePayment.Status.SUCCESS);
    }

    @Query(value = "SELECT p FROM FeePayment p JOIN FETCH p.student s JOIN FETCH p.session "
            + "WHERE p.session.id = :sessionId "
            + "AND (:status IS NULL OR p.status = :status) "
            + "AND (:programId IS NULL OR p.schedule.program.id = :programId) "
            + "AND (:level IS NULL OR p.level = :level) "
            + "AND (:q IS NULL OR LOWER(p.reference) LIKE :q OR LOWER(s.username) LIKE :q "
            + "     OR LOWER(CONCAT(s.firstname, ' ', s.lastname)) LIKE :q OR LOWER(s.email) LIKE :q)",
            countQuery = "SELECT COUNT(p) FROM FeePayment p JOIN p.student s "
            + "WHERE p.session.id = :sessionId "
            + "AND (:status IS NULL OR p.status = :status) "
            + "AND (:programId IS NULL OR p.schedule.program.id = :programId) "
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

    long countBySchedule_IdAndStatus(Long scheduleId, FeePayment.Status status);
}
