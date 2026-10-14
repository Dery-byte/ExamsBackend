package com.exam.repository;

import com.exam.model.attendance.AttendancePolicy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AttendancePolicyRepository extends JpaRepository<AttendancePolicy, Long> {
    Optional<AttendancePolicy> findFirstByDepartmentIsNullOrderByIdAsc();
    Optional<AttendancePolicy> findByDepartment_Id(Long departmentId);
    List<AttendancePolicy> findByDepartmentIsNotNull();
}
