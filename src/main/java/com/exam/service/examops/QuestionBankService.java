package com.exam.service.examops;

import com.exam.model.User;
import com.exam.model.exam.*;
import com.exam.model.examops.BankQuestion;
import com.exam.repository.BankQuestionRepository;
import com.exam.repository.CategoryRepository;
import com.exam.repository.QuestionsRepository;
import com.exam.repository.QuizRepository;
import com.exam.service.QuestionImageService;
import com.exam.service.comms.CurrentUserService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Per-course question bank. Questions are written once, tagged by topic and difficulty, and
 * drawn (copied) into quizzes at random or by hand.
 */
@Service
public class QuestionBankService {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired private BankQuestionRepository bankRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private QuizRepository quizRepository;
    @Autowired private QuestionsRepository questionsRepository;
    @Autowired private QuestionImageService questionImageService;
    @Autowired private com.exam.repository.TheoryQuestionsRepository theoryRepository;

    /** Create / update payload. */
    public static class BankQuestionRequest {
        public String topic;
        public String difficulty;          // EASY | MEDIUM | HARD
        public String questionType;        // MCQ | TRUE_FALSE | MATCHING | FILL_BLANK | NUMERIC | THEORY
        public String content;
        public String image;
        public String option1, option2, option3, option4;
        public List<String> correctAnswer;
        public List<Map<String, String>> matchingPairs;   // [{prompt, answer}]
        public Double tolerance;                          // NUMERIC only
        public Double marks;                              // THEORY only
        public String markingGuide;                       // THEORY only
    }

    /** Random draw: filters are optional; count is required. */
    public static class DrawRequest {
        public String topic;
        public String difficulty;
        public String questionType;
        public Integer count;
        public List<Long> questionIds;     // hand-picked alternative to a random draw
    }

