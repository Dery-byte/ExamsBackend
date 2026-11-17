package com.exam.repository.materials;

import com.exam.model.materials.CourseMaterial;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface CourseMaterialRepository extends JpaRepository<CourseMaterial, Long> {

    @Query("SELECT m FROM CourseMaterial m WHERE m.space.id = :spaceId AND m.deletedAt IS NULL"
            + " ORDER BY m.weekNumber, m.pinned DESC, m.sortOrder, m.id")
    List<CourseMaterial> live(@Param("spaceId") Long spaceId);

    @Query("SELECT m FROM CourseMaterial m WHERE m.space.id IN :spaceIds AND m.deletedAt IS NULL")
    List<CourseMaterial> liveIn(@Param("spaceIds") Collection<Long> spaceIds);

    @Query("SELECT m FROM CourseMaterial m WHERE m.space.id = :spaceId AND m.deletedAt IS NOT NULL ORDER BY m.deletedAt DESC")
    List<CourseMaterial> bin(@Param("spaceId") Long spaceId);

    /** Items not yet announced that may have become visible (the scheduler checks weeks and the course switch). */
    @Query("SELECT m FROM CourseMaterial m WHERE m.notified = false AND m.deletedAt IS NULL"
            + " AND (m.visibility = :visible OR (m.visibility = :scheduled AND m.visibleFrom <= :now))")
    List<CourseMaterial> waitingToAnnounce(@Param("visible") CourseMaterial.Visibility visible,
                                           @Param("scheduled") CourseMaterial.Visibility scheduled,
                                           @Param("now") LocalDateTime now);

    @Query("SELECT m FROM CourseMaterial m WHERE m.kind = :kind AND m.deletedAt IS NULL AND m.reminderSent = false"
            + " AND m.dueAt > :now AND m.dueAt <= :until")
    List<CourseMaterial> dueSoon(@Param("kind") CourseMaterial.Kind kind, @Param("now") LocalDateTime now,
                                 @Param("until") LocalDateTime until);

    @Query("SELECT m FROM CourseMaterial m WHERE m.kind = :kind AND m.deletedAt IS NULL AND m.closedNotified = false"
            + " AND m.closesAt IS NOT NULL AND m.closesAt <= :now")
    List<CourseMaterial> justClosed(@Param("kind") CourseMaterial.Kind kind, @Param("now") LocalDateTime now);

    @Query("SELECT m FROM CourseMaterial m WHERE m.deletedAt IS NOT NULL AND m.deletedAt < :before")
    List<CourseMaterial> binnedBefore(@Param("before") LocalDateTime before);
}
