package com.exam.DTO;

import java.util.List;

/**
 * The minimal, safe-to-show-before-login summary of a quiz — used on the shared quiz link's
 * sign-in page. Deliberately excludes the passkey, proctoring settings and anything else the
 * full Quiz entity carries.
 */
public record QuizPublicSummaryDTO(
        Long qId,
        String title,
        String courseTitle,
        /** Names of the programs allowed to take this quiz; empty means open to every program. */
        List<String> programNames
) {}
