package com.exam.repository;

import com.exam.model.fees.CashUp;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface CashUpRepository extends JpaRepository<CashUp, Long> {

    @Query("SELECT c FROM CashUp c WHERE c.createdAt >= :from AND c.createdAt < :to ORDER BY c.createdAt DESC")
    List<CashUp> createdBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("SELECT c FROM CashUp c WHERE c.status = :status ORDER BY c.createdAt")
    List<CashUp> findByStatus(@Param("status") CashUp.Status status);
}
