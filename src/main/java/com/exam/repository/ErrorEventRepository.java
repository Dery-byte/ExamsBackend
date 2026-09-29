package com.exam.repository;

import com.exam.model.monitoring.ErrorEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ErrorEventRepository extends JpaRepository<ErrorEvent, Long> {
    Optional<ErrorEvent> findByFingerprint(String fingerprint);
    List<ErrorEvent> findAllByOrderByLastSeenDesc(Pageable page);
    List<ErrorEvent> findByResolvedOrderByLastSeenDesc(boolean resolved, Pageable page);
    long countByResolvedFalse();
    long countByLastSeenAfter(Instant since);
    long countByResolvedFalseAndLastSeenAfter(Instant since);

    @Transactional
    long deleteByResolvedTrue();
}
