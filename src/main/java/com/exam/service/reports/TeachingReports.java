package com.exam.service.reports;

import com.exam.model.QuizType;
import com.exam.model.academic.AcademicSession;
import com.exam.model.exam.*;
import com.exam.model.examops.RemarkRequest;
import com.exam.repository.QuestionsRepository;
import com.exam.repository.TheoryQuestionsRepository;
import com.exam.service.AnswerMatcher;
import com.exam.service.reports.ReportQueries.*;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

import static com.exam.service.reports.ReportResult.*;
import static com.exam.service.reports.ReportSupport.*;

/**
 * Reports about teaching one course or one quiz: the quiz result sheet, question (item) analysis,
 * the course's continuous assessment summary, and re-mark requests. Lecturers see their own
 * courses and quizzes; HODs their department's; the Super Admin everything.
 */
@Service
@Transactional(readOnly = true)
public class TeachingReports {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy");
    private static final String[] LETTERS = {"A", "B", "C", "D"};

    @Autowired private ReportSupport support;
    @Autowired private ReportQueries queries;
    @Autowired private ExamReports exams;
    @Autowired private QuestionsRepository questionsRepository;
    @Autowired private TheoryQuestionsRepository theoryQuestionsRepository;

    /** "CS201 · Mid-semester · 12 Oct 2026", as the quiz pickers show it. */
    public static String quizLabel(Quiz q) {
        StringBuilder sb = new StringBuilder();
        if (q.getCategory() != null && q.getCategory().getCourseCode() != null) sb.append(q.getCategory().getCourseCode()).append(" · ");
        sb.append(Objects.toString(q.getTitle(), "Quiz " + q.getqId()));
        if (q.getQuizDate() != null) sb.append(" · ").append(DAY.format(q.getQuizDate()));
        return sb.toString();
    }

    public static String courseLabel(Category c) {
        return (c.getCourseCode() == null ? "" : c.getCourseCode() + " — ") + Objects.toString(c.getTitle(), "");
    }

    /** The chosen quiz, which must be inside the viewer's scope (their own, or their department's). */
    private Quiz chosenQuiz(ReportFilters f, Lookups l) {
        Quiz q = l.quiz(f.quizId());
        if (q == null) throw new IllegalArgumentException("Quiz not found.");
        if (!l.quizMatches(q.getqId(), new ReportFilters(null, f.departmentId(), null, null, null, null, null, null, null, null, f.lecturerId())))
            throw new AccessDeniedException("That quiz is outside your reports.");
        return q;
    }

    /** HOD reports say which department they cover (lecturer and Super Admin reports name the quiz or course instead). */
    private static void departmentLine(ReportResult r, ReportFilters f, Lookups l) {
        if (f.departmentId() != null && f.lecturerId() == null) r.scope("Department: " + Objects.toString(l.deptName(f.departmentId()), "?"));
    }

    private void quizScope(ReportResult r, Quiz q, Map<String, String> t) {
        r.scope("Quiz: " + q.getTitle());
        if (q.getCategory() != null) r.scope(t.get("course") + ": " + courseLabel(q.getCategory()));
        if (q.getQuizDate() != null) r.scope("Date: " + DAY.format(q.getQuizDate()));
        if (q.getMaxMarks() != null) r.scope("Objective marks: " + BigDecimal.valueOf(q.getMaxMarks()).stripTrailingZeros().toPlainString());
    }

    // ── Quiz result sheet ────────────────────────────────────────────────────

