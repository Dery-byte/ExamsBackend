package com.exam.model.monitoring;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * History of the institution's subscription: keys installed, state changes (active → grace → read-only
 * → suspended), manual suspensions, extensions and reminder emails. Shown in the developer console.
 */
@Entity
@Table(name = "subscription_event", indexes = @Index(name = "idx_subscription_event_at", columnList = "at"))
@Getter @Setter
public class SubscriptionEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDateTime at;

    @Column(nullable = false, length = 40)
    private String type;

    @Column(name = "from_state", length = 20)
    private String fromState;

    @Column(name = "to_state", length = 20)
    private String toState;

    @Column(length = 500)
    private String details;

    /** Who did it; blank for the automatic daily check. */
    @Column(length = 120)
    private String actor;
}
