package com.exam.model.exam;

import jakarta.persistence.*;

/**
 * A WebP image attached to a question. Stored in the database (not on disk) so it
 * survives redeploys on hosts with ephemeral file systems.
 * The question's {@code image} column holds the public path "question-images/{id}.webp".
 */
@Entity
@Table(name = "question_images")
public class QuestionImage {

    @Id
    @Column(length = 36)
    private String id;

    @Lob
    @Column(nullable = false, columnDefinition = "LONGBLOB")
    private byte[] data;

    public QuestionImage() {}

    public QuestionImage(String id, byte[] data) {
        this.id = id;
        this.data = data;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public byte[] getData() { return data; }
    public void setData(byte[] data) { this.data = data; }
}
