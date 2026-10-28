package com.exam.repository;

import com.exam.model.fees.FeeReminder;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface FeeReminderRepository extends JpaRepository<FeeReminder, Long> {

    /** Students reminded since then (they are not reminded again yet). */
    @Query("SELECT DISTINCT r.student.id FROM FeeReminder r WHERE r.sentAt >= :since")
    List<Long> remindedSince(@Param("since") LocalDateTime since);

    /** [batch, sentAt, sentBy, automatic, byEmail, bySms, count, total owed] per send, newest first. */
    @Query("SELECT r.batch, MIN(r.sentAt), MIN(r.sentBy), MIN(CASE WHEN r.automatic = true THEN 1 ELSE 0 END), "
            + "MAX(CASE WHEN r.byEmail = true THEN 1 ELSE 0 END), MAX(CASE WHEN r.bySms = true THEN 1 ELSE 0 END), COUNT(r), SUM(r.owed) "
            + "FROM FeeReminder r GROUP BY r.batch ORDER BY MIN(r.sentAt) DESC")
    List<Object[]> batches(Pageable page);

    @Query("SELECT MAX(r.sentAt) FROM FeeReminder r WHERE r.automatic = true")
    Optional<LocalDateTime> lastAutomatic();

    @Query("SELECT r FROM FeeReminder r WHERE r.student.id = :studentId ORDER BY r.sentAt DESC")
    List<FeeReminder> findForStudent(@Param("studentId") Long studentId, Pageable page);
}
