package com.exam.repository;

import com.exam.model.attendance.AttendanceDevice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AttendanceDeviceRepository extends JpaRepository<AttendanceDevice, Long> {
    Optional<AttendanceDevice> findByUser_Id(Long userId);
    Optional<AttendanceDevice> findByDeviceHash(String deviceHash);
}
