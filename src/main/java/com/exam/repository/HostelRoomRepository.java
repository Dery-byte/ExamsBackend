package com.exam.repository;

import com.exam.model.boarding.HostelRoom;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface HostelRoomRepository extends JpaRepository<HostelRoom, Long> {

    @Query("SELECT r FROM HostelRoom r JOIN FETCH r.hostel LEFT JOIN FETCH r.block WHERE r.hostel.id = :hostelId")
    List<HostelRoom> findForHostel(@Param("hostelId") Long hostelId);

    @Query("SELECT r FROM HostelRoom r JOIN FETCH r.hostel LEFT JOIN FETCH r.block WHERE r.active = true AND r.hostel.active = true")
    List<HostelRoom> findAllInUse();

    /** Serialises allocations into one room, so two staff can't both take its last bed. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM HostelRoom r WHERE r.id = :id")
    Optional<HostelRoom> lockById(@Param("id") Long id);

    boolean existsByHostel_IdAndNameKey(Long hostelId, String nameKey);

    boolean existsByBlock_Id(Long blockId);

    boolean existsByHostel_Id(Long hostelId);
}
