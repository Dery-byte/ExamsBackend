package com.exam.service;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ClassPositionTest {

    @Test
    void tiesShareAPlaceAndStudentsWithoutScoresAreLeftOut() {
        Map<Long, double[]> totals = new HashMap<>();
        totals.put(1L, new double[]{160, 2});   // avg 80
        totals.put(2L, new double[]{150, 2});   // avg 75
        totals.put(3L, new double[]{75, 1});    // avg 75 (tie)
        totals.put(4L, new double[]{60, 1});    // avg 60
        totals.put(5L, new double[]{0, 0});     // no scores

        assertThat(MarksEntryService.rank(totals, 1L)).containsExactly(1, 4);
        assertThat(MarksEntryService.rank(totals, 2L)).containsExactly(2, 4);
        assertThat(MarksEntryService.rank(totals, 3L)).containsExactly(2, 4);
        assertThat(MarksEntryService.rank(totals, 4L)).containsExactly(4, 4);
        assertThat(MarksEntryService.rank(totals, 5L)).isNull();
        assertThat(MarksEntryService.rank(totals, 99L)).isNull();
    }
}
