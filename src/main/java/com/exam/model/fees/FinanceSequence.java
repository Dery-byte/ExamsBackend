package com.exam.model.fees;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** A numbering series by name and year, e.g. "RF-2026" for refund vouchers RF-2026-000001 … */
@Entity
@Table(name = "fee_sequence")
@Getter @Setter
public class FinanceSequence {

    @Id
    @Column(length = 20)
    private String name;

    @Column(nullable = false)
    private long lastNumber;
}
