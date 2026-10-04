package com.exam.helper;

import com.exam.DTO.TheoryQuestionResponseDTO;
import com.exam.model.exam.TheoryQuestions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** A theory question's marking criteria reach staff only; students receive null. */
class TheoryCriteriaVisibilityTest {

    private final ObjectMapper json = new ObjectMapper();

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    private void signInAs(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "someone", null, List.of(new SimpleGrantedAuthority(role))));
    }

    private JsonNode entity() throws Exception {
        TheoryQuestions q = new TheoryQuestions();
        q.setQuesNo("Q1a");
        q.setQuestion("Define an operating system.");
        q.setMarks("4");
        q.setEvaluationCriteria("Manages hardware and software.");
        return json.readTree(json.writeValueAsString(q));
    }

    private JsonNode dto() throws Exception {
        TheoryQuestionResponseDTO d = new TheoryQuestionResponseDTO();
        d.setQuesNo("Q1a");
        d.setEvaluationCriteria("Manages hardware and software.");
        return json.readTree(json.writeValueAsString(d));
    }

    @Test
    void studentGetsNull() throws Exception {
        signInAs("NORMAL");
        assertTrue(entity().get("evaluationCriteria").isNull());
        assertTrue(dto().get("evaluationCriteria").isNull());
        assertEquals("Q1a", dto().get("quesNo").asText());
    }

    @Test
    void anonymousGetsNull() throws Exception {
        assertTrue(entity().get("evaluationCriteria").isNull());
        assertTrue(dto().get("evaluationCriteria").isNull());
    }

    @Test
    void staffGetCriteria() throws Exception {
        for (String role : List.of("LECTURER", "ADMIN", "SUPER_ADMIN")) {
            signInAs(role);
            assertEquals("Manages hardware and software.", entity().get("evaluationCriteria").asText(), role);
            assertEquals("Manages hardware and software.", dto().get("evaluationCriteria").asText(), role);
        }
    }
}
