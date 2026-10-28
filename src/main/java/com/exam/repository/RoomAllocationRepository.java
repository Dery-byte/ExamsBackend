package com.exam.repository;

import com.exam.model.boarding.RoomAllocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RoomAllocationRepository extends JpaRepository<RoomAllocation, Long> {

    String WITH_PLACE = "SELECT a FROM RoomAllocation a JOIN FETCH a.student JOIN FETCH a.room r JOIN FETCH r.hostel LEFT JOIN FETCH r.block ";

    @Query(WITH_PLACE + "WHERE a.endedAt IS NULL AND a.student.id = :studentId")
    Optional<RoomAllocation> findCurrent(@Param("studentId") Long studentId);

    @Query(WITH_PLACE + "WHERE a.endedAt IS NULL AND a.student.id IN :studentIds")
    List<RoomAllocation> findCurrentFor(@Param("studentIds") Collection<Long> studentIds);

    @Query(WITH_PLACE + "WHERE a.endedAt IS NULL")
    List<RoomAllocation> findAllCurrent();

    @Query(WITH_PLACE + "WHERE a.endedAt IS NULL AND r.hostel.id = :hostelId")
    List<RoomAllocation> findCurrentInHostel(@Param("hostelId") Long hostelId);

    @Query(WITH_PLACE + "WHERE a.endedAt IS NULL AND r.id = :roomId")
    List<RoomAllocation> findCurrentInRoom(@Param("roomId") Long roomId);

    @Query(WITH_PLACE + "WHERE r.id = :roomId ORDER BY a.startedAt DESC, a.id DESC")
    List<RoomAllocation> historyOfRoom(@Param("roomId") Long roomId);

    @Query(WITH_PLACE + "WHERE a.student.id = :studentId ORDER BY a.startedAt DESC, a.id DESC")
    List<RoomAllocation> historyOfStudent(@Param("studentId") Long studentId);

    @Query("SELECT a.room.hostel.id, COUNT(a) FROM RoomAllocation a WHERE a.endedAt IS NULL GROUP BY a.room.hostel.id")
    List<Object[]> countCurrentByHostel();

    @Query("SELECT COUNT(a) FROM RoomAllocation a WHERE a.endedAt IS NULL AND a.room.id = :roomId")
    long countCurrentInRoom(@Param("roomId") Long roomId);

    boolean existsByRoom_Id(Long roomId);

    boolean existsByRoom_Hostel_Id(Long hostelId);

    @Modifying
    @Query("DELETE FROM RoomAllocation a WHERE a.student.id = :studentId")
    int deleteForStudent(@Param("studentId") Long studentId);
}