    public ReportResult quizResults(ReportFilters f) {
        Map<String, String> t = support.terms();
        String student = t.get("student");
        ReportResult r = new ReportResult("quiz-results", "Quiz result sheet",
                "Every " + student.toLowerCase() + "'s marks on one quiz (sat, unfinished or absent) with the class statistics.");
        Lookups l = support.lookups();
        departmentLine(r, f, l);
        if (f.quizId() == null) {
            r.table("results", "Results").text("studentId", t.get("studentId")).text("name", "Name").emptyText("Choose a quiz above.");
            return r;
        }
        Quiz q = chosenQuiz(f, l);
        quizScope(r, q, t);

        // Latest result per student; attempts and violations per student
        Map<Long, QuizScoreRow> latest = new HashMap<>();
        for (QuizScoreRow s : queries.quizScores(q.getqId())) {
            QuizScoreRow had = latest.get(s.studentId());
            if (had == null || Comparator.comparing((QuizScoreRow x) -> x.submittedAt() == null ? LocalDateTime.MIN : x.submittedAt())
                    .thenComparing(QuizScoreRow::reportId).compare(s, had) > 0) latest.put(s.studentId(), s);
        }
        Map<Long, List<AttemptRow>> attempts = queries.quizAttempts(q.getqId()).stream().collect(Collectors.groupingBy(AttemptRow::studentId));
        Map<Long, List<EventRow>> events = queries.quizEvents(q.getqId()).stream().collect(Collectors.groupingBy(EventRow::studentId));
        Map<Long, StudentRow> info = queries.students().stream().collect(Collectors.toMap(StudentRow::id, s -> s));
        List<StudentRow> active = info.values().stream().filter(StudentRow::enabled).toList();
        AcademicSession session = support.sessionOn(q.getQuizDate());
        ExamReports.Expected expected = exams.expected(q, exams.registeredIn(session.getId()), active,
                active.stream().collect(Collectors.toMap(StudentRow::id, s -> s)), l, t);

        Set<Long> everyone = new TreeSet<>();
        expected.students().forEach(s -> everyone.add(s.id()));
        everyone.addAll(latest.keySet());
        everyone.addAll(attempts.keySet());

        boolean objective = q.getQuizType() != QuizType.THEORY;
        boolean theory = q.getQuizType() == QuizType.THEORY || q.getQuizType() == QuizType.BOTH
                || latest.values().stream().anyMatch(s -> s.marksB() != null && s.marksB().signum() != 0);

        Table tb = r.table("results", "Results")
                .col("sn", "No.", INT).text("studentId", t.get("studentId")).text("name", "Name").text("programme", t.get("program"))
                .col("level", t.get("level"), INT).text("status", "Status").col("attempts", "Attempts", INT);
        if (objective) tb.col("a", "Section A", NUMBER);
        if (theory) tb.col("b", "Section B", NUMBER);
        tb.col("total", "Total", NUMBER).col("pct", "Score", PERCENT).text("grade", "Grade");
        if (theory) tb.text("reviewed", "Reviewed");
        tb.col("submitted", "Submitted", DATETIME).col("violations", "Violations", INT).text("auto", "Auto-submitted")
          .emptyText("Nobody was expected to sit this quiz and nobody has.");

        List<StudentRow> ordered = everyone.stream().map(info::get).filter(Objects::nonNull)
                .sorted(Comparator.comparing(s -> Objects.toString(s.username(), ""))).toList();
        int n = 0, sat = 0, absent = 0, unfinished = 0, unreviewed = 0;
        List<Double> pcts = new ArrayList<>();
        Map<String, Long> grades = new TreeMap<>();
        for (StudentRow s : ordered) {
            QuizScoreRow res = latest.get(s.id());
            List<AttemptRow> at = attempts.getOrDefault(s.id(), List.of());
            List<EventRow> ev = events.getOrDefault(s.id(), List.of());
            boolean open = at.stream().anyMatch(a -> a.status() == AttemptStatus.IN_PROGRESS);
            String status = res != null ? "Submitted" : open ? "Not submitted" : "Absent";
            if (res != null) sat++; else if (open) unfinished++; else absent++;

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sn", ++n);
            row.put("studentId", s.username());
            row.put("name", s.name());
            row.put("programme", l.programName(s.programId()));
            row.put("level", s.level());
            row.put("status", status);
            row.put("attempts", at.stream().filter(a -> a.status() != AttemptStatus.VOIDED).count());
            if (objective) row.put("a", res == null ? null : res.marks());
            if (theory) row.put("b", res == null ? null : res.marksB());
            BigDecimal total = res == null ? null : Optional.ofNullable(res.marks()).orElse(BigDecimal.ZERO)
                    .add(Optional.ofNullable(res.marksB()).orElse(BigDecimal.ZERO));
            row.put("total", total);
            row.put("pct", res == null ? null : res.percentage());
            row.put("grade", res == null ? null : res.grade());
            if (theory) row.put("reviewed", res == null ? null : yesNo(res.reviewed()));
            row.put("submitted", res == null ? null : minute(res.submittedAt()));
            row.put("violations", ev.stream().filter(e -> !ExamReports.AUTO_SUBMIT.equals(e.type())).count());
            row.put("auto", ev.stream().anyMatch(e -> ExamReports.AUTO_SUBMIT.equals(e.type())) ? "Yes" : null);
            tb.add(row);
            if (res != null && res.percentage() != null) pcts.add(res.percentage());
            if (res != null && res.grade() != null) grades.merge(res.grade(), 1L, Long::sum);
            if (res != null && theory && !res.reviewed()) unreviewed++;
        }

        Table dist = r.table("distribution", "Score distribution").text("band", "Score").col("students", t.get("student") + "s", INT)
                .col("share", "Share", PERCENT).chart("band", "students");
        for (int lo = 0; lo < 100; lo += 10) {
            int from = lo, to = lo + 10;
            long count = pcts.stream().filter(p -> p >= from && (to == 100 ? p <= 100 : p < to)).count();
            dist.add(from + "–" + (to == 100 ? 100 : to - 1) + "%", count, pct(count, pcts.size()));
        }
        Table gr = r.table("grades", "Grades").text("grade", "Grade").col("students", t.get("student") + "s", INT)
                .col("share", "Share", PERCENT).chart("grade", "students");
        long graded = grades.values().stream().mapToLong(Long::longValue).sum();
        grades.forEach((g, c) -> gr.add(g, c, pct(c, graded)));

        List<Double> sorted = pcts.stream().sorted().toList();
        Double median = sorted.isEmpty() ? null : round1(sorted.size() % 2 == 1 ? sorted.get(sorted.size() / 2)
                : (sorted.get(sorted.size() / 2 - 1) + sorted.get(sorted.size() / 2)) / 2);
        r.stat("Expected", expected.students().size(), "From " + expected.basis().toLowerCase(), null)
         .stat("Sat", sat, null, "good")
         .stat("Absent", absent, null, absent > 0 ? "warn" : null)
         .stat("Not submitted", unfinished, null, unfinished > 0 ? "warn" : null)
         .stat("Average", AcademicReports.fmtPct(round1OrNull(pcts.stream().mapToDouble(d -> d).average())))
         .stat("Median", AcademicReports.fmtPct(median))
         .stat("Highest", AcademicReports.fmtPct(sorted.isEmpty() ? null : round1(sorted.get(sorted.size() - 1))))
         .stat("Lowest", AcademicReports.fmtPct(sorted.isEmpty() ? null : round1(sorted.get(0))))
         .stat("Std dev", stdDev(pcts))
         .stat("Pass rate", AcademicReports.fmtPct(pct(pcts.stream().filter(p -> p >= PASS_MARK).count(), pcts.size())));
        if (theory) r.stat("Not yet reviewed", unreviewed, null, unreviewed > 0 ? "warn" : null);
        r.note("Expected = " + student.toLowerCase() + "s registered for the " + t.get("course").toLowerCase() + " in " + session.getName()
                + " within the quiz's index-number range (when nobody registered: active " + student.toLowerCase() + "s of its "
                + t.get("program").toLowerCase() + "s at its " + t.get("level").toLowerCase() + "). Anyone else who sat is listed too.");
        r.note("Each " + student.toLowerCase() + "'s latest submission is shown. A score of " + (int) PASS_MARK + "% or more is a pass.");
        return r;
    }

