package com.exam.repository;

import com.exam.model.boarding.Hostel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface HostelRepository extends JpaRepository<Hostel, Long> {

    List<Hostel> findAllByOrderByNameAsc();

    boolean existsByNameKey(String nameKey);
}
