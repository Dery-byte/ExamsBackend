package com.exam.service.materials;

import com.exam.model.exam.LlmProvider;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Comparing classmates' work: copied passages are found (even reworded around), question wording isn't counted. */
class SimilarityCheckTest {

    static final String QUESTION = "Explain how a linked list stores its elements and compare insertion at the head of a linked list with insertion into an array.";

    static final String AMA = "Explain how a linked list stores its elements and compare insertion at the head of a linked list with insertion into an array. "
            + "A linked list keeps each value inside a node, and every node holds a reference to the node that comes after it in the chain. "
            + "Because the nodes do not sit next to each other in memory the list can grow one node at a time without moving anything else. "
            + "To insert at the head we create a new node, point it at the current first node and then make the head reference point to the new node, "
            + "which takes constant time no matter how long the list is. An array on the other hand must shift every existing element one place "
            + "to the right before the new value can go into position zero, so insertion at the front costs time proportional to the length.";

    /** Kofi copied Ama's middle part and wrote his own opening and ending. */
    static final String KOFI = "My answer is about lists. In a singly linked structure, data lives in separate pieces. "
            + "Because the nodes do not sit next to each other in memory the list can grow one node at a time without moving anything else. "
            + "To insert at the head we create a new node, point it at the current first node and then make the head reference point to the new node, "
            + "which takes constant time no matter how long the list is. Arrays are different and slower for this, as I learnt in the lab last week "
            + "when we timed both approaches with ten thousand items and saw the array version fall far behind as the size grew.";

    /** Esi wrote her own answer, using the same question wording. */
    static final String ESI = "Explain how a linked list stores its elements and compare insertion at the head of a linked list with insertion into an array. "
            + "Think of a treasure hunt: every clue tells you where the next clue is hidden, and that is what each element of a linked structure does. "
            + "Adding a new first clue is easy because you only write one new note that points to the old first clue and hand it to the player. "
            + "With a row of numbered lockers you would have to move everything along by one locker to free the first locker, which gets slow "
            + "as the row of lockers becomes longer, so the list wins for this particular job while the lockers win when you need item number fifty.";

    @Test
    void findsCopiedPassagesButNotSharedQuestionWording() {
        Map<Long, List<SimilarityCheck.Match>> m = SimilarityCheck.compare(List.of(
                new SimilarityCheck.Text(1L, "Ama", AMA), new SimilarityCheck.Text(2L, "Kofi", KOFI), new SimilarityCheck.Text(3L, "Esi", ESI)), QUESTION);
        SimilarityCheck.Match amaKofi = m.get(1L).get(0);
        assertThat(amaKofi.name()).isEqualTo("Kofi");
        assertThat(amaKofi.percent()).isGreaterThanOrEqualTo(SimilarityCheck.FLAG_PERCENT);
        // Ama and Esi share only the question's words, which are left out
        assertThat(m.getOrDefault(3L, List.of())).allMatch(x -> x.percent() < SimilarityCheck.FLAG_PERCENT);

        List<SimilarityCheck.Passage> shared = SimilarityCheck.sharedPassages(KOFI, AMA, QUESTION, 3);
        assertThat(shared).isNotEmpty();
        assertThat(shared.get(0).text()).contains("insert at the head we create a new node");
    }

    @Test
    void shortAnswersAreNotCompared() {
        Map<Long, List<SimilarityCheck.Match>> m = SimilarityCheck.compare(List.of(
                new SimilarityCheck.Text(1L, "A", "A node holds a value and a pointer."), new SimilarityCheck.Text(2L, "B", "A node holds a value and a pointer.")), "");
        assertThat(m).isEmpty();
    }

    @Test
    void spotsChatbotLeftovers() {
        assertThat(SimilarityCheck.chatbotPhrases("Certainly! Here is your essay. As an AI language model, I can’t browse."))
                .contains("certainly! here", "as an ai language model");
        assertThat(SimilarityCheck.chatbotPhrases(ESI)).isEmpty();
    }

    @Test
    void readsTheAiWritingNoteApartFromTheMark() {
        AssignmentAiMarker marker = new AssignmentAiMarker();
        AssignmentAiMarker.Result r = marker.parse("{\"score\": 6, \"feedback\": \"Good.\", \"ai_writing\": {\"likelihood\": \"medium\", "
                + "\"signals\": [\"Generic answer that never uses the course's own example\"]}}", BigDecimal.TEN, LlmProvider.CLAUDE, List.of());
        assertThat(r.score()).isEqualByComparingTo("6");
        assertThat(r.aiWriting()).isEqualTo("MEDIUM");
        assertThat(r.aiWritingNotes()).contains("Generic answer");
        assertThat(r.feedback()).doesNotContain("Generic answer");          // never shown to the student
        assertThat(marker.parse("{\"score\": 6}", BigDecimal.TEN, LlmProvider.CLAUDE, List.of()).aiWriting()).isNull();
    }
}
