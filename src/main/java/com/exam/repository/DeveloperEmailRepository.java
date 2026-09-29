package com.exam.repository;

import com.exam.model.monitoring.DeveloperEmail;
import org.springframework.data.jpa.repository.JpaRepository;

/** Read-only for the application: developers are added and removed directly in the database. */
public interface DeveloperEmailRepository extends JpaRepository<DeveloperEmail, Long> {
}
