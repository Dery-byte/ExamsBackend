package com.exam.repository;

import com.exam.model.fees.StudentBill;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface StudentBillRepository extends JpaRepository<StudentBill, Long> {

    /** Every bill of the student (any status), oldest first. */
    @Query("SELECT b FROM StudentBill b JOIN FETCH b.batch LEFT JOIN FETCH b.session WHERE b.student.id = :studentId ORDER BY b.createdAt ASC, b.id ASC")
    List<StudentBill> findForStudent(@Param("studentId") Long studentId);

    @Query("SELECT b FROM StudentBill b JOIN FETCH b.student s LEFT JOIN FETCH s.program WHERE b.batch.id = :batchId ORDER BY s.firstname, s.lastname")
    List<StudentBill> findForBatch(@Param("batchId") Long batchId);

    @Query("SELECT b.student.id FROM StudentBill b WHERE b.student.id IN :studentIds AND b.status = :status "
            + "AND LOWER(b.title) = :titleKey AND (b.session.id = :sessionId)")
    List<Long> studentsWithSameBill(@Param("studentIds") Collection<Long> studentIds, @Param("titleKey") String titleKey,
                                    @Param("sessionId") Long sessionId, @Param("status") StudentBill.Status status);

    /** [batchId, status, count] for these batches. */
    @Query("SELECT b.batch.id, b.status, COUNT(b) FROM StudentBill b WHERE b.batch.id IN :batchIds GROUP BY b.batch.id, b.status")
    List<Object[]> countByBatchAndStatus(@Param("batchIds") Collection<Long> batchIds);

    /** [batchId, total paid] (successful payments only) for these batches. */
    @Query("SELECT b.batch.id, SUM(a.amount) FROM FeeAllocation a JOIN a.bill b WHERE b.batch.id IN :batchIds "
            + "AND a.payment.status = :status GROUP BY b.batch.id")
    List<Object[]> paidByBatch(@Param("batchIds") Collection<Long> batchIds, @Param("status") com.exam.model.fees.FeePayment.Status status);

    /** [studentId, total of their open bills]. */
    @Query("SELECT b.student.id, SUM(b.amount) FROM StudentBill b WHERE b.status = :status GROUP BY b.student.id")
    List<Object[]> sumByStudent(@Param("status") StudentBill.Status status);
}
