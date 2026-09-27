package com.exam.repository;

import com.exam.model.comms.Notification;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    @Query("SELECT n FROM Notification n WHERE n.recipient.id = :userId ORDER BY n.createdAt DESC")
    List<Notification> findRecent(@Param("userId") Long userId, Pageable pageable);

    long countByRecipientIdAndReadFalse(Long recipientId);

    @Modifying
    @Query("UPDATE Notification n SET n.read = true WHERE n.recipient.id = :userId AND n.read = false")
    int markAllRead(@Param("userId") Long userId);

    @Modifying
    @Query("UPDATE Notification n SET n.read = true WHERE n.id = :id AND n.recipient.id = :userId")
    int markRead(@Param("id") Long id, @Param("userId") Long userId);
}
