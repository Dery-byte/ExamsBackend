package com.exam.repository;

import com.exam.model.attendance.ClassSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ClassScheduleRepository extends JpaRepository<ClassSchedule, Long> {
    List<ClassSchedule> findAllByOrderByDayOfWeekAscStartTimeAsc();

    /** A deleted classroom leaves its meetings without a room rather than blocking the delete. */
    @Modifying
    @Query("UPDATE ClassSchedule s SET s.classroom = null WHERE s.classroom.id = :classroomId")
    int detachClassroom(@Param("classroomId") Long classroomId);
}
