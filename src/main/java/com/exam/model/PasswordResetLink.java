package com.exam.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * A "forgot password" link that has been sent and not used yet. Kept in the database (not in server
 * memory) so links still work after a restart or redeploy. Only a SHA-256 hash of the token is
 * stored, so someone reading the table cannot use the links. One use, then deleted.
 */
@Entity
@Table(name = "password_reset_link",
       uniqueConstraints = @UniqueConstraint(columnNames = "tokenHash"),
       indexes = @Index(name = "idx_password_reset_link_expires", columnList = "expiresAt"))
@Getter @Setter
public class PasswordResetLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** SHA-256 (hex) of the token in the link. */
    @Column(nullable = false, length = 64)
    private String tokenHash;

    @Column(nullable = false)
    private Long userId;

    /** The phone number or email address the link was sent to. */
    @Column(nullable = false, length = 150)
    private String sentTo;

    @Column(nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private Instant createdAt;

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }
}
