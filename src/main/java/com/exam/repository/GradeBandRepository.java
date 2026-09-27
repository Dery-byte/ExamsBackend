package com.exam.repository;

import com.exam.model.academic.GradeBand;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GradeBandRepository extends JpaRepository<GradeBand, Long> {
    List<GradeBand> findAllByOrderByMinScoreDesc();
}
