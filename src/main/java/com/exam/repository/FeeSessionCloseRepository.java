package com.exam.repository;

import com.exam.model.fees.FeeSessionClose;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FeeSessionCloseRepository extends JpaRepository<FeeSessionClose, Long> {

    Optional<FeeSessionClose> findBySession_Id(Long sessionId);

    boolean existsBySession_Id(Long sessionId);

    List<FeeSessionClose> findAllByOrderByClosedAtDesc();
}