    // ── Question analysis ────────────────────────────────────────────────────

    public ReportResult questionAnalysis(ReportFilters f) {
        Map<String, String> t = support.terms();
        String student = t.get("student");
        ReportResult r = new ReportResult("question-analysis", "Question analysis",
                "How each question performed: how many got it right, whether it separates strong from weak "
                        + student.toLowerCase() + "s, which options were chosen, and questions whose answer key may be wrong.");
        Lookups l = support.lookups();
        departmentLine(r, f, l);
        if (f.quizId() == null) {
            r.table("objective", "Objective questions").text("no", "No.").text("question", "Question").emptyText("Choose a quiz above.");
            return r;
        }
        Quiz q = chosenQuiz(f, l);
        quizScope(r, q, t);

        List<Questions> questions = questionsRepository.findByQuiz_qId(q.getqId()).stream()
                .sorted(Comparator.comparing(Questions::getQuesId)).toList();
        Map<Long, Map<Long, String[]>> byStudent = new HashMap<>();
        for (ObjectiveAnswerRow a : queries.objectiveAnswers(q.getqId()))
            byStudent.computeIfAbsent(a.studentId(), k -> new HashMap<>()).put(a.questionId(), a.selected());

        // Each student's credit (0–1) on each question, by the same rules as marking
        List<Long> students = new ArrayList<>(byStudent.keySet());
        Map<Long, Map<Long, Double>> credit = new HashMap<>();
        for (Long s : students) {
            Map<Long, Double> m = new HashMap<>();
            for (Questions qu : questions) m.put(qu.getQuesId(), credit(qu, byStudent.get(s).get(qu.getQuesId())));
            credit.put(s, m);
        }
        int n = students.size();
        int group = n >= 10 ? Math.max(1, (int) Math.round(n * 0.27)) : 0;
        List<Long> ranked = students.stream()
                .sorted(Comparator.comparingDouble((Long s) -> credit.get(s).values().stream().mapToDouble(d -> d).sum()).reversed()).toList();
        List<Long> upper = group == 0 ? List.of() : ranked.subList(0, group);
        List<Long> lower = group == 0 ? List.of() : ranked.subList(n - group, n);

        Table tb = r.table("objective", "Objective questions")
                .col("no", "No.", INT).text("question", "Question").text("type", "Type").col("answered", "Answered", INT)
                .col("skipped", "Skipped", INT).col("correct", "Correct", PERCENT).text("difficulty", "Difficulty")
                .col("discrimination", "Discrimination", DECIMAL).text("key", "Answer key");
        for (String letter : LETTERS) tb.col("o" + letter, "Chose " + letter, PERCENT);
        tb.text("flags", "Check").chart("no", "correct")
          .emptyText(questions.isEmpty() ? "This quiz has no objective questions." : "Nobody has submitted objective answers yet.");
        Table flagged = r.table("flagged", "Questions to check")
                .col("no", "No.", INT).text("question", "Question").col("correct", "Correct", PERCENT)
                .col("discrimination", "Discrimination", DECIMAL).text("flags", "Why")
                .emptyText("No question looks wrong or unusually hard.");

        int needCheck = 0, no = 0;
        List<Double> totals = new ArrayList<>();
        students.forEach(s -> totals.add(credit.get(s).values().stream().mapToDouble(d -> d).sum()));
        for (Questions qu : questions) {
            no++;
            Long id = qu.getQuesId();
            QuestionType type = qu.getQuestionType() == null ? QuestionType.MCQ : qu.getQuestionType();
            long answered = students.stream().filter(s -> isAnswered(byStudent.get(s).get(id))).count();
            double p = n == 0 ? 0 : students.stream().mapToDouble(s -> credit.get(s).get(id)).average().orElse(0);
            Double d = group == 0 ? null : round2(upper.stream().mapToDouble(s -> credit.get(s).get(id)).average().orElse(0)
                    - lower.stream().mapToDouble(s -> credit.get(s).get(id)).average().orElse(0));

            List<String> options = new ArrayList<>(Arrays.asList(qu.getOption1(), qu.getOption2(), qu.getOption3(), qu.getOption4()));
            boolean choice = type == QuestionType.MCQ || type == QuestionType.TRUE_FALSE;
            Map<String, Long> chosen = new HashMap<>();
            if (choice) for (Long s : students) {
                String[] sel = byStudent.get(s).get(id);
                if (sel != null) for (String o : sel) if (o != null && !o.isBlank()) chosen.merge(o.trim(), 1L, Long::sum);
            }
            Set<String> correctSet = qu.getcorrect_answer() == null ? Set.of()
                    : Arrays.stream(qu.getcorrect_answer()).filter(Objects::nonNull).map(String::trim).collect(Collectors.toSet());

            List<String> flags = new ArrayList<>();
            if (n >= 5 && p == 0) flags.add("Nobody got it right: check the answer key");
            if (d != null && d < 0) flags.add("Weaker " + student.toLowerCase() + "s did better: check the key or wording");
            else if (d != null && d < 0.2 && p > 0 && p < 1) flags.add("Does not separate strong and weak " + student.toLowerCase() + "s");
            if (n >= 5 && p > 0 && p < 0.3) flags.add("Hard");
            if (n >= 5 && p > 0.95) flags.add("Almost everyone got it right");
            if (choice && n >= 5) {
                long rightCount = correctSet.stream().mapToLong(c -> chosen.getOrDefault(c, 0L)).max().orElse(0);
                for (int i = 0; i < options.size(); i++) {
                    String o = options.get(i);
                    if (o == null || o.isBlank() || correctSet.contains(o.trim())) continue;
                    long c = chosen.getOrDefault(o.trim(), 0L);
                    if (c > rightCount) flags.add("More chose " + LETTERS[i] + " than the right answer");
                }
            }
            if (flags.stream().anyMatch(x -> x.startsWith("Nobody") || x.startsWith("Weaker") || x.startsWith("More chose"))) needCheck++;

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("no", no);
            row.put("question", plain(qu.getContent()));
            row.put("type", typeName(type));
            row.put("answered", answered);
            row.put("skipped", n - answered);
            row.put("correct", n == 0 ? null : round1(p * 100));
            row.put("difficulty", n == 0 ? null : p >= 0.9 ? "Very easy" : p >= 0.7 ? "Easy" : p >= 0.3 ? "Moderate" : "Hard");
            row.put("discrimination", d);
            row.put("key", key(qu, type, options, correctSet));
            for (int i = 0; i < LETTERS.length; i++) {
                String o = i < options.size() ? options.get(i) : null;
                row.put("o" + LETTERS[i], !choice || o == null || o.isBlank() ? null : pct(chosen.getOrDefault(o.trim(), 0L), answered));
            }
            row.put("flags", String.join("; ", flags));
            tb.add(row);
            if (!flags.isEmpty()) flagged.add(no, row.get("question"), row.get("correct"), d, row.get("flags"));
        }

        // Theory questions: marks per question number
        List<TheoryAnswerRow> theory = queries.theoryAnswers(q.getqId());
        Map<String, String> texts = theoryQuestionsRepository.findByQuiz_qId(q.getqId()).stream()
                .filter(x -> x.getQuesNo() != null)
                .collect(Collectors.toMap(TheoryQuestions::getQuesNo, x -> plain(x.getQuestion()), (a, b) -> a));
        long submitted = queries.quizScores(q.getqId()).stream().map(QuizScoreRow::studentId).distinct().count();
        Table th = r.table("theory", "Theory questions")
                .text("no", "Question").text("question", "Text").col("max", "Marks", NUMBER).col("answered", "Answered", INT)
                .col("chosen", "Chosen by", PERCENT).col("avg", "Average mark", NUMBER).col("avgPct", "Average", PERCENT)
                .col("full", "Full marks", INT).col("zero", "Zero", INT).text("flags", "Check").chart("no", "avgPct")
                .emptyText("No theory answers for this quiz.");
        Map<String, List<TheoryAnswerRow>> byNo = theory.stream().collect(Collectors.groupingBy(TheoryAnswerRow::questionNo));
        byNo.keySet().stream().sorted(Comparator.comparing((String k) -> k.replaceAll("\\d", "")).thenComparingInt(TeachingReports::number))
                .forEach(k -> {
                    List<TheoryAnswerRow> rows = byNo.get(k);
                    double max = rows.stream().mapToDouble(TheoryAnswerRow::maxMarks).max().orElse(0);
                    double avg = rows.stream().mapToDouble(TheoryAnswerRow::score).average().orElse(0);
                    Double avgPct = max <= 0 ? null : round1(avg * 100 / max);
                    th.add(k, texts.get(k), max, rows.size(), pct(rows.size(), submitted), round1(avg), avgPct,
                            rows.stream().filter(x -> max > 0 && x.score() >= max).count(), rows.stream().filter(x -> x.score() <= 0).count(),
                            avgPct != null && avgPct < 40 && rows.size() >= 5 ? "Low average: check the marking guide or teaching" : null);
                });

        r.stat("Objective questions", questions.size())
         .stat(student + "s analysed", n)
         .stat("Average objective score", questions.isEmpty() || totals.isEmpty() ? null
                 : AcademicReports.fmtPct(round1(totals.stream().mapToDouble(x -> x).average().orElse(0) * 100 / questions.size())))
         .stat("Questions to check", needCheck, "Possible answer-key or wording problems", needCheck > 0 ? "bad" : null)
         .stat("Theory questions", byNo.size());
        r.note("Correct = average credit on the question (matching questions earn part credit per pair). Difficulty: 70%+ easy, 30–70% moderate, under 30% hard.");
        r.note(group == 0 ? "Discrimination needs at least 10 " + student.toLowerCase() + "s, so it is not shown."
                : "Discrimination = correct share in the top " + group + " " + student.toLowerCase() + "s minus the bottom " + group
                + " (27% each, by objective score). 0.3+ is good; under 0.2 the question does not separate them; below 0 suggests a wrong key.");
        r.note("Uses each " + student.toLowerCase() + "'s latest attempt. \"Chose A–D\" is the share of those who answered.");
        return r;
    }

