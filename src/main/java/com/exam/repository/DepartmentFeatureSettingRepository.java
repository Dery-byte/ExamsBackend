package com.exam.repository;

import com.exam.model.features.DepartmentFeatureSetting;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DepartmentFeatureSettingRepository extends JpaRepository<DepartmentFeatureSetting, Long> {
    Optional<DepartmentFeatureSetting> findByDepartment_IdAndFeatureKey(Long departmentId, String featureKey);
    List<DepartmentFeatureSetting> findByFeatureKey(String featureKey);
    List<DepartmentFeatureSetting> findByDepartment_Id(Long departmentId);
}
