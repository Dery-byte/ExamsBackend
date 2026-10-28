package com.exam.repository;

import com.exam.model.fees.ReceiptSequence;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ReceiptSequenceRepository extends JpaRepository<ReceiptSequence, Integer> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM ReceiptSequence r WHERE r.year = :year")
    Optional<ReceiptSequence> lockYear(@Param("year") Integer year);
}
