package com.exam.repository;

import com.exam.model.exam.MarkSheetSection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MarkSheetSectionRepository extends JpaRepository<MarkSheetSection, Long> {
}
