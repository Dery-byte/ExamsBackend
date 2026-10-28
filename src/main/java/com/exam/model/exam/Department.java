package com.exam.model.exam;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "department")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Department {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(nullable = false, unique = true, length = 20)
    private String code;

    @Column(length = 500)
    private String description;

    /** Place in the list; schools use it to put Primary before JHS before SHS. Null = after the numbered ones. */
    @Column(name = "sort_order")
    private Integer sortOrder;

    /** Schools: how many years the section runs (Primary 6, JHS 3 …); its classes sit in years 1 to this. */
    @Column(name = "duration_years")
    private Integer durationYears;
}
