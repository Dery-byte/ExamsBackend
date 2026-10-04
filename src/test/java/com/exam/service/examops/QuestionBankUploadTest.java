package com.exam.service.examops;

import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.exam.Category;
import com.exam.model.exam.QuestionType;
import com.exam.model.examops.BankQuestion;
import com.exam.repository.*;
import com.exam.service.QuestionImageService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/** Bulk upload into the bank accepts the quiz upload templates (objective and theory) and reports problems by question. */
class QuestionBankUploadTest {

    private QuestionBankService service;
    private final List<BankQuestion> bank = new ArrayList<>();
    private User lecturer;
    private long nextId = 1;

    /** The Section A quiz template plus a per-question topic / difficulty on the first item. */
    private static final String OBJECTIVE = """
            [
              { "questionType": "MCQ", "content": "Which of these is an input device?", "topic": "Hardware", "difficulty": "easy",
                "option1": "Monitor", "option2": "Keyboard", "option3": "Printer", "option4": "Speaker", "correct_answer": ["Keyboard"] },
              { "questionType": "MCQ", "content": "Which are programming languages?",
                "option1": "Python", "option2": "HTML", "option3": "Java", "option4": "Excel", "correct_answer": ["Python", "Java"] },
              { "questionType": "TRUE_FALSE", "content": "RAM keeps its data when switched off.", "correct_answer": ["False"] },
              { "questionType": "MATCHING", "content": "Match each device to its category.", "matchingPairs": [
                  { "prompt": "Printer", "answer": "Output device", "pairOrder": 1 },
                  { "prompt": "Mouse", "answer": "Input device", "pairOrder": 0 } ] },
              { "questionType": "FILL_BLANK", "content": "The brain of the computer is the ______.", "correct_answer": ["CPU", "processor"] },
              { "questionType": "NUMERIC", "content": "How many bits in a byte?", "correct_answer": ["8"], "tolerance": 0 }
            ]""";

    /** The Section B quiz template: quesNo and isCompulsory are quiz-only and ignored. */
    private static final String THEORY = """
            [
              { "quesNo": "Q1a", "question": "Define an operating system.", "marks": "4",
                "evaluationCriteria": "Manages hardware and software.", "isCompulsory": true },
              { "quesNo": "Q2", "question": "Explain RAM and ROM.", "marks": "6 marks" },
              { "questionType": "THEORY", "content": "What is a network?", "marks": 3, "topic": "Networks" }
            ]""";

    @BeforeEach
    void setUp() {
        service = new QuestionBankService();
        BankQuestionRepository bankRepo = mock(BankQuestionRepository.class);
        CategoryRepository categories = mock(CategoryRepository.class);
        ReflectionTestUtils.setField(service, "bankRepository", bankRepo);
        ReflectionTestUtils.setField(service, "categoryRepository", categories);
        ReflectionTestUtils.setField(service, "quizRepository", mock(QuizRepository.class));
        ReflectionTestUtils.setField(service, "questionsRepository", mock(QuestionsRepository.class));
        ReflectionTestUtils.setField(service, "theoryRepository", mock(TheoryQuestionsRepository.class));
        ReflectionTestUtils.setField(service, "questionImageService", mock(QuestionImageService.class));

        lecturer = new User();
        lecturer.setId(1L);
        lecturer.setRole(Role.SUPER_ADMIN);
        Category course = new Category();
        course.setCid(10L);

        when(categories.findById(10L)).thenReturn(Optional.of(course));
        when(bankRepo.save(any(BankQuestion.class))).thenAnswer(a -> {
            BankQuestion b = a.getArgument(0);
            if (b.getId() == null) { b.setId(nextId++); bank.add(b); }
            return b;
        });
        when(bankRepo.findByCourse_CidOrderByCreatedAtDesc(anyLong())).thenAnswer(a -> new ArrayList<>(bank));
    }

    private static List<Object> json(String s) throws Exception {
        return new ObjectMapper().readValue(s, new TypeReference<List<Object>>() {});
    }

    private Map<String, Object> upload(String file, String topic, String difficulty) throws Exception {
        QuestionBankService.UploadRequest req = new QuestionBankService.UploadRequest();
        req.topic = topic;
        req.difficulty = difficulty;
        req.questions = json(file);
        return service.upload(lecturer, 10L, req);
    }

