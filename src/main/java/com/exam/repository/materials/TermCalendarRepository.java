package com.exam.repository.materials;

import com.exam.model.materials.TermCalendar;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TermCalendarRepository extends JpaRepository<TermCalendar, Long> {
    Optional<TermCalendar> findByAcademicSession_IdAndSemester(Long sessionId, int semester);
    List<TermCalendar> findByAcademicSession_IdOrderBySemester(Long sessionId);
}
