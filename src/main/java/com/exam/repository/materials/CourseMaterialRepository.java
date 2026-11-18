package com.exam.repository.materials;

import com.exam.model.materials.CourseMaterial;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

    // ── The every-minute notices (MaterialNotices): ids only to find the work, one direct update to mark it ──

    /** Courses (spaces) with items not yet announced that may have become visible. */
    @Query("SELECT DISTINCT m.space.id FROM CourseMaterial m WHERE m.notified = false AND m.deletedAt IS NULL"
            + " AND (m.visibility = :visible OR (m.visibility = :scheduled AND m.visibleFrom <= :now))")
    List<Long> spacesWaitingToAnnounce(@Param("visible") CourseMaterial.Visibility visible,
                                       @Param("scheduled") CourseMaterial.Visibility scheduled,
                                       @Param("now") LocalDateTime now);

    @Query("SELECT m FROM CourseMaterial m WHERE m.space.id = :spaceId AND m.notified = false AND m.deletedAt IS NULL"
            + " AND (m.visibility = :visible OR (m.visibility = :scheduled AND m.visibleFrom <= :now))")
    List<CourseMaterial> waitingToAnnounceIn(@Param("spaceId") Long spaceId,
                                             @Param("visible") CourseMaterial.Visibility visible,
                                             @Param("scheduled") CourseMaterial.Visibility scheduled,
                                             @Param("now") LocalDateTime now);

    @Query("SELECT m.id FROM CourseMaterial m WHERE m.kind = :kind AND m.deletedAt IS NULL AND m.reminderSent = false"
            + " AND m.dueAt > :now AND m.dueAt <= :until")
    List<Long> dueSoonIds(@Param("kind") CourseMaterial.Kind kind, @Param("now") LocalDateTime now, @Param("until") LocalDateTime until);

    @Query("SELECT m.id FROM CourseMaterial m WHERE m.kind = :kind AND m.deletedAt IS NULL AND m.closedNotified = false"
            + " AND m.closesAt IS NOT NULL AND m.closesAt <= :now")
    List<Long> justClosedIds(@Param("kind") CourseMaterial.Kind kind, @Param("now") LocalDateTime now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE CourseMaterial m SET m.notified = true, m.publishedAt = COALESCE(m.publishedAt, :now) WHERE m.id IN :ids")
    int markAnnounced(@Param("ids") Collection<Long> ids, @Param("now") LocalDateTime now);

    /** Items of an earlier term: never announced, just marked so they aren't looked at again. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE CourseMaterial m SET m.notified = true WHERE m.id IN :ids")
    int markNotified(@Param("ids") Collection<Long> ids);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE CourseMaterial m SET m.reminderSent = true WHERE m.id = :id")
    int markReminded(@Param("id") Long id);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE CourseMaterial m SET m.closedNotified = true WHERE m.id = :id")
    int markClosedReported(@Param("id") Long id);

    @Query("SELECT m FROM CourseMaterial m WHERE m.deletedAt IS NOT NULL AND m.deletedAt < :before")
    List<CourseMaterial> binnedBefore(@Param("before") LocalDateTime before);
}