    /** Credit (0–1) for a student's answer, as marking gives it. */
    static double credit(Questions q, String[] selected) {
        if (!isAnswered(selected)) return 0;
        QuestionType type = q.getQuestionType() == null ? QuestionType.MCQ : q.getQuestionType();
        if (type == QuestionType.MATCHING) {
            List<MatchingPair> pairs = q.getMatchingPairs();
            if (pairs == null || pairs.isEmpty()) return 0;
            int right = 0;
            for (int i = 0; i < pairs.size(); i++)
                if (pairs.get(i).getAnswer() != null && i < selected.length && pairs.get(i).getAnswer().equals(selected[i])) right++;
            return (double) right / pairs.size();
        }
        return AnswerMatcher.isCorrect(type, q.getcorrect_answer(), q.getTolerance(), Arrays.asList(selected)) ? 1 : 0;
    }

    static boolean isAnswered(String[] selected) {
        return selected != null && Arrays.stream(selected).anyMatch(s -> s != null && !s.isBlank());
    }

    private static String key(Questions q, QuestionType type, List<String> options, Set<String> correct) {
        if (type == QuestionType.MATCHING) return (q.getMatchingPairs() == null ? 0 : q.getMatchingPairs().size()) + " pairs";
        if (type == QuestionType.MCQ || type == QuestionType.TRUE_FALSE) {
            List<String> letters = new ArrayList<>();
            for (int i = 0; i < options.size(); i++)
                if (options.get(i) != null && correct.contains(options.get(i).trim())) letters.add(LETTERS[i]);
            if (!letters.isEmpty()) return String.join(", ", letters);
        }
        return String.join(" / ", correct);
    }

