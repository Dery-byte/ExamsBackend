package com.exam.repository;

import com.exam.model.academic.GradingPreset;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GradingPresetRepository extends JpaRepository<GradingPreset, Long> {
    List<GradingPreset> findAllByOrderByNameAsc();
    boolean existsByNameIgnoreCase(String name);
}
