package com.exam.model.features;

import com.exam.model.exam.Department;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** A department's own on/off choice for a DEPARTMENT-scoped feature (absent = follow the system-wide switch). */
@Entity
@Table(name = "department_feature_setting",
        uniqueConstraints = @UniqueConstraint(columnNames = {"department_id", "feature_key"}))
@Getter @Setter
public class DepartmentFeatureSetting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "department_id")
    private Department department;

    @Column(name = "feature_key", nullable = false, length = 60)
    private String featureKey;

    @Column(nullable = false)
    private boolean enabled;

    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();
}