    private static String typeName(QuestionType t) {
        return switch (t) {
            case MCQ -> "Multiple choice";
            case TRUE_FALSE -> "True / false";
            case MATCHING -> "Matching";
            case FILL_BLANK -> "Fill in the blank";
            case NUMERIC -> "Numeric";
        };
    }

    /** Question text without formatting, shortened for a table cell. */
    static String plain(String html) {
        if (html == null) return null;
        String text = Jsoup.parse(html).text().trim();
        return text.length() > 140 ? text.substring(0, 139) + "…" : text;
    }

    private static int number(String s) {
        try { return Integer.parseInt(s.replaceAll("\\D", "")); } catch (Exception e) { return 0; }
    }

    private static Double round2(double v) { return Math.round(v * 100.0) / 100.0; }

    // ── Course assessment summary ────────────────────────────────────────────

    public ReportResult courseAssessment(ReportFilters f) {
        Map<String, String> t = support.terms();
        String course = t.get("course"), student = t.get("student");
        ReportResult r = new ReportResult("course-assessment", "Continuous assessment summary",
                "Every " + student.toLowerCase() + "'s score on every quiz in one " + course.toLowerCase()
                        + ", with their average, trend and who needs support.");
        Lookups l = support.lookups();
        departmentLine(r, f, l);
        if (f.courseId() == null) {
            r.table("students", t.get("student") + "s").text("studentId", t.get("studentId")).text("name", "Name")
                    .emptyText("Choose a " + course.toLowerCase() + " above.");
            return r;
        }
        Category c = l.course(f.courseId());
        if (c == null) throw new IllegalArgumentException(course + " not found.");
        if (!l.courseMatches(c, new ReportFilters(null, f.departmentId(), null, null, null, null, null, null, null, null, f.lecturerId())))
            throw new AccessDeniedException("That " + course.toLowerCase() + " is outside your reports.");
        Period p = support.period(f);
        r.scope(course + ": " + courseLabel(c));
        support.periodScope(r, p);

        List<ResultRow> results = queries.quizResults(p).stream()
                .filter(x -> l.quiz(x.quizId()) != null && l.quiz(x.quizId()).getCategory() != null
                        && c.getCid().equals(l.quiz(x.quizId()).getCategory().getCid()) && x.percentage() != null)
                .toList();
        // Latest result per student and quiz
        Map<Long, Map<Long, ResultRow>> grid = new HashMap<>();
        for (ResultRow x : results) {
            Map<Long, ResultRow> m = grid.computeIfAbsent(x.studentId(), k -> new HashMap<>());
            ResultRow had = m.get(x.quizId());
            if (had == null || (x.submittedAt() != null && (had.submittedAt() == null || x.submittedAt().isAfter(had.submittedAt()))))
                m.put(x.quizId(), x);
        }
        Map<Long, LocalDate> quizDay = new HashMap<>();
        results.forEach(x -> {
            Quiz q = l.quiz(x.quizId());
            LocalDate d = q.getQuizDate() != null ? q.getQuizDate() : x.submittedAt() == null ? null : x.submittedAt().toLocalDate();
            if (d != null) quizDay.merge(x.quizId(), d, (a, b) -> a.isBefore(b) ? a : b);
        });
        List<Quiz> quizzes = results.stream().map(ResultRow::quizId).distinct().map(l::quiz)
                .sorted(Comparator.comparing((Quiz q) -> quizDay.getOrDefault(q.getqId(), LocalDate.MAX)).thenComparing(Quiz::getqId)).toList();

        Long sessionId = f.sessionId() != null ? f.sessionId() : support.currentSession().getId();
        Set<Long> registered = new HashSet<>();
        for (RegistrationRow x : queries.registrations())
            if (c.getCid().equals(x.courseId()) && sessionId.equals(x.sessionId())) registered.add(x.studentId());
        Map<Long, StudentRow> info = queries.students().stream().collect(Collectors.toMap(StudentRow::id, s -> s));
        Set<Long> everyone = new TreeSet<>(registered);
        everyone.addAll(grid.keySet());

        Table tb = r.table("students", t.get("student") + "s")
                .text("studentId", t.get("studentId")).text("name", "Name").text("programme", t.get("program")).col("level", t.get("level"), INT);
        for (Quiz q : quizzes) tb.col("q" + q.getqId(), q.getTitle(), PERCENT);
        tb.col("taken", "Taken", INT).col("missed", "Missed", INT).col("average", "Average", PERCENT).col("best", "Best", PERCENT)
          .col("lowest", "Lowest", PERCENT).col("trend", "Trend (points)", NUMBER).text("status", "Status")
          .emptyText("No " + student.toLowerCase() + " registered for this " + course.toLowerCase() + " or sat one of its quizzes in this period.");
        Table help = r.table("support", "Needs support")
                .subtitle(student + "s with an average under " + (int) PASS_MARK + "%, two or more missed quizzes, or a fall of 15 points or more.")
                .text("studentId", t.get("studentId")).text("name", "Name").text("email", "Email").text("phone", "Phone")
                .col("average", "Average", PERCENT).col("missed", "Missed", INT).col("trend", "Trend (points)", NUMBER).text("why", "Why")
                .emptyText("Nobody needs support on these figures.");

        List<Double> averages = new ArrayList<>();
        int needSupport = 0, neverSat = 0;
        for (StudentRow s : everyone.stream().map(info::get).filter(Objects::nonNull)
                .sorted(Comparator.comparing(x -> Objects.toString(x.username(), ""))).toList()) {
            Map<Long, ResultRow> mine = grid.getOrDefault(s.id(), Map.of());
            List<Double> scores = new ArrayList<>();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("studentId", s.username());
            row.put("name", s.name());
            row.put("programme", l.programName(s.programId()));
            row.put("level", s.level());
            for (Quiz q : quizzes) {
                ResultRow x = mine.get(q.getqId());
                row.put("q" + q.getqId(), x == null ? null : round1(x.percentage()));
                if (x != null) scores.add(x.percentage());
            }
            int missed = registered.contains(s.id()) ? quizzes.size() - scores.size() : 0;
            Double avg = scores.isEmpty() ? null : round1(scores.stream().mapToDouble(d -> d).average().orElse(0));
            Double trend = scores.size() >= 2 ? round1(scores.get(scores.size() - 1) - scores.get(0)) : null;
            List<String> why = new ArrayList<>();
            if (scores.isEmpty() && !quizzes.isEmpty()) why.add("Has not sat any quiz");
            if (avg != null && avg < PASS_MARK) why.add("Average " + avg + "%");
            if (missed >= 2) why.add("Missed " + missed + " quizzes");
            if (trend != null && scores.size() >= 3 && trend <= -15) why.add("Down " + Math.abs(trend) + " points");
            row.put("taken", scores.size());
            row.put("missed", missed);
            row.put("average", avg);
            row.put("best", scores.isEmpty() ? null : round1(Collections.max(scores)));
            row.put("lowest", scores.isEmpty() ? null : round1(Collections.min(scores)));
            row.put("trend", trend);
            row.put("status", why.isEmpty() ? "On track" : "Needs support");
            tb.add(row);
            if (avg != null) averages.add(avg);
            if (scores.isEmpty()) neverSat++;
            if (!why.isEmpty()) {
                needSupport++;
                help.add(s.username(), s.name(), s.email(), s.phone(), avg, missed, trend, String.join("; ", why));
            }
        }

        Table qt = r.table("quizzes", "Quizzes").col("date", "Date", DATE).text("quiz", "Quiz").col("sat", "Sat", INT)
                .col("missed", "Missed", INT).col("average", "Average", PERCENT).col("max", "Highest", PERCENT)
                .col("min", "Lowest", PERCENT).col("pass", "Pass rate", PERCENT).chart("quiz", "average");
        for (Quiz q : quizzes) {
            List<Double> ps = grid.values().stream().map(m -> m.get(q.getqId())).filter(Objects::nonNull).map(ResultRow::percentage).toList();
            long missed = registered.stream().filter(id -> !grid.getOrDefault(id, Map.of()).containsKey(q.getqId())).count();
            qt.add(day(quizDay.get(q.getqId())), q.getTitle(), ps.size(), missed, round1OrNull(ps.stream().mapToDouble(d -> d).average()),
                    ps.isEmpty() ? null : round1(Collections.max(ps)), ps.isEmpty() ? null : round1(Collections.min(ps)),
                    pct(ps.stream().filter(x -> x >= PASS_MARK).count(), ps.size()));
        }

        r.stat(student + "s", everyone.size())
         .stat("Quizzes", quizzes.size())
         .stat("Class average", AcademicReports.fmtPct(averages.isEmpty() ? null : round1(averages.stream().mapToDouble(d -> d).average().orElse(0))))
         .stat("Needs support", needSupport, null, needSupport > 0 ? "warn" : null)
         .stat("Never sat a quiz", neverSat, null, neverSat > 0 ? "warn" : null);
        r.note(student + "s = those registered for the " + course.toLowerCase() + " in the session, plus anyone else who sat one of its quizzes. "
                + "Missed counts quizzes a registered " + student.toLowerCase() + " has no result for.");
        r.note("Scores are each " + student.toLowerCase() + "'s latest submission, as a percentage. Trend = last quiz minus first quiz.");
        return r;
    }

