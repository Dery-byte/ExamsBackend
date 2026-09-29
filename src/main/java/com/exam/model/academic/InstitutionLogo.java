package com.exam.model.academic;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** The institution's crest, printed on transcripts and report cards. There is only ever one row. */
@Entity
@Table(name = "institution_logo")
@Getter @Setter
public class InstitutionLogo {

    public static final Long ID = 1L;

    @Id
    private Long id;

    @Lob
    @Column(nullable = false, columnDefinition = "LONGBLOB")
    private byte[] data;

    @Column(nullable = false, length = 30)
    private String contentType;

    private LocalDateTime updatedAt;
}
