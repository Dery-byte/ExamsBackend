package com.exam.repository;

import com.exam.model.fees.FeeSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface FeeScheduleRepository extends JpaRepository<FeeSchedule, Long> {

    Optional<FeeSchedule> findByProgram_IdAndLevelAndSession_Id(Long programId, int level, Long sessionId);

    List<FeeSchedule> findBySession_Id(Long sessionId);

    @Query("SELECT DISTINCT s FROM FeeSchedule s JOIN FETCH s.program JOIN FETCH s.session LEFT JOIN FETCH s.components WHERE s.id = :id")
    Optional<FeeSchedule> findWithComponents(@Param("id") Long id);

    @Query("SELECT DISTINCT s FROM FeeSchedule s JOIN FETCH s.program JOIN FETCH s.session LEFT JOIN FETCH s.components WHERE s.session.id = :sessionId")
    List<FeeSchedule> findWithComponentsBySession(@Param("sessionId") Long sessionId);

    long countBySession_Id(Long sessionId);
}
