package com.exam.model.attendance;

import com.exam.model.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * The phone a student checks in with, linked on their first check-in. A phone serves one student
 * only, and a student checks in from their linked phone only, until staff reset the link in class.
 * The browser's random device id is stored hashed.
 */
@Entity
@Table(name = "attendance_device")
@Getter @Setter
public class AttendanceDevice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", unique = true)
    private User user;

    @Column(name = "device_hash", nullable = false, unique = true, length = 64)
    private String deviceHash;

    @Column(nullable = false)
    private LocalDateTime linkedAt;

    private LocalDateTime lastUsedAt;
}
