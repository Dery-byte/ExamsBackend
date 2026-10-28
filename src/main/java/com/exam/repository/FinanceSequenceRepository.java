package com.exam.repository;

import com.exam.model.fees.FinanceSequence;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface FinanceSequenceRepository extends JpaRepository<FinanceSequence, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM FinanceSequence s WHERE s.name = :name")
    Optional<FinanceSequence> lock(@Param("name") String name);
}
