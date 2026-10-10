package com.exam.service.reports;

import com.exam.helper.TheoryGroups;
import com.exam.model.QuizType;
import com.exam.model.exam.*;
import com.exam.repository.NumberOfTheoryToAnswerRepository;
import com.exam.repository.QuestionsRepository;
import com.exam.repository.TheoryQuestionsRepository;
import com.exam.service.QuestionImageService;
import com.exam.service.academic.InstitutionService;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

import static com.exam.service.reports.ReportResult.*;

/**
 * A quiz's question paper, with or without the Section A answers, laid out like a printed
 * examination paper: a cover page (institution, examination, course, time allowed, registration
 * number boxes, instructions, date and examiner), Section A in two columns, Section B on its own
 * pages. One model drives both the report page (tables) and the PDF ({@link QuestionPaperPdf}).
 */
@Service
@Transactional(readOnly = true)
public class QuestionPapers {

    private static final String[] LETTERS = {"A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L"};
    private static final String[] ROMAN = {"i", "ii", "iii", "iv", "v", "vi", "vii", "viii", "ix", "x", "xi", "xii", "xiii", "xiv", "xv"};
    /** Section A diagrams fit a column; Section B diagrams can use the page width. */
    private static final int COLUMN_IMG_W_PT = 230, COLUMN_IMG_H_PT = 170, PAGE_IMG_W_PT = 380, PAGE_IMG_H_PT = 260;

    @Autowired private ReportSupport support;
    @Autowired private ReportQueries queries;
    @Autowired private QuestionsRepository questionsRepository;
    @Autowired private TheoryQuestionsRepository theoryQuestionsRepository;
    @Autowired private NumberOfTheoryToAnswerRepository theoryRulesRepository;
    @Autowired private QuestionImageService questionImageService;
    @Autowired private InstitutionService institutionService;

    // ── Model ────────────────────────────────────────────────────────────────

    public record Detail(String label, String value) {}

    public record Choice(String letter, String text, boolean correct) {}

    /** A matching question's prompt and (in the answer version) the letter of its answer. */
    public record Prompt(String label, String text, String answerLetter) {}

    public record Objective(int number, String html, String text, QuestionImageService.PdfImage image,
                            List<Choice> choices, List<Prompt> prompts, List<Choice> matchAnswers, boolean typed, String key) {}

    /** label: the part ("a)") when the question has parts; null for a single question. */
    public record TheoryItem(String no, String label, String html, String text, QuestionImageService.PdfImage image, String marks) {}

    public record TheoryGroup(String key, boolean compulsory, String marks, List<TheoryItem> items) {}

    /** The cover page, as on a printed examination paper. */
    public record Cover(List<String> heading, String programme, String examLine, String semesterLine, String courseLine,
                        String timeAllowed, String registrationLabel, List<String> registrationCells, List<String> instructions,
                        String dateDay, String dateSuffix, String dateRest, String startTime, String examiner) {}

    public record Paper(boolean answers, String title, Cover cover, List<Detail> details, List<String> instructions,
                        List<Objective> objective, String sectionAHeading,
                        List<TheoryGroup> theory, String sectionBHeading, String theoryNote) {
        public boolean hasObjective() { return !objective.isEmpty(); }
        public boolean hasTheory() { return !theory.isEmpty(); }
    }

    // ── Reports ──────────────────────────────────────────────────────────────

