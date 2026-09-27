package com.exam.model.comms;

import com.exam.model.User;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** An in-app notification for one user (shown under the bell icon). */
@Entity
@Table(name = "notification", indexes = @Index(name = "idx_notification_recipient", columnList = "recipient_id, created_at"))
@Getter @Setter
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipient_id")
    @JsonIgnore
    private User recipient;

    @Column(nullable = false)
    private String title;

    @Column(length = 1000)
    private String message;

    /** Frontend route to open when the notification is clicked (may be null). */
    private String link;

    /** Category of event, e.g. QUIZ_PUBLISHED, RESULT_RELEASED, SHEET_SUBMITTED, ANNOUNCEMENT. */
    private String type;

    @Column(name = "is_read", nullable = false)
    private boolean read = false;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
