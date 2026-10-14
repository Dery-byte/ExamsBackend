package com.exam.model.attendance;

import com.exam.model.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** Wrong or expired codes a student typed in one meeting; at the limit they are locked out until the lecturer unlocks them. */
@Entity
@Table(name = "attendance_strike",
        uniqueConstraints = @UniqueConstraint(name = "uk_att_strike", columnNames = {"session_id", "student_id"}))
@Getter @Setter
public class AttendanceStrike {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id")
    private AttendanceSession session;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id")
    private User student;

    private int failures;

    private LocalDateTime lastAt;
}
