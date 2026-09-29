package com.exam.model.monitoring;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Who may sign in as the developer. Rows are added and removed directly in the database; nothing in
 * the application (not even the Super Admin) can change this table.
 * <pre>
 *   INSERT INTO developer_email (email, name) VALUES ('you@example.com', 'Your Name');
 *   DELETE FROM developer_email WHERE email = 'you@example.com';
 * </pre>
 */
@Entity
@Table(name = "developer_email", uniqueConstraints = @UniqueConstraint(columnNames = "email"))
@Getter @Setter
public class DeveloperEmail {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String email;

    @Column(length = 100)
    private String name;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
