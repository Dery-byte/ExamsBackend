package com.exam.repository;

import com.exam.model.attendance.AttendanceDevice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AttendanceDeviceRepository extends JpaRepository<AttendanceDevice, Long> {
    Optional<AttendanceDevice> findByUser_Id(Long userId);
    Optional<AttendanceDevice> findByDeviceHash(String deviceHash);

    /** [userId, linkedAt, lastUsedAt] for a class list in one query. */
    @org.springframework.data.jpa.repository.Query("SELECT d.user.id, d.linkedAt, d.lastUsedAt FROM AttendanceDevice d WHERE d.user.id IN :userIds")
    java.util.List<Object[]> linksFor(@org.springframework.data.repository.query.Param("userIds") java.util.Collection<Long> userIds);
}
