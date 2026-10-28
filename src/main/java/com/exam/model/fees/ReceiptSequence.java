package com.exam.model.fees;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** The last receipt number given in a year; locked while the next one is taken, so numbers never repeat or skip. */
@Entity
@Table(name = "fee_receipt_sequence")
@Getter @Setter
public class ReceiptSequence {

    @Id
    @Column(name = "receipt_year")
    private Integer year;

    @Column(name = "last_number", nullable = false)
    private long lastNumber;
}
