package com.exam.model.comms;

import com.exam.model.User;
import com.exam.model.exam.Department;
import com.exam.model.exam.Program;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A message posted by the Super Admin (any scope) or an HOD (own department only).
 * Optional department / program / level narrow who sees it.
 */
@Entity
@Table(name = "announcement")
@Getter @Setter
public class Announcement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String body;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "author_id")
    private User author;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AnnouncementAudience audience = AnnouncementAudience.ALL;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "department_id")
    private Department department;

    /** Students only: restrict to one program. */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "program_id")
    private Program program;

    /** Students only: restrict to one level (100, 200 …). */
    private Integer level;

    private boolean pinned = false;

    /** Hidden from readers after this date (inclusive). Null = never expires. */
    private LocalDate expiresOn;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
