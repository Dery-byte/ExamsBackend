package com.exam.model.materials;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * A file kept on disk (MaterialStorage), with its details here: a material's attachment, a student's
 * submitted work, or the lecturer's marked copy returned to the student. The same content (sha256)
 * is stored once on disk however many rows point at it (copies from an earlier term share it).
 */
@Entity
@Table(name = "material_file", indexes = {
        @Index(name = "idx_mfile_material", columnList = "material_id"),
        @Index(name = "idx_mfile_submission", columnList = "submission_id"),
        @Index(name = "idx_mfile_sha", columnList = "sha256")})
@Getter @Setter
public class StoredFile {

    public enum Role { ATTACHMENT, SUBMISSION, FEEDBACK }

    @Id
    @Column(length = 36)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "material_id")
    private CourseMaterial material;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "submission_id")
    private AssignmentSubmission submission;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Role role;

    @Column(nullable = false, length = 255)
    private String originalName;

    @Column(nullable = false, length = 120)
    private String contentType;

    @Column(nullable = false)
    private long sizeBytes;

    @Column(nullable = false, length = 64)
    private String sha256;

    private Long uploadedById;
    private LocalDateTime createdAt = LocalDateTime.now();
}
