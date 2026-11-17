package com.exam.service.materials;

import com.exam.model.exam.LlmProvider;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Reading the AI's reply: fenced JSON, marks kept within range and rounded to halves, unreadable work flagged. */
class AssignmentAiMarkerTest {

    final AssignmentAiMarker marker = new AssignmentAiMarker();

    @Test
    void readsTheReplyAndKeepsTheMarkInRange() {
        AssignmentAiMarker.Result r = marker.parse("```json\n{\"score\": 7.3, \"feedback\": \"Clear answer.\", "
                + "\"breakdown\": [\"Q1: 4/5 - good\", \"Q2: 3/5 - missed a case\"], \"readable\": true}\n```",
                BigDecimal.TEN, LlmProvider.CLAUDE, List.of());
        assertThat(r.score()).isEqualByComparingTo("7.5");
        assertThat(r.feedback()).contains("Clear answer.").contains("• Q1: 4/5");

        assertThat(marker.parse("{\"score\": 14}", BigDecimal.TEN, LlmProvider.GEMINI, List.of()).score()).isEqualByComparingTo("10");
        assertThat(marker.parse("{\"score\": -2}", BigDecimal.TEN, LlmProvider.GEMINI, List.of()).score()).isEqualByComparingTo("0");
        assertThat(marker.parse("{\"score\": 0, \"feedback\": \"Blank\", \"readable\": false}", BigDecimal.TEN, LlmProvider.GPT, List.of())
                .feedback()).startsWith("⚠");
        assertThatThrownBy(() -> marker.parse("I can't do that", BigDecimal.TEN, LlmProvider.GPT, List.of()))
                .isInstanceOf(AssignmentAiMarker.AiUnavailable.class);
    }

    @Test
    void withNoKeysNothingIsConfigured() {
        assertThat(marker.providers()).allMatch(p -> Boolean.FALSE.equals(p.get("configured")));
        assertThatThrownBy(() -> marker.pick(null)).isInstanceOf(AssignmentAiMarker.AiUnavailable.class);
    }

    @Test
    void showsTheProvidersOwnErrorWithoutKeys() {
        org.springframework.test.util.ReflectionTestUtils.setField(marker, "gptKey", "sk-proj-abcdefghijklmnop");
        String said = marker.providerMessage("{\"error\":{\"message\":\"Incorrect API key provided: sk-proj-abcdefghijklmnop. Check it.\",\"type\":\"invalid_request_error\"}}");
        assertThat(said).contains("Incorrect API key provided").doesNotContain("abcdefghijklmnop");
        assertThat(marker.providerMessage("{\"error\":{\"code\":400,\"message\":\"API key not valid. Please pass a valid API key.\"}}"))
                .isEqualTo("API key not valid. Please pass a valid API key.");
        assertThat(marker.providerMessage("")).isEmpty();
    }

    @Test
    void geminiUsesTheConfiguredHostWithACurrentModel() {
        org.springframework.test.util.ReflectionTestUtils.setField(marker, "geminiUrl",
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash-latest:generateContent");
        assertThat(marker.geminiEndpoint()).isEqualTo("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent");
        assertThat(marker.readsFiles(LlmProvider.GPT)).isTrue();
        assertThat(marker.readsFiles(LlmProvider.DEEPSEEK)).isFalse();
    }

    @Test
    void describesWhatIsSentToTheAi() {
        var photo = new AssignmentAiMarker.WorkFile("p1.jpg", "image/jpeg", new byte[]{1});
        assertThat(AssignmentAiMarker.describe(List.of(new AssignmentAiMarker.Attached(photo, false)), true, false, "my answer"))
                .isEqualTo("1 photo, the text of the other files and the typed answer");
        var paper = new AssignmentAiMarker.WorkFile("questions.jpg", "image/jpeg", new byte[]{1});
        assertThat(AssignmentAiMarker.describe(List.of(new AssignmentAiMarker.Attached(paper, true), new AssignmentAiMarker.Attached(photo, false)), false, false, ""))
                .isEqualTo("the question paper (1 page) and 1 photo");
    }

    @Test
    void theQuestionPaperIsSentApartFromTheStudentsWork() {
        var paper = new AssignmentAiMarker.WorkFile("questions.pdf", "application/pdf", new byte[]{1});
        var work = new AssignmentAiMarker.WorkFile("answers.pdf", "application/pdf", new byte[]{1});
        var inline = List.of(new AssignmentAiMarker.Attached(paper, true), new AssignmentAiMarker.Attached(work, false));
        // Labels go before each part, in order
        assertThat(AssignmentAiMarker.labelBefore(inline, 0)).isEqualTo(AssignmentAiMarker.QUESTION_LABEL);
        assertThat(AssignmentAiMarker.labelBefore(inline, 1)).isEqualTo(AssignmentAiMarker.WORK_LABEL);
        var task = new AssignmentAiMarker.Task("CSC 201", "Lab 1", null, BigDecimal.TEN, "Q1 5 marks", "", List.of(work), List.of(paper));
        String prompt = AssignmentAiMarker.prompt(task, "", "", "", inline, true, List.of());
        assertThat(prompt).contains("(none typed: the questions are in the question paper)")
                .contains("QUESTION PAPER:").contains("\"questions.pdf\"").contains("Mark each answer against these questions")
                .contains("<student_work>").doesNotContain("the attached assignment files hold the questions");
        // A text-only AI gets the paper's text inside its own tags
        String textOnly = AssignmentAiMarker.prompt(task, "", "", "Q1. Define a node.", List.of(), true, List.of());
        assertThat(textOnly).contains("<question_paper>Q1. Define a node.").contains("</question_paper>");
        // No questions anywhere: the AI is told so
        var bare = new AssignmentAiMarker.Task("CSC 201", "Lab 1", null, BigDecimal.TEN, null, "answer", List.of());
        assertThat(AssignmentAiMarker.prompt(bare, "answer", "", "", List.of(), false, List.of())).contains("no questions were given");
    }

    @Test
    void takesTextOutOfTypedFiles() {
        String text = AssignmentAiMarker.extractText(new AssignmentAiMarker.WorkFile("a.txt", "text/plain; charset=utf-8",
                "Linked lists use nodes.".getBytes(StandardCharsets.UTF_8)));
        assertThat(text).contains("Linked lists");
    }
}
