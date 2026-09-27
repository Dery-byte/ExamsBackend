package com.exam.model.examops;

import com.exam.model.User;
import com.exam.model.exam.Quiz;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** A student entered the correct quiz password; lets them start one new attempt shortly afterwards. */
@Entity
@Table(name = "quiz_unlock", indexes = @Index(name = "idx_unlock_user_quiz", columnList = "user_id, quiz_id"))
@Getter @Setter
public class QuizUnlock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "quiz_id")
    private Quiz quiz;

    @Column(name = "unlocked_at", nullable = false)
    private LocalDateTime unlockedAt = LocalDateTime.now();
}
