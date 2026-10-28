package com.exam.repository;

import com.exam.model.boarding.HostelBlock;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface HostelBlockRepository extends JpaRepository<HostelBlock, Long> {

    List<HostelBlock> findByHostel_IdOrderByNameAsc(Long hostelId);

    boolean existsByHostel_IdAndNameKey(Long hostelId, String nameKey);
}