    private BankQuestion find(String contentStart) {
        return bank.stream().filter(b -> b.getContent().startsWith(contentStart)).findFirst().orElseThrow();
    }

    @Test
    void objectiveTemplateUploadsEveryType() throws Exception {
        Map<String, Object> result = upload(OBJECTIVE, "Basics", "MEDIUM");

        assertThat(result).containsEntry("added", 6).containsEntry("skipped", 0);
        BankQuestion first = find("Which of these");
        assertThat(first.getTopic()).isEqualTo("Hardware");                       // the item's own topic wins
        assertThat(first.getDifficulty()).isEqualTo(BankQuestion.Difficulty.EASY);
        assertThat(first.getCorrectAnswer()).containsExactly("Keyboard");
        assertThat(find("Which are").getCorrectAnswer()).containsExactly("Python", "Java");
        assertThat(find("Which are").getTopic()).isEqualTo("Basics");             // the upload's default
        assertThat(find("Which are").getDifficulty()).isEqualTo(BankQuestion.Difficulty.MEDIUM);

        BankQuestion tf = find("RAM keeps");
        assertThat(tf.getQuestionType()).isEqualTo(QuestionType.TRUE_FALSE);
        assertThat(tf.getOption1()).isEqualTo("True");
        assertThat(tf.getCorrectAnswer()).containsExactly("False");

        BankQuestion matching = find("Match each");
        assertThat(matching.getMatchingPairsJson()).contains("Mouse");
        assertThat(matching.getMatchingPairsJson().indexOf("Mouse"))
                .isLessThan(matching.getMatchingPairsJson().indexOf("Printer"));       // sorted by pairOrder

        assertThat(find("The brain").getCorrectAnswer()).containsExactly("CPU", "processor");
        BankQuestion numeric = find("How many bits");
        assertThat(numeric.getQuestionType()).isEqualTo(QuestionType.NUMERIC);
        assertThat(numeric.getTolerance()).isEqualTo(0.0);
        assertThat(bank).allMatch(b -> !b.isTheory());
    }

    @Test
    void theoryTemplateUploadsAsTheory() throws Exception {
        Map<String, Object> result = upload(THEORY, null, null);

        assertThat(result).containsEntry("added", 3);
        BankQuestion os = find("Define an operating system");
        assertThat(os.isTheory()).isTrue();
        assertThat(os.getMarks()).isEqualTo(4.0);
        assertThat(os.getMarkingGuide()).isEqualTo("Manages hardware and software.");
        assertThat(os.getTopic()).isNull();
        assertThat(os.getDifficulty()).isEqualTo(BankQuestion.Difficulty.MEDIUM);
        assertThat(find("Explain RAM").getMarks()).isEqualTo(6.0);                  // "6 marks"
        assertThat(find("What is a network").isTheory()).isTrue();
        assertThat(find("What is a network").getTopic()).isEqualTo("Networks");
    }

    @Test
    void mixedFileAndDuplicatesAreSkipped() throws Exception {
        upload(OBJECTIVE, null, null);
        Map<String, Object> again = upload(OBJECTIVE, null, null);
        assertThat(again).containsEntry("added", 0).containsEntry("skipped", 6);

        String twice = "[" + THEORY.substring(THEORY.indexOf('{'), THEORY.indexOf('}') + 1) + ","
                + THEORY.substring(THEORY.indexOf('{'), THEORY.indexOf('}') + 1) + "]";
        assertThat(upload(twice, null, null)).containsEntry("added", 1).containsEntry("skipped", 1);
    }

