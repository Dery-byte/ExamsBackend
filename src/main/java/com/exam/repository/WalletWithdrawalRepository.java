package com.exam.repository;

import com.exam.model.fees.WalletWithdrawal;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface WalletWithdrawalRepository extends JpaRepository<WalletWithdrawal, Long> {

    @Query("SELECT w FROM WalletWithdrawal w WHERE w.student.id = :studentId ORDER BY w.requestedAt DESC")
    List<WalletWithdrawal> findForStudent(@Param("studentId") Long studentId);

    @Query("SELECT COALESCE(SUM(w.amount), 0) FROM WalletWithdrawal w WHERE w.student.id = :studentId AND w.status IN :statuses")
    BigDecimal sumForStudent(@Param("studentId") Long studentId, @Param("statuses") Collection<WalletWithdrawal.Status> statuses);

    /** Wallet money held by withdrawals still in progress. */
    default BigDecimal held(Long studentId) {
        return sumForStudent(studentId, WalletWithdrawal.OPEN);
    }

    @Query("SELECT w FROM WalletWithdrawal w JOIN FETCH w.student WHERE w.status IN :statuses ORDER BY w.requestedAt ASC")
    List<WalletWithdrawal> findByStatuses(@Param("statuses") Collection<WalletWithdrawal.Status> statuses);

    /** Finished ones (paid, rejected, cancelled, reversed), newest first. */
    @Query("SELECT w FROM WalletWithdrawal w JOIN FETCH w.student WHERE w.status NOT IN :open ORDER BY w.requestedAt DESC")
    List<WalletWithdrawal> findClosed(@Param("open") Collection<WalletWithdrawal.Status> open, Pageable page);

    Optional<WalletWithdrawal> findByRefund_Id(Long refundId);

    @Query("SELECT w.student.id FROM WalletWithdrawal w WHERE w.id = :id")
    Optional<Long> studentIdOf(@Param("id") Long id);
}
