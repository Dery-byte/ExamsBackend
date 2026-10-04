package com.exam.service;

import com.exam.DTO.UpdateQuestionDTO;
import com.exam.model.exam.MatchingPair;
import com.exam.model.exam.QuestionType;
import com.exam.model.exam.Questions;
import com.exam.repository.QuestionsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Editing a quiz question may change its type: the old type's fields are cleared and the new type's are checked. */
class QuestionTypeChangeTest {

    private QuestionsService service;
    private QuestionsRepository repo;
    private QuestionImageService images;
    private Questions stored;

    @BeforeEach
    void setUp() {
        service = new QuestionsService();
        repo = mock(QuestionsRepository.class);
        images = mock(QuestionImageService.class);
        ReflectionTestUtils.setField(service, "questionsRepository", repo);
        ReflectionTestUtils.setField(service, "questionImageService", images);

        stored = new Questions();
        stored.setQuesId(1L);
        stored.setContent("Which is an input device?");
        stored.setQuestionType(QuestionType.MCQ);
        stored.setOption1("Mouse"); stored.setOption2("Monitor"); stored.setOption3("Printer");
        stored.setcorrect_answer(new String[]{"Mouse"});
        stored.setImage("question-images/a.webp");
        when(repo.findById(1L)).thenReturn(Optional.of(stored));
        when(repo.save(any(Questions.class))).thenAnswer(a -> a.getArgument(0));
    }

    private UpdateQuestionDTO edit(QuestionType type) {
        UpdateQuestionDTO d = new UpdateQuestionDTO();
        d.setQuesId(1L);
        d.setContent("<p>Edited text</p>");
        d.setImage("question-images/a.webp");
        d.setQuestionType(type);
        return d;
    }

    private static MatchingPair pair(String prompt, String answer) {
        MatchingPair p = new MatchingPair();
        p.setPrompt(prompt);
        p.setAnswer(answer);
        p.setPairOrder(0);
        return p;
    }

    @Test
    void mcqToTrueFalse() {
        UpdateQuestionDTO d = edit(QuestionType.TRUE_FALSE);
        d.setOption1("Mouse");                       // stale MCQ options from the form are ignored
        d.setCorrect_answer(new String[]{"False"});
        service.updateQuestion(d);

        assertThat(stored.getQuestionType()).isEqualTo(QuestionType.TRUE_FALSE);
        assertThat(stored.getOption1()).isEqualTo("True");
        assertThat(stored.getOption2()).isEqualTo("False");
        assertThat(stored.getOption3()).isNull();
        assertThat(stored.getcorrect_answer()).containsExactly("False");
    }

    @Test
    void mcqToMatchingAndBack() {
        UpdateQuestionDTO d = edit(QuestionType.MATCHING);
        d.setMatchingPairs(new ArrayList<>(List.of(pair("Mouse", "Input"), pair("Printer", "Output"))));
        service.updateQuestion(d);
        assertThat(stored.getQuestionType()).isEqualTo(QuestionType.MATCHING);
        assertThat(stored.getMatchingPairs()).hasSize(2);
        assertThat(stored.getOption1()).isNull();
        assertThat(stored.getcorrect_answer()).isNull();

        UpdateQuestionDTO back = edit(QuestionType.MCQ);
        back.setOption1("Mouse"); back.setOption2("Printer");
        back.setCorrect_answer(new String[]{"Mouse"});
        service.updateQuestion(back);
        assertThat(stored.getQuestionType()).isEqualTo(QuestionType.MCQ);
        assertThat(stored.getMatchingPairs()).isEmpty();
        assertThat(stored.getcorrect_answer()).containsExactly("Mouse");
    }

    @Test
    void mcqToNumeric() {
        UpdateQuestionDTO d = edit(QuestionType.NUMERIC);
        d.setCorrect_answer(new String[]{"8"});
        d.setTolerance(0.5);
        service.updateQuestion(d);

        assertThat(stored.getQuestionType()).isEqualTo(QuestionType.NUMERIC);
        assertThat(stored.getOption1()).isNull();
        assertThat(stored.getcorrect_answer()).containsExactly("8");
        assertThat(stored.getTolerance()).isEqualTo(0.5);
    }

    @Test
    void invalidEditForTheNewTypeChangesNothing() {
        UpdateQuestionDTO d = edit(QuestionType.MATCHING);
        d.setImage(null);                            // would delete the old image if it got that far
        d.setMatchingPairs(new ArrayList<>(List.of(pair("Mouse", "Input"))));
        assertThatThrownBy(() -> service.updateQuestion(d)).hasMessage("A matching question needs at least 2 pairs.");

        assertThat(stored.getQuestionType()).isEqualTo(QuestionType.MCQ);
        assertThat(stored.getContent()).isEqualTo("Which is an input device?");
        verify(images, never()).deleteByPath(any());
        verify(repo, never()).save(any());
    }

    @Test
    void eachTypeIsChecked() {
        UpdateQuestionDTO noText = edit(QuestionType.MCQ);
        noText.setContent("<p>&nbsp;</p>");
        assertThatThrownBy(() -> QuestionsService.validateUpdate(noText)).hasMessage("Question text is required.");

        UpdateQuestionDTO tf = edit(QuestionType.TRUE_FALSE);
        assertThatThrownBy(() -> QuestionsService.validateUpdate(tf)).hasMessage("Choose True or False as the correct answer.");

        UpdateQuestionDTO mcq = edit(QuestionType.MCQ);
        mcq.setOption1("Mouse");
        assertThatThrownBy(() -> QuestionsService.validateUpdate(mcq)).hasMessageContaining("at least options A and B");
        mcq.setOption2("Monitor");
        assertThatThrownBy(() -> QuestionsService.validateUpdate(mcq)).hasMessage("Mark at least one option as correct.");
        mcq.setCorrect_answer(new String[]{"True"});   // left over from True / False
        assertThatThrownBy(() -> QuestionsService.validateUpdate(mcq)).hasMessageContaining("\"True\" is not one of the options");

        UpdateQuestionDTO match = edit(QuestionType.MATCHING);
        match.setMatchingPairs(new ArrayList<>(List.of(pair("Mouse", "Input"), pair("Printer", " "))));
        assertThatThrownBy(() -> QuestionsService.validateUpdate(match)).hasMessageContaining("both a prompt and its match");

        UpdateQuestionDTO numeric = edit(QuestionType.NUMERIC);
        numeric.setImage(null);
        numeric.setCorrect_answer(new String[]{"eight"});
        assertThatThrownBy(() -> service.updateQuestion(numeric)).hasMessage("The correct answer must be a number.");
        verify(images, never()).deleteByPath(any());   // rejected before the old image is touched
    }
}