    @Test
    void errorsNameTheQuestion() {
        assertThatThrownBy(() -> upload("""
                [ { "questionType": "TRUE_FALSE", "content": "Fine.", "correct_answer": ["True"] },
                  { "questionType": "MCQ", "content": "Pick the input device", "option1": "Mouse", "option2": "Monitor",
                    "correct_answer": ["mouse"] } ]""", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Question 2 (\"Pick the input device\")")
                .hasMessageContaining("\"mouse\" doesn't match any option exactly");

        assertThatThrownBy(() -> upload("[ { \"questionType\": \"TRUE_FALSE\", \"content\": \"X\", \"correct_answer\": [\"true\"] } ]", null, null))
                .hasMessageContaining("use \"True\" or \"False\"");
        assertThatThrownBy(() -> upload("[ { \"question\": \"Explain.\" } ]", null, null))
                .hasMessageStartingWith("Question 1 (\"Explain.\")")
                .hasMessageContaining("marks");
        assertThatThrownBy(() -> upload("[ { \"questionType\": \"ESSAY\", \"content\": \"X\" } ]", null, null))
                .hasMessageContaining("Unknown question type");
        assertThatThrownBy(() -> upload("[ { \"content\": \"X\", \"option1\": \"A\", \"option2\": \"B\", \"correct_answer\": [\"A\"], \"difficulty\": \"tricky\" } ]", null, null))
                .hasMessageContaining("Difficulty must be EASY, MEDIUM or HARD");
        assertThatThrownBy(() -> upload("[ \"just text\" ]", null, null))
                .hasMessage("Question 1: each question must be written inside { }.");
        assertThatThrownBy(() -> upload("[ { \"questionType\": \"NUMERIC\", \"content\": \"X\", \"correct_answer\": [\"8\"], \"tolerance\": \"a bit\" } ]", null, null))
                .hasMessageContaining("\"tolerance\" must be a number");
    }

    private Map<String, Object> uploadOnly(String file, String... types) throws Exception {
        QuestionBankService.UploadRequest req = new QuestionBankService.UploadRequest();
        req.questions = json(file);
        req.types = List.of(types);
        return service.upload(lecturer, 10L, req);
    }

    /** Both templates in one file: 6 objective items, then 3 theory items. */
    private static String mixed() {
        return OBJECTIVE.substring(0, OBJECTIVE.lastIndexOf(']')) + "," + THEORY.substring(THEORY.indexOf('[') + 1);
    }

    @Test
    void onlyTheChosenTypesAreUploaded() throws Exception {
        Map<String, Object> result = uploadOnly(mixed(), "true_false", " THEORY ");

        assertThat(result).containsEntry("added", 4).containsEntry("skipped", 0).containsEntry("ignored", 5);
        assertThat(bank).extracting(b -> b.isTheory() ? "THEORY" : b.getQuestionType().name())
                .containsOnly("TRUE_FALSE", "THEORY");

        assertThat(uploadOnly(mixed(), "MCQ")).containsEntry("added", 2).containsEntry("ignored", 7);
        assertThat(uploadOnly(mixed())).containsEntry("added", 3).containsEntry("skipped", 6);   // no filter: everything
    }

    @Test
    void unchosenItemsAreNotCheckedAndErrorsKeepFilePositions() throws Exception {
        String file = """
                [ { "questionType": "NUMERIC", "content": "Broken", "correct_answer": [] },
                  { "questionType": "MCQ", "content": "Fine", "option1": "A", "option2": "B", "correct_answer": ["A"] },
                  { "questionType": "MCQ", "content": "Typo here", "option1": "A", "option2": "B", "correct_answer": ["a"] } ]""";

        assertThatThrownBy(() -> uploadOnly(file, "MCQ"))
                .hasMessageStartingWith("Question 3 (\"Typo here\")");                       // position in the file, not "Question 2"
        bank.clear();   // the mock saved "Fine" before the failure; the real transaction rolls it back
        assertThat(uploadOnly(file.replace("[\"a\"]", "[\"A\"]"), "MCQ"))                   // the broken NUMERIC is ignored
                .containsEntry("added", 2).containsEntry("ignored", 1);
        assertThatThrownBy(() -> uploadOnly(file, "MATCHING"))
                .hasMessage("None of the questions in the file are of the type(s) you selected.");
    }

    @Test
    void emptyOrTooLargeFilesAreRejected() {
        assertThatThrownBy(() -> upload("[]", null, null)).hasMessage("The file has no questions in it.");
        StringBuilder big = new StringBuilder("[");
        for (int i = 0; i <= QuestionBankService.MAX_UPLOAD; i++)
            big.append(i == 0 ? "" : ",").append("{\"question\":\"Q").append(i).append("\",\"marks\":1}");
        assertThatThrownBy(() -> upload(big + "]", null, null)).hasMessageContaining("at most " + QuestionBankService.MAX_UPLOAD);
        assertThatThrownBy(() -> upload("[ { \"question\": \"X\", \"marks\": 1 } ]", null, "SOMETIMES"))
                .hasMessageContaining("Difficulty must be EASY, MEDIUM or HARD");
        assertThat(bank).isEmpty();
    }
}
