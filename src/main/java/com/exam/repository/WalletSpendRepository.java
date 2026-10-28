package com.exam.repository;

import com.exam.model.fees.WalletSpend;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface WalletSpendRepository extends JpaRepository<WalletSpend, Long> {

    @Query("SELECT s FROM WalletSpend s WHERE s.student.id = :studentId ORDER BY s.spentAt DESC")
    List<WalletSpend> findForStudent(@Param("studentId") Long studentId);
}
