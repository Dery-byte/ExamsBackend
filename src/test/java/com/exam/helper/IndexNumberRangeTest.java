package com.exam.helper;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IndexNumberRangeTest {

    private static final String FROM = "PS/ICT/17/0001";
    private static final String TO   = "PS/ICT/17/0009";

    @Test
    void includesBothEndsAndEverythingBetween() {
        assertThat(IndexNumberRange.contains(FROM, TO, "PS/ICT/17/0001")).isTrue();
        assertThat(IndexNumberRange.contains(FROM, TO, "PS/ICT/17/0005")).isTrue();
        assertThat(IndexNumberRange.contains(FROM, TO, "PS/ICT/17/0009")).isTrue();
    }

    @Test
    void excludesSerialsOutsideTheRange() {
        assertThat(IndexNumberRange.contains(FROM, TO, "PS/ICT/17/0000")).isFalse();
        assertThat(IndexNumberRange.contains(FROM, TO, "PS/ICT/17/0010")).isFalse();
    }

    @Test
    void excludesOtherProgramsAndYears() {
        assertThat(IndexNumberRange.contains(FROM, TO, "PS/ICT/18/0005")).isFalse();
        assertThat(IndexNumberRange.contains(FROM, TO, "PS/CSC/17/0005")).isFalse();
        assertThat(IndexNumberRange.contains(FROM, TO, "PS/ICT/170005")).isFalse();
        assertThat(IndexNumberRange.contains(FROM, TO, "student@example.com")).isFalse();
        assertThat(IndexNumberRange.contains(FROM, TO, null)).isFalse();
    }

    @Test
    void ignoresCaseSpacesAndLeadingZeros() {
        assertThat(IndexNumberRange.contains("ps/ict/17/0001", "ps/ict/17/0009", " PS/ICT/17/0004 ")).isTrue();
        assertThat(IndexNumberRange.contains(FROM, TO, "ps/ict/17/4")).isTrue();
        assertThat(IndexNumberRange.contains("PS/ICT/17/0095", "PS/ICT/17/0105", "PS/ICT/17/0100")).isTrue();
    }

    @Test
    void noRangeMeansEveryoneIsIn() {
        assertThat(IndexNumberRange.contains(null, null, "PS/ICT/17/0500")).isTrue();
        assertThat(IndexNumberRange.contains("", "  ", "anything")).isTrue();
    }

    @Test
    void validatesRanges() {
        assertThat(IndexNumberRange.validationError(null, null)).isNull();
        assertThat(IndexNumberRange.validationError(" ", "")).isNull();
        assertThat(IndexNumberRange.validationError(FROM, TO)).isNull();
        assertThat(IndexNumberRange.validationError(FROM, FROM)).isNull();
        assertThat(IndexNumberRange.validationError(FROM, null)).contains("both");
        assertThat(IndexNumberRange.validationError("PS/ICT/17/ABC", TO)).contains("must end in a number");
        assertThat(IndexNumberRange.validationError(FROM, "PS/ICT/18/0009")).contains("start the same way");
        assertThat(IndexNumberRange.validationError(TO, FROM)).contains("comes after");
    }

    @Test
    void normalizesToTrimmedUpperCase() {
        assertThat(IndexNumberRange.normalize("  ps/ict/17/0001 ")).isEqualTo("PS/ICT/17/0001");
        assertThat(IndexNumberRange.normalize("   ")).isNull();
    }
}
