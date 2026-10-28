package com.exam.model.boarding;

import com.exam.model.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * A teacher or HOD account given boarding duties: HEAD (Senior Housemaster/Housemistress) manages
 * every hostel; HOUSE manages one hostel's students. No new login type is needed.
 */
@Entity
@Table(name = "boarding_staff",
        uniqueConstraints = @UniqueConstraint(name = "uk_boarding_staff", columnNames = {"user_id", "scope_key"}))
@Getter @Setter
public class BoardingStaff {

    public enum Duty { HEAD, HOUSE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** Null for HEAD. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hostel_id")
    private Hostel hostel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Duty duty;

    /** What students see, e.g. "Housemistress". */
    @Column(length = 60)
    private String title;

    /** "all" for HEAD, else the hostel id: one row per person per scope. */
    @Column(name = "scope_key", nullable = false, length = 20)
    private String scopeKey;

    @Column(length = 120)
    private String addedBy;

    private LocalDateTime addedAt;
}