    // ── Read ─────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Map<String, Object> list(User u, Long courseId) {
        Category course = course(courseId);
        ExamAccess.requireCourse(u, course);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("courseId", course.getCid());
        out.put("courseCode", course.getCourseCode());
        out.put("courseTitle", course.getTitle());
        out.put("topics", bankRepository.findTopics(courseId));
        out.put("questions", bankRepository.findByCourse_CidOrderByCreatedAtDesc(courseId).stream().map(this::toDto).toList());
        return out;
    }

    /** Courses the user can keep a bank for, with how many questions each holds. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> myCourses(User u) {
        return categoryRepository.findAll().stream()
                .filter(c -> ExamAccess.canManageCourse(u, c))
                .sorted(Comparator.comparing(c -> Objects.toString(c.getCourseCode(), "")))
                .map(c -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("courseId", c.getCid());
                    m.put("courseCode", c.getCourseCode());
                    m.put("title", c.getTitle());
                    m.put("questionCount", bankRepository.countByCourse_Cid(c.getCid()));
                    return m;
                }).toList();
    }

    // ── Write ────────────────────────────────────────────────────────────────

    @Transactional
    public Map<String, Object> create(User u, Long courseId, BankQuestionRequest req) {
        Category course = course(courseId);
        ExamAccess.requireCourse(u, course);
        BankQuestion b = new BankQuestion();
        b.setCourse(course);
        b.setAuthorId(u.getId());
        b.setAuthorName(CurrentUserService.displayName(u));
        apply(b, req);
        return toDto(bankRepository.save(b));
    }

    @Transactional
    public Map<String, Object> update(User u, Long id, BankQuestionRequest req) {
        BankQuestion b = bank(id);
        ExamAccess.requireCourse(u, b.getCourse());
        String oldImage = b.getImage();
        apply(b, req);
        if (oldImage != null && !oldImage.equals(b.getImage())) questionImageService.deleteByPath(oldImage);
        return toDto(bankRepository.save(b));
    }

    @Transactional
    public void delete(User u, Long id) {
        BankQuestion b = bank(id);
        ExamAccess.requireCourse(u, b.getCourse());
        questionImageService.deleteByPath(b.getImage());
        bankRepository.delete(b);
    }

    /** Copies every question of a quiz (objective and theory) into its course's bank, skipping ones already there. */
    @Transactional
    public Map<String, Object> importFromQuiz(User u, Long quizId, String topic, String difficulty) {
        Quiz quiz = quiz(quizId);
        ExamAccess.requireQuiz(u, quiz);
        Category course = quiz.getCategory();
        if (course == null) throw new IllegalArgumentException("This quiz is not linked to a course.");
        ExamAccess.requireCourse(u, course);

        Set<String> existing = bankRepository.findByCourse_CidOrderByCreatedAtDesc(course.getCid()).stream()
                .map(QuestionBankService::fingerprint).collect(Collectors.toSet());
        BankQuestion.Difficulty diff = parseDifficulty(difficulty);
        String cleanTopic = blankToNull(topic) != null ? topic.trim() : quiz.getTitle();

        int added = 0, skipped = 0;
        for (Questions q : questionsRepository.findByQuiz_qId(quizId)) {
            if (!existing.add(fingerprint(q.getQuestionType(), q.getContent()))) { skipped++; continue; }
            BankQuestion b = new BankQuestion();
            b.setCourse(course);
            b.setTopic(cleanTopic);
            b.setDifficulty(diff);
            b.setQuestionType(q.getQuestionType() != null ? q.getQuestionType() : QuestionType.MCQ);
            b.setContent(q.getContent());
            b.setImage(questionImageService.copy(q.getImage()));
            b.setOption1(q.getOption1()); b.setOption2(q.getOption2());
            b.setOption3(q.getOption3()); b.setOption4(q.getOption4());
            b.setCorrectAnswer(q.getcorrect_answer());
            b.setTolerance(q.getTolerance());
            if (q.getQuestionType() == QuestionType.MATCHING) b.setMatchingPairsJson(pairsToJson(q.getMatchingPairs()));
            b.setAuthorId(u.getId());
            b.setAuthorName(CurrentUserService.displayName(u));
            bankRepository.save(b);
            added++;
        }
        for (com.exam.model.exam.TheoryQuestions t : theoryRepository.findByQuiz(quiz)) {
            if (!existing.add(theoryFingerprint(t.getQuestion()))) { skipped++; continue; }
            BankQuestion b = new BankQuestion();
            b.setCourse(course);
            b.setTopic(cleanTopic);
            b.setDifficulty(diff);
            b.setSection(BankQuestion.Section.THEORY);
            b.setQuestionType(QuestionType.MCQ);   // unused for theory; the column is required
            b.setContent(t.getQuestion());
            b.setImage(questionImageService.copy(t.getImage()));
            b.setMarks(parseMarks(t.getMarks()));
            b.setMarkingGuide(blankToNull(t.getEvaluationCriteria()));
            b.setAuthorId(u.getId());
            b.setAuthorName(CurrentUserService.displayName(u));
            bankRepository.save(b);
            added++;
        }
        return Map.of("added", added, "skipped", skipped);
    }

    /**
     * Copies bank questions into a quiz: either the hand-picked ids, or {@code count} random
     * questions matching the filters. Questions whose text is already in the quiz are skipped.
     */
    @Transactional
    public Map<String, Object> drawIntoQuiz(User u, Long quizId, DrawRequest req) {
        Quiz quiz = quiz(quizId);
        ExamAccess.requireQuiz(u, quiz);
        Category course = quiz.getCategory();
        if (course == null) throw new IllegalArgumentException("This quiz is not linked to a course.");

        Set<String> inQuiz = questionsRepository.findByQuiz_qId(quizId).stream()
                .map(q -> fingerprint(q.getQuestionType(), q.getContent())).collect(Collectors.toSet());
        Set<com.exam.model.exam.TheoryQuestions> theoryInQuiz = theoryRepository.findByQuiz(quiz);
        theoryInQuiz.forEach(t -> inQuiz.add(theoryFingerprint(t.getQuestion())));

        List<BankQuestion> pool = bankRepository.findByCourse_CidOrderByCreatedAtDesc(course.getCid()).stream()
                .filter(b -> !inQuiz.contains(fingerprint(b)))
                .collect(Collectors.toCollection(ArrayList::new));

        List<BankQuestion> chosen;
        if (req.questionIds != null && !req.questionIds.isEmpty()) {
            Set<Long> ids = new HashSet<>(req.questionIds);
            chosen = pool.stream().filter(b -> ids.contains(b.getId())).toList();
        } else {
            int count = req.count == null ? 0 : req.count;
            if (count < 1 || count > 200) throw new IllegalArgumentException("Choose between 1 and 200 questions.");
            String topic = blankToNull(req.topic);
            BankQuestion.Difficulty diff = blankToNull(req.difficulty) == null ? null : parseDifficulty(req.difficulty);
            String type = blankToNull(req.questionType);
            pool.removeIf(b -> (topic != null && !topic.equalsIgnoreCase(Objects.toString(b.getTopic(), "")))
                    || (diff != null && b.getDifficulty() != diff)
                    || (type != null && !type.equals(typeName(b))));
            if (pool.size() < count)
                throw new IllegalArgumentException("Only " + pool.size() + " matching question(s) are available that aren't already in this quiz.");
            Collections.shuffle(pool);
            chosen = pool.subList(0, count);
        }

        // Theory questions get the next free number (Q4 after Q1–Q3): the exam groups parts by that prefix
        int nextNo = theoryInQuiz.stream().mapToInt(t -> questionNumber(t.getQuesNo())).max().orElse(0) + 1;
        int objective = 0, theory = 0;
        for (BankQuestion b : chosen) {
            if (b.isTheory()) {
                theoryRepository.save(toTheoryQuestion(b, quiz, "Q" + nextNo++));
                theory++;
            } else {
                questionsRepository.save(toQuizQuestion(b, quiz));
                objective++;
            }
            b.setTimesUsed(b.getTimesUsed() + 1);
        }
        bankRepository.saveAll(chosen);
        return Map.of("added", chosen.size(), "addedObjective", objective, "addedTheory", theory,
                "totalInQuiz", questionsRepository.findByQuiz_qId(quizId).size(),
                "theoryInQuiz", theoryRepository.findByQuiz(quiz).size());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void apply(BankQuestion b, BankQuestionRequest req) {
        if (req.content == null || req.content.isBlank()) throw new IllegalArgumentException("Question text is required.");
        b.setTopic(blankToNull(req.topic) == null ? null : req.topic.trim());
        b.setDifficulty(parseDifficulty(req.difficulty));
        b.setContent(req.content.trim());
        b.setImage(blankToNull(req.image));

        if ("THEORY".equals(req.questionType)) {
            if (req.marks == null || req.marks <= 0 || req.marks > 1000)
                throw new IllegalArgumentException("Give the theory question its marks (more than 0).");
            b.setSection(BankQuestion.Section.THEORY);
            b.setQuestionType(QuestionType.MCQ);   // unused for theory; the column is required
            b.setMarks(req.marks);
            b.setMarkingGuide(blankToNull(req.markingGuide) == null ? null : req.markingGuide.trim());
            b.setOption1(null); b.setOption2(null); b.setOption3(null); b.setOption4(null);
            b.setCorrectAnswer(null);
            b.setTolerance(null);
            b.setMatchingPairsJson(null);
            return;
        }
        b.setSection(BankQuestion.Section.OBJECTIVE);
        b.setMarks(null);
        b.setMarkingGuide(null);
        QuestionType type;
        try { type = blankToNull(req.questionType) == null ? QuestionType.MCQ : QuestionType.valueOf(req.questionType); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Unknown question type."); }
        b.setQuestionType(type);

        if (type == QuestionType.MATCHING) {
            List<Map<String, String>> pairs = req.matchingPairs == null ? List.of() : req.matchingPairs.stream()
                    .filter(p -> blankToNull(p.get("prompt")) != null && blankToNull(p.get("answer")) != null).toList();
            if (pairs.size() < 2) throw new IllegalArgumentException("A matching question needs at least 2 complete pairs.");
            b.setOption1(null); b.setOption2(null); b.setOption3(null); b.setOption4(null);
            b.setCorrectAnswer(null);
            try { b.setMatchingPairsJson(JSON.writeValueAsString(pairs)); }
            catch (Exception e) { throw new IllegalArgumentException("Invalid matching pairs."); }
            return;
        }

        if (com.exam.service.AnswerMatcher.isTyped(type)) {
            String[] accepted = req.correctAnswer == null ? new String[0] : req.correctAnswer.toArray(new String[0]);
            com.exam.service.AnswerMatcher.validateTyped(type, accepted, req.tolerance);
            b.setOption1(null); b.setOption2(null); b.setOption3(null); b.setOption4(null);
            b.setCorrectAnswer(com.exam.service.AnswerMatcher.cleanAccepted(accepted));
            b.setTolerance(type == QuestionType.NUMERIC ? req.tolerance : null);
            b.setMatchingPairsJson(null);
            return;
        }
        b.setTolerance(null);

        if (type == QuestionType.TRUE_FALSE) {
            b.setOption1("True"); b.setOption2("False"); b.setOption3(null); b.setOption4(null);
        } else {
            b.setOption1(blankToNull(req.option1)); b.setOption2(blankToNull(req.option2));
            b.setOption3(blankToNull(req.option3)); b.setOption4(blankToNull(req.option4));
            if (b.getOption1() == null || b.getOption2() == null)
                throw new IllegalArgumentException("A multiple-choice question needs at least 2 options.");
        }
        List<String> options = new ArrayList<>(Arrays.asList(b.getOption1(), b.getOption2(), b.getOption3(), b.getOption4()));
        options.removeIf(Objects::isNull);
        List<String> correct = req.correctAnswer == null ? List.of() : req.correctAnswer.stream()
                .filter(options::contains).distinct().toList();
        if (correct.isEmpty()) throw new IllegalArgumentException("Mark at least one option as correct.");
        b.setCorrectAnswer(correct.toArray(new String[0]));
        b.setMatchingPairsJson(null);
    }

    private Questions toQuizQuestion(BankQuestion b, Quiz quiz) {
        Questions q = new Questions();
        q.setQuiz(quiz);
        q.setQuestionType(b.getQuestionType());
        q.setContent(b.getContent());
        q.setImage(questionImageService.copy(b.getImage()));
        q.setOption1(b.getOption1()); q.setOption2(b.getOption2());
        q.setOption3(b.getOption3()); q.setOption4(b.getOption4());
        q.setcorrect_answer(b.getCorrectAnswer());
        q.setTolerance(b.getTolerance());
        if (b.getQuestionType() == QuestionType.MATCHING) {
            List<Map<String, String>> pairs = pairsFromJson(b.getMatchingPairsJson());
            List<MatchingPair> mp = new ArrayList<>();
            for (int i = 0; i < pairs.size(); i++) mp.add(new MatchingPair(pairs.get(i).get("prompt"), pairs.get(i).get("answer"), i, q));
            q.setMatchingPairs(mp);
            // Matching answers are stored as the correct right-hand values in pair order
            q.setcorrect_answer(pairs.stream().map(p -> p.get("answer")).toArray(String[]::new));
        }
        return q;
    }

    private com.exam.model.exam.TheoryQuestions toTheoryQuestion(BankQuestion b, Quiz quiz, String quesNo) {
        com.exam.model.exam.TheoryQuestions t = new com.exam.model.exam.TheoryQuestions();
        t.setQuiz(quiz);
        t.setQuesNo(quesNo);
        t.setQuestion(b.getContent());
        t.setImage(questionImageService.copy(b.getImage()));
        t.setMarks(formatMarks(b.getMarks()));
        t.setEvaluationCriteria(b.getMarkingGuide());
        t.setIsCompulsory(false);
        return t;
    }

    /** "MCQ", "MATCHING" … or "THEORY": the type the UI and the draw filter use. */
    static String typeName(BankQuestion b) {
        return b.isTheory() ? "THEORY" : b.getQuestionType().name();
    }

    /** "Q12b" → 12; anything without a number → 0. */
    static int questionNumber(String quesNo) {
        if (quesNo == null) return 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)").matcher(quesNo);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private static Double parseMarks(String s) {
        try { return s == null || s.isBlank() ? null : Double.parseDouble(s.trim()); }
        catch (NumberFormatException e) { return null; }
    }

    private static String formatMarks(Double m) {
        if (m == null) return "0";
        return m == Math.rint(m) ? String.valueOf(m.longValue()) : String.valueOf(m);
    }

    private Map<String, Object> toDto(BankQuestion b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", b.getId());
        m.put("topic", b.getTopic());
        m.put("difficulty", b.getDifficulty().name());
        m.put("questionType", typeName(b));
        m.put("marks", b.getMarks());
        m.put("markingGuide", b.getMarkingGuide());
        m.put("content", b.getContent());
        m.put("image", b.getImage());
        m.put("option1", b.getOption1());
        m.put("option2", b.getOption2());
        m.put("option3", b.getOption3());
        m.put("option4", b.getOption4());
        m.put("correctAnswer", b.getCorrectAnswer() == null ? List.of() : Arrays.asList(b.getCorrectAnswer()));
        m.put("matchingPairs", pairsFromJson(b.getMatchingPairsJson()));
        m.put("tolerance", b.getTolerance());
        m.put("authorName", b.getAuthorName());
        m.put("timesUsed", b.getTimesUsed());
        m.put("createdAt", b.getCreatedAt());
        return m;
    }

    private static String pairsToJson(List<MatchingPair> pairs) {
        if (pairs == null) return "[]";
        List<Map<String, String>> list = pairs.stream()
                .sorted(Comparator.comparing(p -> p.getPairOrder() == null ? 0 : p.getPairOrder()))
                .map(p -> Map.of("prompt", Objects.toString(p.getPrompt(), ""), "answer", Objects.toString(p.getAnswer(), "")))
                .toList();
        try { return JSON.writeValueAsString(list); } catch (Exception e) { return "[]"; }
    }

    private static List<Map<String, String>> pairsFromJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        try { return JSON.readValue(json, new TypeReference<List<Map<String, String>>>() {}); }
        catch (Exception e) { return List.of(); }
    }

    private static String fingerprint(BankQuestion b) {
        return b.isTheory() ? theoryFingerprint(b.getContent()) : fingerprint(b.getQuestionType(), b.getContent());
    }

    private static String theoryFingerprint(String content) {
        return "THEORY|" + normaliseText(content);
    }

    private static String normaliseText(String s) {
        return s == null ? "" : s.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim().toLowerCase();
    }

    private static String fingerprint(QuestionType type, String content) {
        return (type == null ? "MCQ" : type.name()) + "|" + Objects.toString(content, "").trim().replaceAll("\\s+", " ").toLowerCase();
    }

    private static BankQuestion.Difficulty parseDifficulty(String d) {
        if (blankToNull(d) == null) return BankQuestion.Difficulty.MEDIUM;
        try { return BankQuestion.Difficulty.valueOf(d.trim().toUpperCase()); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Difficulty must be EASY, MEDIUM or HARD."); }
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s; }

    private Category course(Long id) {
        return categoryRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Course not found."));
    }

    private Quiz quiz(Long id) {
        return quizRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Quiz not found."));
    }

    private BankQuestion bank(Long id) {
        return bankRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Question not found."));
    }
}