    public ReportResult questionPaper(ReportFilters f, boolean answers) {
        Map<String, String> t = support.terms();
        ReportResult r = answers
                ? new ReportResult("question-paper-answers", "Question paper with answers",
                        "The quiz's questions with the answer key for Section A (correct options, accepted answers, matching pairs) and every theory question.")
                : new ReportResult("question-paper", "Question paper",
                        "The quiz's questions only, ready to print, with the quiz details and instructions to candidates.");
        r.setPrintable(true);
        Lookups l = support.lookups();
        if (f.departmentId() != null && f.lecturerId() == null)
            r.scope("Department: " + Objects.toString(l.deptName(f.departmentId()), "?"));
        if (f.quizId() == null) {
            r.table("details", "Quiz details").text("label", "Detail").text("value", "").emptyText("Choose a quiz above.");
            return r;
        }
        Quiz q = TeachingReports.chosenQuiz(f, l);
        Paper paper = build(q, answers, l);
        r.setDocument(paper);

        r.scope("Quiz: " + q.getTitle());
        if (q.getCategory() != null) r.scope(t.get("course") + ": " + TeachingReports.courseLabel(q.getCategory()));
        if (answers) r.scope("Answer key: confidential");

        Table details = r.table("details", "Quiz details").text("label", "Detail").text("value", "Value");
        paper.details().forEach(d -> details.add(d.label(), d.value()));
        Table instr = r.table("instructions", "Instructions to candidates").text("instruction", "Instruction");
        paper.instructions().forEach(instr::add);

        if (paper.hasObjective()) {
            Table obj = r.table("objective", "Section A: objective questions").col("no", "No.", INT).text("question", "Question");
            for (int i = 0; i < 4; i++) obj.text("o" + LETTERS[i], LETTERS[i]);
            if (answers) obj.text("answer", "Answer");
            for (Objective o : paper.objective()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("no", o.number());
                String text = o.text();
                if (!o.prompts().isEmpty())
                    text += " | Match: " + o.prompts().stream().map(p -> "(" + p.label() + ") " + p.text()).collect(Collectors.joining("; "))
                            + " | With: " + o.matchAnswers().stream().map(c -> c.letter() + ". " + c.text()).collect(Collectors.joining("; "));
                if (o.image() != null) text += " [diagram]";
                row.put("question", text);
                for (int i = 0; i < 4; i++) row.put("o" + LETTERS[i], i < o.choices().size() ? o.choices().get(i).text() : null);
                if (answers) row.put("answer", o.key());
                obj.add(row);
            }
        }
        if (paper.hasTheory()) {
            Table th = r.table("theory", "Section B: theory questions").subtitle(paper.theoryNote())
                    .text("no", "Question").text("question", "Text").text("marks", "Marks").text("compulsory", "Compulsory");
            for (TheoryGroup g : paper.theory())
                for (TheoryItem it : g.items()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("no", it.no());
                    row.put("question", it.text() + (it.image() != null ? " [diagram]" : ""));
                    row.put("marks", it.marks());
                    row.put("compulsory", g.compulsory() ? "Yes" : null);
                    th.add(row);
                }
        }
        if (!paper.hasObjective() && !paper.hasTheory())
            r.table("objective", "Questions").text("question", "Question").emptyText("This quiz has no questions yet.");

        long theoryCount = paper.theory().stream().mapToLong(g -> g.items().size()).sum();
        r.stat("Objective questions", paper.objective().size())
         .stat("Theory questions", theoryCount)
         .stat("Duration", detail(paper.details(), "Duration"))
         .stat("Date", detail(paper.details(), "Date"));
        r.note("Download the PDF for the printable paper: a cover page, then Section A in two columns and Section B on its own pages.");
        r.note("Questions are printed in the order they were set; on screen each candidate gets them in a shuffled order.");
        if (answers) r.note("This is the answer key. Keep it confidential; opening and printing it is recorded in the audit log.");
        return r;
    }

    private static String detail(List<Detail> details, String label) {
        return details.stream().filter(d -> d.label().equals(label)).map(Detail::value).findFirst().orElse("");
    }

    // ── Building the paper ───────────────────────────────────────────────────

