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
     * Whether this program is active across the entire system.
     * When false, the program is hidden from all roles except Super Admin.
     */
    @Column(nullable = false, columnDefinition = "TINYINT(1) DEFAULT 1")
    private boolean enabled = true;

    /**
     * JSON map of level → number of semesters, e.g. {"100":3,"200":2,"300":2,"400":2}.
     * Stored as TEXT. If null/empty, each level defaults to 2 semesters.
     */
    @Column(columnDefinition = "TEXT")
    private String semestersPerLevel;

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

    /**
     * Returns the semestersPerLevel map deserialized from JSON.
     * Falls back to 2 semesters per level for any level not explicitly configured.
     */
    @Transient
    public java.util.Map<Integer, Integer> getSemestersPerLevelMap() {
        if (semestersPerLevel == null || semestersPerLevel.isBlank()) {
            return new java.util.HashMap<>();
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            java.util.Map<String, Integer> raw = mapper.readValue(semestersPerLevel,
                    mapper.getTypeFactory().constructMapType(java.util.HashMap.class, String.class, Integer.class));
            java.util.Map<Integer, Integer> result = new java.util.HashMap<>();
            raw.forEach((k, v) -> result.put(Integer.parseInt(k), v));
            return result;
        } catch (Exception e) {
            return new java.util.HashMap<>();
        }
    }

    /**
     * Serializes the given map to JSON and stores it in semestersPerLevel.
     */
    public void setSemestersPerLevelMap(java.util.Map<Integer, Integer> map) {
        if (map == null || map.isEmpty()) {
            this.semestersPerLevel = null;
            return;
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            this.semestersPerLevel = mapper.writeValueAsString(map);
        } catch (Exception e) {
            this.semestersPerLevel = null;
        }
    }

    /**
     * Returns the configured number of semesters for a given level.
     * Defaults to 2 if not explicitly set.
     */
    @Transient
    public int getSemestersForLevel(int level) {
        java.util.Map<Integer, Integer> map = getSemestersPerLevelMap();
        return map.getOrDefault(level, 2);
    }
}
