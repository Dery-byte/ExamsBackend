package com.exam.service.examops;

import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.exam.*;
import com.exam.model.examops.BankQuestion;
import com.exam.repository.*;
import com.exam.service.QuestionImageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/** Theory questions in the bank: saved with marks and a marking guide, drawn into Section B with the next free number. */
class QuestionBankTheoryTest {

    private QuestionBankService service;
    private final List<BankQuestion> bank = new ArrayList<>();
    private final Set<TheoryQuestions> theoryInQuiz = new HashSet<>();
    private final List<Questions> objectiveInQuiz = new ArrayList<>();
    private User admin;
    private Quiz quiz;
    private Category course;
    private long nextId = 1;

    @BeforeEach
    void setUp() {
        service = new QuestionBankService();
        BankQuestionRepository bankRepo = mock(BankQuestionRepository.class);
        CategoryRepository categories = mock(CategoryRepository.class);
        QuizRepository quizzes = mock(QuizRepository.class);
        QuestionsRepository questions = mock(QuestionsRepository.class);
        TheoryQuestionsRepository theory = mock(TheoryQuestionsRepository.class);
        QuestionImageService images = mock(QuestionImageService.class);
        ReflectionTestUtils.setField(service, "bankRepository", bankRepo);
        ReflectionTestUtils.setField(service, "categoryRepository", categories);
        ReflectionTestUtils.setField(service, "quizRepository", quizzes);
        ReflectionTestUtils.setField(service, "questionsRepository", questions);
        ReflectionTestUtils.setField(service, "theoryRepository", theory);
        ReflectionTestUtils.setField(service, "questionImageService", images);

        admin = new User();
        admin.setId(1L);
        admin.setRole(Role.SUPER_ADMIN);
        course = new Category();
        course.setCid(10L);
        quiz = new Quiz();
        quiz.setqId(20L);
        quiz.setCategory(course);

        when(categories.findById(10L)).thenReturn(Optional.of(course));
        when(quizzes.findById(20L)).thenReturn(Optional.of(quiz));
        when(images.copy(any())).thenAnswer(a -> a.getArgument(0));
        when(bankRepo.save(any(BankQuestion.class))).thenAnswer(a -> {
            BankQuestion b = a.getArgument(0);
            if (b.getId() == null) { b.setId(nextId++); bank.add(b); }
            return b;
        });
        when(bankRepo.findByCourse_CidOrderByCreatedAtDesc(anyLong())).thenAnswer(a -> new ArrayList<>(bank));
        when(questions.findByQuiz_qId(20L)).thenAnswer(a -> objectiveInQuiz);
        when(questions.save(any(Questions.class))).thenAnswer(a -> { objectiveInQuiz.add(a.getArgument(0)); return a.getArgument(0); });
        when(theory.findByQuiz(quiz)).thenAnswer(a -> new HashSet<>(theoryInQuiz));
        when(theory.save(any(TheoryQuestions.class))).thenAnswer(a -> { theoryInQuiz.add(a.getArgument(0)); return a.getArgument(0); });
    }

    private QuestionBankService.BankQuestionRequest theory(String text, Double marks) {
        QuestionBankService.BankQuestionRequest r = new QuestionBankService.BankQuestionRequest();
        r.questionType = "THEORY";
        r.content = text;
        r.marks = marks;
        r.markingGuide = "Mentions chlorophyll and sunlight";
        r.difficulty = "MEDIUM";
        return r;
    }

    @Test
    void theoryQuestionsAreSavedWithMarksAndAMarkingGuide() {
        Map<String, Object> dto = service.create(admin, 10L, theory("Explain photosynthesis.", 10.0));
        assertThat(dto.get("questionType")).isEqualTo("THEORY");
        assertThat(dto.get("marks")).isEqualTo(10.0);
        assertThat(dto.get("markingGuide")).isEqualTo("Mentions chlorophyll and sunlight");
        assertThat(bank.get(0).getOption1()).isNull();

        assertThatThrownBy(() -> service.create(admin, 10L, theory("No marks", null))).hasMessageContaining("marks");
        assertThatThrownBy(() -> service.create(admin, 10L, theory("Zero", 0.0))).hasMessageContaining("marks");
    }

    @Test
    void drawnTheoryQuestionsTakeTheNextFreeNumberAndSkipDuplicates() {
        TheoryQuestions existing1 = new TheoryQuestions(); existing1.setQuesNo("Q1"); existing1.setQuestion("Old question");
        TheoryQuestions existing2 = new TheoryQuestions(); existing2.setQuesNo("Q2b"); existing2.setQuestion("Explain  <b>photosynthesis.</b>");
        theoryInQuiz.addAll(List.of(existing1, existing2));

        service.create(admin, 10L, theory("Explain photosynthesis.", 10.0));   // same text as Q2b → skipped
        service.create(admin, 10L, theory("Describe osmosis.", 8.0));
        service.create(admin, 10L, theory("Define respiration.", 5.5));

        QuestionBankService.DrawRequest req = new QuestionBankService.DrawRequest();
        req.questionType = "THEORY";
        req.count = 2;
        Map<String, Object> res = service.drawIntoQuiz(admin, 20L, req);

        assertThat(res.get("addedTheory")).isEqualTo(2);
        assertThat(res.get("addedObjective")).isEqualTo(0);
        List<TheoryQuestions> added = theoryInQuiz.stream().filter(t -> t.getQuesNo().matches("Q[34]")).toList();
        assertThat(added).extracting(TheoryQuestions::getQuesNo).containsExactlyInAnyOrder("Q3", "Q4");
        assertThat(added).extracting(TheoryQuestions::getMarks).containsExactlyInAnyOrder("8", "5.5");
        assertThat(added).allSatisfy(t -> assertThat(t.getEvaluationCriteria()).isEqualTo("Mentions chlorophyll and sunlight"));
        assertThat(objectiveInQuiz).isEmpty();

        // nothing left that isn't already in the quiz
        req.count = 1;
        assertThatThrownBy(() -> service.drawIntoQuiz(admin, 20L, req)).hasMessageContaining("Only 0");
    }

    @Test
    void importingAQuizBringsItsTheoryQuestionsToo() {
        TheoryQuestions t = new TheoryQuestions();
        t.setQuesNo("Q1"); t.setQuestion("Discuss the causes of WWI."); t.setMarks("15"); t.setEvaluationCriteria("Alliances, militarism");
        theoryInQuiz.add(t);

        Map<String, Object> res = service.importFromQuiz(admin, 20L, "History", "HARD");
        assertThat(res.get("added")).isEqualTo(1);
        BankQuestion b = bank.get(0);
        assertThat(b.isTheory()).isTrue();
        assertThat(b.getMarks()).isEqualTo(15.0);
        assertThat(b.getMarkingGuide()).isEqualTo("Alliances, militarism");

        assertThat(service.importFromQuiz(admin, 20L, "History", "HARD").get("skipped")).isEqualTo(1);
    }

    @Test
    void questionNumbersAreReadFromLabels() {
        assertThat(QuestionBankService.questionNumber("Q12b")).isEqualTo(12);
        assertThat(QuestionBankService.questionNumber("Question 3")).isEqualTo(3);
        assertThat(QuestionBankService.questionNumber("Essay")).isZero();
        assertThat(QuestionBankService.questionNumber(null)).isZero();
    }
}
