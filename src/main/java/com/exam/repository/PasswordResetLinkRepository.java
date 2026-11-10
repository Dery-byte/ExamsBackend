package com.exam.repository;

import com.exam.model.PasswordResetLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

public interface PasswordResetLinkRepository extends JpaRepository<PasswordResetLink, Long> {

    Optional<PasswordResetLink> findByTokenHash(String tokenHash);

    /** After a successful reset, every other link sent to that account stops working too. */
    @Modifying
    @Transactional
    @Query("delete from PasswordResetLink l where l.userId = :userId")
    int deleteAllForUser(@Param("userId") Long userId);

    @Modifying
    @Transactional
    @Query("delete from PasswordResetLink l where l.expiresAt < :now")
    int deleteExpired(@Param("now") Instant now);
}
