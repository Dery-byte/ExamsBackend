package com.exam.repository;

import com.exam.model.comms.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    @Query("SELECT a FROM AuditLog a WHERE " +
           "(:actor IS NULL OR LOWER(a.actorName) LIKE LOWER(CONCAT('%', :actor, '%'))) AND " +
           "(:action IS NULL OR a.action = :action) AND " +
           "(:role IS NULL OR a.actorRole = :role) AND " +
           "(:from IS NULL OR a.createdAt >= :from) AND " +
           "(:to IS NULL OR a.createdAt < :to) " +
           "ORDER BY a.createdAt DESC")
    Page<AuditLog> search(@Param("actor") String actor, @Param("action") String action, @Param("role") String role,
                          @Param("from") LocalDateTime from, @Param("to") LocalDateTime to, Pageable pageable);

    @Query("SELECT DISTINCT a.action FROM AuditLog a ORDER BY a.action")
    List<String> findDistinctActions();
}
