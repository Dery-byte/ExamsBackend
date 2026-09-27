package com.exam.repository;

import com.exam.model.academic.DegreeClass;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DegreeClassRepository extends JpaRepository<DegreeClass, Long> {
    List<DegreeClass> findAllByOrderByMinCgpaDesc();
}
