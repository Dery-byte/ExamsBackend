package com.exam.model.exam;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Entity
@Table(name = "program")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Program {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;           // e.g. "Computer Science BS"

    @Column(nullable = false, length = 20)
    private String code;           // e.g. "CS"

    @Column(nullable = false)
    private int durationYears;     // e.g. 4 → levels 100, 200, 300, 400

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "department_id", nullable = false)
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
    private Department department;

    /**
     * Derives the configured academic levels based on durationYears.
     * E.g. durationYears=4 → [100, 200, 300, 400]
     */
    @Transient
    public List<Integer> getConfiguredLevels() {
        return IntStream.rangeClosed(1, durationYears)
                .map(i -> i * 100)
                .boxed()
                .collect(Collectors.toList());
    }
}
