package com.exam.helper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TheoryGroupsTest {

    @Test
    void groupsByLeadingLabelAndNumber() {
        assertEquals("Q1", TheoryGroups.key("Q1"));
        assertEquals("Q1", TheoryGroups.key("Q1a"));
        assertEquals("Q1", TheoryGroups.key("Q1b(ii)"));
        assertEquals("Q3", TheoryGroups.key("Q3ai"));
        assertEquals("Q10", TheoryGroups.key("Q10a"));
        assertEquals("2", TheoryGroups.key("2b"));
        assertEquals("QUESTION4", TheoryGroups.key("Question4c"));
    }

    @Test
    void ignoresCaseAndSpaces() {
        assertEquals("Q1", TheoryGroups.key("q1a"));
        assertEquals("Q1", TheoryGroups.key(" Q 1 a "));
        assertEquals("Q1", TheoryGroups.key("Q 1a"));
    }

    @Test
    void numbersWithoutDigitsOrMissing() {
        assertEquals("BONUS", TheoryGroups.key("Bonus"));
        assertEquals("OTHER", TheoryGroups.key(null));
        assertEquals("OTHER", TheoryGroups.key("   "));
    }

    @Test
    void q1NeverMatchesQ10() {
        assertTrue(TheoryGroups.sameGroup("Q1a", "Q1"));
        assertFalse(TheoryGroups.sameGroup("Q10a", "Q1"));
        assertFalse(TheoryGroups.sameGroup("Q1a", "Q10"));
        assertTrue(TheoryGroups.sameGroup("q10b", "Q10"));
    }
}
