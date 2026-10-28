package com.exam.repository;

import com.exam.model.boarding.BoardingStaff;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface BoardingStaffRepository extends JpaRepository<BoardingStaff, Long> {

    @Query("SELECT s FROM BoardingStaff s LEFT JOIN FETCH s.hostel WHERE s.user.id = :userId")
    List<BoardingStaff> findForUser(@Param("userId") Long userId);

    @Query("SELECT s FROM BoardingStaff s JOIN FETCH s.user LEFT JOIN FETCH s.hostel ORDER BY s.duty ASC, s.id ASC")
    List<BoardingStaff> findAllWithHostel();

    boolean existsByUser_IdAndScopeKey(Long userId, String scopeKey);

    @Modifying
    @Query("DELETE FROM BoardingStaff s WHERE s.user.id = :userId")
    int deleteForUser(@Param("userId") Long userId);

    @Modifying
    @Query("DELETE FROM BoardingStaff s WHERE s.hostel.id = :hostelId")
    int deleteForHostel(@Param("hostelId") Long hostelId);
}