    Paper build(Quiz q, boolean answers, Lookups l) {
        Map<String, String> t = support.terms();
        Category c = q.getCategory();
        boolean objectiveOn = q.getQuizType() != QuizType.THEORY;
        boolean theoryOn = q.getQuizType() == QuizType.THEORY || q.getQuizType() == QuizType.BOTH;

        List<Questions> questions = objectiveOn ? questionsRepository.findByQuiz_qId(q.getqId()).stream()
                .sorted(Comparator.comparing(Questions::getQuesId)).toList() : List.of();
        List<TheoryQuestions> theoryQs = theoryOn ? theoryQuestionsRepository.findByQuiz_qId(q.getqId()) : List.of();
        List<NumberOfTheoryToAnswer> rules = theoryRulesRepository.findByQuiz_qId(q.getqId());
        int toAnswer = rules.isEmpty() || rules.get(0).getTotalQuestToAnswer() == null ? 0 : rules.get(0).getTotalQuestToAnswer();
        int theoryMinutes = rules.isEmpty() || rules.get(0).getTimeAllowed() == null ? 0 : rules.get(0).getTimeAllowed();
        int objectiveMinutes = objectiveOn ? minutes(q.getQuizTime()) : 0;

        // Section A
        double maxObjective = q.getMaxMarks() == null ? 0 : q.getMaxMarks();
        List<Objective> objective = new ArrayList<>();
        int n = 0;
        for (Questions qu : questions) objective.add(objective(++n, qu, answers));

        // Section B, grouped by question number (1, 1a, 1b … form question 1)
        Map<String, List<TheoryQuestions>> groups = new TreeMap<>(Comparator.comparing((String k) -> k.replaceAll("\\d", ""))
                .thenComparingInt(QuestionPapers::number).thenComparing(k -> k));
        for (TheoryQuestions tq : theoryQs) groups.computeIfAbsent(TheoryGroups.key(tq.getQuesNo()), k -> new ArrayList<>()).add(tq);
        List<TheoryGroup> theory = new ArrayList<>();
        List<String> compulsory = new ArrayList<>();
        Set<String> groupMarks = new HashSet<>();
        groups.forEach((key, items) -> {
            items.sort(Comparator.comparing((TheoryQuestions x) -> Objects.toString(x.getQuesNo(), ""), QuestionPapers::natural));
            boolean comp = items.stream().anyMatch(x -> Boolean.TRUE.equals(x.getIsCompulsory()));
            if (comp) compulsory.add(key);
            double total = items.stream().mapToDouble(x -> parse(x.getMarks())).sum();
            groupMarks.add(fmt(total));
            theory.add(new TheoryGroup(key, comp, total > 0 ? fmt(total) : null, items.stream().map(x -> {
                String no = Objects.toString(x.getQuesNo(), key).trim();
                return new TheoryItem(no, partLabel(no, key), clean(x.getQuestion()), TeachingReports.plainFull(x.getQuestion()),
                        questionImageService.loadForPdf(x.getImage(), PAGE_IMG_W_PT, PAGE_IMG_H_PT),
                        parse(x.getMarks()) > 0 ? fmt(parse(x.getMarks())) : x.getMarks());
            }).toList()));
        });
        int groupCount = theory.size();
        boolean answerAll = toAnswer <= 0 || toAnswer >= groupCount;
        String theoryMarks = groupMarks.size() == 1 && !answerAll ? fmt(parse(groupMarks.iterator().next()) * toAnswer)
                : answerAll ? fmt(theory.stream().mapToDouble(g -> parse(g.marks())).sum()) : null;
        boolean hasA = !objective.isEmpty(), hasB = groupCount > 0;
        int totalMinutes = (hasA ? objectiveMinutes : 0) + (hasB ? theoryMinutes : 0);

        // Details (report page)
        String session = support.sessionOn(q.getQuizDate()).getName();
        List<Detail> details = new ArrayList<>();
        if (c != null) {
            details.add(new Detail(t.get("course") + " code", Objects.toString(c.getCourseCode(), "-")));
            details.add(new Detail(t.get("course") + " title", Objects.toString(c.getTitle(), "-")));
        }
        details.add(new Detail("Quiz", Objects.toString(q.getTitle(), "-")));
        details.add(new Detail("Date", q.getQuizDate() == null ? "Not scheduled" : q.getQuizDate().format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy"))));
        if (q.getStartTime() != null) details.add(new Detail("Start time", q.getStartTime().format(DateTimeFormatter.ofPattern("HH:mm"))));
        details.add(new Detail("Duration", duration(objectiveMinutes, theoryMinutes, hasA, hasB)));
        details.add(new Detail("Session", session));
        if (c != null && c.getLevel() != null) details.add(new Detail(t.get("level"), ReportSupport.normLevel(c.getLevel())));
        if (c != null && c.getSemester() != null) details.add(new Detail(t.get("semester"), support.semesterName(c.getSemester())));
        if (c != null && !l.courseProgramNames(c).isEmpty()) details.add(new Detail(t.get("program") + "s", l.courseProgramNames(c)));
        if (hasA) details.add(new Detail("Section A", objective.size() + " objective question" + (objective.size() == 1 ? "" : "s") + ", " + fmt(maxObjective) + " marks"));
        if (hasB) details.add(new Detail("Section B", (answerAll ? "Answer all " + groupCount : "Answer " + toAnswer + " of " + groupCount)
                + " theory question" + (groupCount == 1 ? "" : "s") + (theoryMarks != null ? ", " + theoryMarks + " marks" : "")));
        String examiner = ReportSupport.name(Lookups.lecturerOf(q));
        if (examiner != null) details.add(new Detail(t.get("lecturer"), examiner));

        // Instructions (cover page and report page)
        List<String> instructions = new ArrayList<>();
        if (hasA) instructions.add(hasB ? "Answer all questions in Section A." : "Answer all questions.");
        if (hasB) {
            String which = answerAll ? "Answer all questions in Section B."
                    : "Answer any " + words(toAnswer) + " (" + toAnswer + ") question" + (toAnswer == 1 ? "" : "s") + " in Section B.";
            if (!compulsory.isEmpty() && !answerAll)
                which += " Question" + (compulsory.size() == 1 ? " " + compulsory.get(0) + " is" : "s " + String.join(", ", compulsory) + " are")
                        + " compulsory.";
            instructions.add(which);
        }

        // Section headings, as on a printed paper
        boolean allChoice = objective.stream().allMatch(o -> !o.choices().isEmpty());
        boolean anyChoice = objective.stream().anyMatch(o -> !o.choices().isEmpty());
        String sectionA = allChoice ? "Section A: Choose the most appropriate answer from the options provided by circling the corresponding letter"
                : anyChoice ? "Section A: Answer all questions. For multiple-choice questions, circle the letter of the most appropriate answer"
                : "Section A: Answer all questions";
        String sectionB = answerAll ? "Section B: Answer all questions in this section"
                : "Section B: Answer any " + words(toAnswer) + " (" + toAnswer + ") question" + (toAnswer == 1 ? "" : "s") + " from this section";
        if (!compulsory.isEmpty() && !answerAll)
            sectionB += ". Question" + (compulsory.size() == 1 ? " " + compulsory.get(0) + " is" : "s " + String.join(", ", compulsory) + " are") + " compulsory";
        String theoryNote = !hasB ? null : (answerAll ? "Answer all questions" : "Answer " + toAnswer + " of " + groupCount + " questions")
                + (compulsory.isEmpty() || answerAll ? "" : " · compulsory: " + String.join(", ", compulsory));

        Cover cover = cover(q, c, l, t, session, totalMinutes, instructions, examiner);
        return new Paper(answers, q.getTitle(), cover, details, instructions, objective, sectionA, theory, sectionB, theoryNote);
    }

    private Cover cover(Quiz q, Category c, Lookups l, Map<String, String> t, String session, int totalMinutes,
                        List<String> instructions, String examiner) {
        List<String> heading = new ArrayList<>();
        heading.add(institutionService.name());
        String subtitle = institutionService.subtitle();
        if (subtitle != null) Arrays.stream(subtitle.split("\\r?\\n|\\|")).map(String::trim).filter(s -> !s.isEmpty()).forEach(heading::add);
        // The programme(s) and their department(s): the quiz's own, else the course's, else those of
        // the students registered for the course
        List<Long> programmeIds = paperProgrammes(q, c);
        List<String> programmes = programmeIds.stream().map(l::programName).filter(Objects::nonNull).distinct().sorted().toList();
        Set<String> depts = new LinkedHashSet<>();
        programmeIds.stream().map(l::deptOfProgram).filter(Objects::nonNull).map(l::deptName).filter(Objects::nonNull).forEach(depts::add);
        if (depts.isEmpty() && c != null) l.courseDepts(c).stream().map(l::deptName).filter(Objects::nonNull).forEach(depts::add);
        for (String d : depts) heading.add(d.toLowerCase().startsWith("department") ? d : "Department of " + d);
        String programme = programmes.isEmpty() ? null
                : t.get("program") + (programmes.size() > 1 ? "s" : "") + ": " + String.join(", ", programmes);
        String examLine = Objects.toString(q.getTitle(), "Examination") + ", " + session + " academic year";
        String semesterLine = c == null || c.getSemester() == null ? null : support.semesterName(c.getSemester());
        String courseLine = c == null ? null
                : (c.getCourseCode() == null ? "" : c.getCourseCode() + ": ") + Objects.toString(c.getTitle(), "");

        String registrationLabel = support.isSchool() ? t.get("studentId") : "Student Registration Number";
        List<String> cells = registrationCells(q, c);

        LocalDate d = q.getQuizDate();
        return new Cover(heading, programme, examLine, semesterLine, courseLine, timeInWords(totalMinutes),
                registrationLabel, cells, instructions,
                d == null ? null : String.valueOf(d.getDayOfMonth()), d == null ? null : ordinalSuffix(d.getDayOfMonth()),
                d == null ? null : d.format(DateTimeFormatter.ofPattern("MMMM yyyy")),
                q.getStartTime() == null ? null : q.getStartTime().format(DateTimeFormatter.ofPattern("h:mm a")).toUpperCase(),
                examiner);
    }

    /** Programme ids for the cover: the quiz's, else the course's, else those of students registered for the course. */
    private List<Long> paperProgrammes(Quiz q, Category c) {
        if (q.getPrograms() != null && !q.getPrograms().isEmpty())
            return q.getPrograms().stream().map(Program::getId).filter(Objects::nonNull).toList();
        if (c != null && c.getPrograms() != null && !c.getPrograms().isEmpty())
            return c.getPrograms().stream().map(Program::getId).filter(Objects::nonNull).toList();
        if (c == null) return List.of();
        Map<Long, Long> programmeOf = new HashMap<>();
        queries.students().forEach(s -> { if (s.programId() != null) programmeOf.put(s.id(), s.programId()); });
        return queries.registrations().stream().filter(x -> c.getCid().equals(x.courseId()))
                .map(x -> programmeOf.get(x.studentId())).filter(Objects::nonNull).distinct().toList();
    }

    /**
     * The boxes for the registration number, shaped like the institution's numbers: one box per
     * letter or digit, with separators ("/", "-") printed between them. Taken from the quiz's
     * index-number range, else from the commonest number shape among the course's students.
     */
    private List<String> registrationCells(Quiz q, Category c) {
        String sample = q.getIndexRangeStart();
        if (sample == null || sample.isBlank()) {
            Set<Long> programmes = new HashSet<>(paperProgrammes(q, c));
            sample = queries.students().stream()
                    .filter(s -> s.enabled() && s.username() != null && (programmes.isEmpty() || programmes.contains(s.programId())))
                    .map(s -> s.username().trim())
                    .collect(Collectors.groupingBy(QuestionPapers::shape, Collectors.counting()))
                    .entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        }
        List<String> cells = new ArrayList<>();
        if (sample == null || sample.isBlank() || sample.length() > 24) {
            for (int i = 0; i < 12; i++) cells.add("");
            return cells;
        }
        for (char ch : sample.toCharArray()) cells.add(Character.isLetterOrDigit(ch) ? "" : String.valueOf(ch));
        return cells;
    }

    /** "PS/ITC/21/0001" → "XX/XXX/99/9999": letters and digits masked, separators kept. */
    private static String shape(String s) {
        StringBuilder b = new StringBuilder();
        for (char ch : s.toCharArray()) b.append(Character.isLetter(ch) ? 'X' : Character.isDigit(ch) ? '9' : ch);
        return b.toString();
    }

    private Objective objective(int number, Questions qu, boolean answers) {
        QuestionType type = qu.getQuestionType() == null ? QuestionType.MCQ : qu.getQuestionType();
        Set<String> correct = qu.getcorrect_answer() == null ? Set.of()
                : Arrays.stream(qu.getcorrect_answer()).filter(Objects::nonNull).map(String::trim).collect(Collectors.toCollection(LinkedHashSet::new));
        List<Choice> choices = new ArrayList<>();
        List<Prompt> prompts = new ArrayList<>();
        List<Choice> matchAnswers = new ArrayList<>();
        String key;
        boolean typed = false;
        switch (type) {
            case MATCHING -> {
                List<MatchingPair> pairs = qu.getMatchingPairs() == null ? List.of() : qu.getMatchingPairs();
                // Answers listed alphabetically, so their order does not give the pairs away
                List<String> pool = pairs.stream().map(MatchingPair::getAnswer).filter(Objects::nonNull).distinct()
                        .sorted(String.CASE_INSENSITIVE_ORDER).toList();
                for (int i = 0; i < pool.size(); i++) matchAnswers.add(new Choice(letter(i), pool.get(i), false));
                for (int i = 0; i < pairs.size(); i++) {
                    MatchingPair p = pairs.get(i);
                    int at = pool.indexOf(p.getAnswer());
                    prompts.add(new Prompt(i < ROMAN.length ? ROMAN[i] : String.valueOf(i + 1), p.getPrompt(),
                            answers && at >= 0 ? letter(at) : null));
                }
                // "=" rather than an arrow: the PDF's built-in fonts have no arrow character
                key = prompts.stream().map(p -> p.label() + " = " + p.answerLetter()).collect(Collectors.joining(", "));
            }
            case FILL_BLANK, NUMERIC -> {
                typed = true;
                key = String.join(" / ", correct);
                if (type == QuestionType.NUMERIC && qu.getTolerance() != null && qu.getTolerance() > 0) key += " (± " + fmt(qu.getTolerance()) + ")";
            }
            default -> {
                List<String> options = Arrays.asList(qu.getOption1(), qu.getOption2(), qu.getOption3(), qu.getOption4());
                List<String> keys = new ArrayList<>();
                for (int i = 0; i < options.size(); i++) {
                    String o = options.get(i);
                    if (o == null || o.isBlank()) continue;
                    boolean right = correct.contains(o.trim());
                    choices.add(new Choice(letter(i), o, answers && right));
                    if (right) keys.add(letter(i));
                }
                key = keys.isEmpty() ? String.join(" / ", correct) : String.join(", ", keys);
            }
        }
        return new Objective(number, clean(qu.getContent()), TeachingReports.plainFull(qu.getContent()),
                questionImageService.loadForPdf(qu.getImage(), COLUMN_IMG_W_PT, COLUMN_IMG_H_PT), choices, prompts, matchAnswers, typed,
                answers ? key : null);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Question HTML from the editor, reduced to safe formatting for the printed paper. */
    static String clean(String html) {
        if (html == null || html.isBlank()) return "";
        return Jsoup.clean(html, Safelist.relaxed().removeTags("img"));
    }

    /** "1a" in question 1 → "a)"; "1(ii)" → "(ii)"; a question without parts → null. */
    static String partLabel(String no, String groupKey) {
        String compact = no.replaceAll("\\s+", "");
        if (compact.equalsIgnoreCase(groupKey)) return null;
        String rest = compact.length() > groupKey.length() && compact.toUpperCase().startsWith(groupKey)
                ? compact.substring(groupKey.length()) : compact;
        rest = rest.replaceAll("^[.\\-_]+", "");
        if (rest.isEmpty()) return null;
        return rest.endsWith(")") ? rest : rest.toLowerCase() + ")";
    }

    private static String letter(int i) { return i < LETTERS.length ? LETTERS[i] : String.valueOf(i + 1); }

    static int minutes(String quizTime) {
        if (quizTime == null || quizTime.isBlank()) return 0;
        try {
            if (quizTime.contains(":")) {
                String[] p = quizTime.split(":");
                return Integer.parseInt(p[0].trim()) * 60 + Integer.parseInt(p[1].trim());
            }
            return (int) Math.round(Double.parseDouble(quizTime.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static String duration(int objective, int theory, boolean hasObjective, boolean hasTheory) {
        int total = (hasObjective ? objective : 0) + (hasTheory ? theory : 0);
        String text = hm(total);
        if (hasObjective && hasTheory && objective > 0 && theory > 0) text += " (Section A " + hm(objective) + ", Section B " + hm(theory) + ")";
        return total == 0 ? "Not set" : text;
    }

    private static String hm(int m) {
        int h = m / 60, mm = m % 60;
        return h == 0 ? mm + " min" : mm == 0 ? h + " hr" : h + " hr " + mm + " min";
    }

    /** 120 → "Two (2) Hours"; 90 → "One (1) Hour Thirty (30) Minutes". */
    static String timeInWords(int minutes) {
        if (minutes <= 0) return "Not set";
        int h = minutes / 60, m = minutes % 60;
        String hours = h == 0 ? "" : capital(words(h)) + " (" + h + ") Hour" + (h == 1 ? "" : "s");
        String mins = m == 0 ? "" : capital(words(m)) + " (" + m + ") Minute" + (m == 1 ? "" : "s");
        return (hours + " " + mins).trim();
    }

    private static final String[] SMALL = {"zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
            "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen"};
    private static final String[] TENS = {"", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"};

    /** 2 → "two", 45 → "forty-five" (up to 99; larger numbers stay as digits). */
    static String words(int n) {
        if (n < 0 || n > 99) return String.valueOf(n);
        if (n < 20) return SMALL[n];
        return TENS[n / 10] + (n % 10 == 0 ? "" : "-" + SMALL[n % 10]);
    }

    private static String capital(String s) {
        return Arrays.stream(s.split("-")).map(w -> Character.toUpperCase(w.charAt(0)) + w.substring(1)).collect(Collectors.joining("-"));
    }

    static String ordinalSuffix(int n) {
        int mod100 = n % 100;
        if (mod100 >= 11 && mod100 <= 13) return "TH";
        return switch (n % 10) { case 1 -> "ST"; case 2 -> "ND"; case 3 -> "RD"; default -> "TH"; };
    }

    private static double parse(String s) {
        try { return s == null ? 0 : Double.parseDouble(s.trim()); } catch (NumberFormatException e) { return 0; }
    }

    static String fmt(double v) {
        return BigDecimal.valueOf(v).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private static int number(String s) {
        try { return Integer.parseInt(s.replaceAll("\\D", "")); } catch (Exception e) { return 0; }
    }

    /** "1a" < "1b" < "2" < "10". */
    private static int natural(String a, String b) {
        int na = number(a), nb = number(b);
        return na != nb ? Integer.compare(na, nb) : a.compareToIgnoreCase(b);
    }
}