    // ── Re-mark requests ─────────────────────────────────────────────────────

    public ReportResult remarkRequests(ReportFilters f) {
        Map<String, String> t = support.terms();
        ReportResult r = new ReportResult("remark-requests", "Re-mark requests",
                "Every request to re-mark a script: who asked and why, how long it waited, and what changed.");
        Lookups l = support.lookups();
        Period p = support.period(f);
        support.periodScope(r, p);
        if (f.lecturerId() == null) support.placeScope(r, f, l);
        if (f.courseId() != null && l.course(f.courseId()) != null) r.scope(t.get("course") + ": " + courseLabel(l.course(f.courseId())));
        if (f.hasStatus()) r.scope("Status: " + remarkStatus(f.status()));

        Map<Long, StudentRow> info = queries.students().stream().collect(Collectors.toMap(StudentRow::id, s -> s));
        LocalDateTime now = LocalDateTime.now();
        List<RemarkDetailRow> rows = queries.remarkDetails(p).stream()
                .filter(x -> l.quizMatches(x.quizId(), f))
                .filter(x -> f.courseId() == null || (l.courseOfQuiz(x.quizId()) != null && f.courseId().equals(l.courseOfQuiz(x.quizId()).getCid())))
                .filter(x -> !f.hasStatus() || x.status().name().equalsIgnoreCase(f.status()))
                // Waiting first, oldest at the top; then answered, newest first
                .sorted(Comparator.comparing((RemarkDetailRow x) -> x.status() != RemarkRequest.Status.PENDING)
                        .thenComparing((a, b) -> {
                            LocalDateTime x = a.createdAt() == null ? now : a.createdAt(), y = b.createdAt() == null ? now : b.createdAt();
                            return a.status() == RemarkRequest.Status.PENDING ? x.compareTo(y) : y.compareTo(x);
                        }))
                .toList();

        Table tb = r.table("requests", "Requests")
                .col("asked", "Asked", DATETIME).text("studentId", t.get("studentId")).text("name", "Name").text("course", t.get("course"))
                .text("quiz", "Quiz").text("reason", "Reason").text("status", "Status").col("before", "Mark before", NUMBER)
                .col("after", "Mark after", NUMBER).col("change", "Change", NUMBER).text("by", "Answered by")
                .col("answered", "Answered", DATETIME).col("days", "Days", INT).text("response", "Response")
                .emptyText("No re-mark requests for these filters.");
        long waitingDays = 0;
        for (RemarkDetailRow x : rows) {
            StudentRow s = info.get(x.studentId());
            Quiz q = l.quiz(x.quizId());
            BigDecimal change = x.scoreBefore() != null && x.scoreAfter() != null ? x.scoreAfter().subtract(x.scoreBefore()) : null;
            long days = x.createdAt() == null ? 0 : Duration.between(x.createdAt(), x.respondedAt() != null ? x.respondedAt() : now).toDays();
            if (x.status() == RemarkRequest.Status.PENDING) waitingDays = Math.max(waitingDays, days);
            tb.add(minute(x.createdAt()), s == null ? null : s.username(), s == null ? null : s.name(),
                    q == null || q.getCategory() == null ? null : q.getCategory().getCourseCode(), q == null ? null : q.getTitle(),
                    x.reason(), remarkStatus(x.status().name()), x.scoreBefore(), x.scoreAfter(), change, x.responder(),
                    minute(x.respondedAt()), days, x.response());
        }

        Table byQuiz = r.table("quizzes", "By quiz").text("course", t.get("course")).text("quiz", "Quiz").col("requests", "Requests", INT)
                .col("waiting", "Waiting", INT).col("remarked", "Re-marked", INT).col("rejected", "Turned down", INT)
                .col("change", "Average change", NUMBER).chart("quiz", "requests");
        rows.stream().collect(Collectors.groupingBy(RemarkDetailRow::quizId, LinkedHashMap::new, Collectors.toList())).forEach((qid, g) -> {
            Quiz q = l.quiz(qid);
            byQuiz.add(q == null || q.getCategory() == null ? null : q.getCategory().getCourseCode(), q == null ? null : q.getTitle(), g.size(),
                    count(g, RemarkRequest.Status.PENDING), count(g, RemarkRequest.Status.RESOLVED), count(g, RemarkRequest.Status.REJECTED),
                    round1OrNull(g.stream().filter(x -> x.scoreBefore() != null && x.scoreAfter() != null)
                            .mapToDouble(x -> x.scoreAfter().subtract(x.scoreBefore()).doubleValue()).average()));
        });

        long waiting = count(rows, RemarkRequest.Status.PENDING);
        r.stat("Requests", rows.size())
         .stat("Waiting", waiting, waiting > 0 ? "Oldest " + waitingDays + " days" : null, waiting > 0 ? "warn" : null)
         .stat("Re-marked", count(rows, RemarkRequest.Status.RESOLVED))
         .stat("Turned down", count(rows, RemarkRequest.Status.REJECTED))
         .stat("Average days to answer", round1OrNull(rows.stream().filter(x -> x.createdAt() != null && x.respondedAt() != null)
                 .mapToDouble(x -> Duration.between(x.createdAt(), x.respondedAt()).toHours() / 24.0).average()))
         .stat("Average mark change", round1OrNull(rows.stream().filter(x -> x.scoreBefore() != null && x.scoreAfter() != null)
                 .mapToDouble(x -> x.scoreAfter().subtract(x.scoreBefore()).doubleValue()).average()));
        r.note("Waiting requests come first, oldest at the top. Days = days to answer, or days waiting so far.");
        return r;
    }

    private static long count(List<RemarkDetailRow> rows, RemarkRequest.Status s) {
        return rows.stream().filter(x -> x.status() == s).count();
    }

    static String remarkStatus(String s) {
        return switch (s == null ? "" : s.toUpperCase()) {
            case "PENDING" -> "Waiting";
            case "RESOLVED" -> "Re-marked";
            case "REJECTED" -> "Turned down";
            default -> s;
        };
    }
}
