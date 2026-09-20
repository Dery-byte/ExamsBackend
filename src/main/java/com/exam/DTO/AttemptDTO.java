package com.exam.DTO;

import com.exam.model.exam.AttemptStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** One attempt as shown to staff. */
public record AttemptDTO(
        Long id,
        Long userId,
        String username,
        String fullName,
        int attemptNumber,
        AttemptStatus status,
        LocalDateTime startedAt,
        LocalDateTime submittedAt,
        BigDecimal marksA,
        BigDecimal marksB,
        LocalDateTime voidedAt,
        String voidedByName,
        String voidReason
) {}
