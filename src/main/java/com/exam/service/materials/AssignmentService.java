package com.exam.service.materials;

import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.exam.Category;
import com.exam.model.exam.LlmProvider;
import com.exam.model.exam.MarkSheetSection;
import com.exam.model.exam.SemesterSheet;
import com.exam.model.materials.AssignmentSubmission;
import com.exam.model.materials.CourseMaterial;
import com.exam.model.materials.StoredFile;
import com.exam.repository.materials.AssignmentSubmissionRepository;
import com.exam.repository.materials.CourseMaterialRepository;
import com.exam.repository.materials.CourseSpaceRepository;
import com.exam.repository.materials.StoredFileRepository;
import com.exam.service.MarksEntryService;
import com.exam.service.academic.AcademicSessionService;
import com.exam.service.comms.CurrentUserService;
import com.exam.service.comms.NotificationService;
import com.exam.service.examops.ExamAccess;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Assignments: students hand in typed work and/or files until submissions close (the due date,
 * unless the lecturer reopens them with a new closing date). Lecturers mark each one in a full-screen
 * viewer, with an AI suggestion on request, release the marks, and can send them to a Marks Sheet
 * column. The AI never saves a mark by itself.
 */
@Service
public class AssignmentService {

    private static final Logger log = LoggerFactory.getLogger(AssignmentService.class);
    public static final int MAX_SUBMISSION_FILES = 5;
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE d MMM, h:mm a", Locale.ENGLISH);

    @Autowired private CourseMaterialRepository materials;
    @Autowired private AssignmentSubmissionRepository submissions;
    @Autowired private StoredFileRepository files;
    @Autowired private CourseSpaceRepository spaces;
    @Autowired private MaterialService materialService;
    @Autowired private MaterialAccess access;
    @Autowired private MaterialStorage storage;
    @Autowired private AssignmentAiMarker ai;
    @Autowired private NotificationService notifications;
    @Autowired private MarksEntryService marksEntry;
    @Autowired private AcademicSessionService academicSessions;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired private OfficePreview officePreview;

    /** "AI-suggest all" runs here, one submission at a time, away from the shared scheduler thread. */
    private final ExecutorService aiWorker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "assignment-ai");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean draining = new AtomicBoolean(false);
    /** "Mark with AI" clicks: a few at once, so a lecturer isn't stuck behind a class-wide run. */
    private final ExecutorService interactive = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "assignment-ai-now");
        t.setDaemon(true);
        return t;
    });
    @Autowired private AiProgress progress;
    @Autowired private SubmissionText texts;

    // ── Similar work ────────────────────────────────────────────────────────

    private record Integrity(String version, Map<Long, List<SimilarityCheck.Match>> matches, Map<Long, List<String>> chatbot,
                             Map<Long, String> textByStudent, String questionText) {}
    private final Map<Long, Integrity> integrityCache = new java.util.concurrent.ConcurrentHashMap<>();

    /** Similarity between classmates' work and chatbot phrases, worked out again only when hand-ins change. */
    Integrity integrity(CourseMaterial m, Collection<AssignmentSubmission> subs) {
        List<AssignmentSubmission> handed = subs.stream().filter(s -> s.getSubmittedAt() != null).toList();
        List<StoredFile> paper = files.findByMaterial_IdOrderByCreatedAt(m.getId());
        Map<Long, List<StoredFile>> bySub = handed.isEmpty() ? Map.of() : files.forSubmissions(handed.stream().map(AssignmentSubmission::getId).toList())
                .stream().filter(f -> f.getRole() == StoredFile.Role.SUBMISSION).collect(Collectors.groupingBy(f -> f.getSubmission().getId()));
        StringBuilder v = new StringBuilder(String.valueOf(Objects.hashCode(m.getDescription())));
        paper.forEach(f -> v.append('|').append(f.getSha256()));
        for (AssignmentSubmission s : handed) {
            v.append(';').append(s.getId()).append('@').append(s.getUpdatedAt());
            bySub.getOrDefault(s.getId(), List.of()).forEach(f -> v.append(',').append(f.getSha256()));
        }
        String version = v.toString();
        Integrity cached = integrityCache.get(m.getId());
        if (cached != null && cached.version().equals(version)) return cached;

        StringBuilder q = new StringBuilder(m.getDescription() == null ? "" : m.getDescription());
        for (StoredFile f : paper) q.append('\n').append(texts.of(f));
        Map<Long, String> byStudent = new HashMap<>();
        List<SimilarityCheck.Text> list = new ArrayList<>();
        Map<Long, List<String>> chatbot = new HashMap<>();
        for (AssignmentSubmission s : handed) {
            StringBuilder t = new StringBuilder(s.getTypedAnswer() == null ? "" : s.getTypedAnswer());
            for (StoredFile f : bySub.getOrDefault(s.getId(), List.of())) t.append('\n').append(texts.of(f));
            Long sid = s.getStudent().getId();
            byStudent.put(sid, t.toString());
            list.add(new SimilarityCheck.Text(sid, CurrentUserService.displayName(s.getStudent()), t.toString()));
            List<String> found = SimilarityCheck.chatbotPhrases(t.toString());
            if (!found.isEmpty()) chatbot.put(sid, found);
        }
        Integrity fresh = new Integrity(version, SimilarityCheck.compare(list, q.toString()), chatbot, byStudent, q.toString());
        integrityCache.put(m.getId(), fresh);
        if (integrityCache.size() > 200) integrityCache.clear();
        return fresh;
    }
    /** Where "Mark with AI" runs; tests run it on the calling thread so it sees their uncommitted data. */
    private java.util.concurrent.Executor runNow = interactive;

    void runAiOnCallingThreadForTests() { runNow = Runnable::run; }

    @PreDestroy
    void stop() { aiWorker.shutdownNow(); interactive.shutdownNow(); }

    public record MarkRequest(BigDecimal score, String feedback, Boolean aiAssisted) {}
    public record ReopenRequest(LocalDateTime closesAt) {}
    public record SheetRequest(Long sheetId, Long sectionId, Boolean scale) {}

    // ══ Students ═════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public Map<String, Object> studentView(User u, Long id) {
        CourseMaterial m = assignment(id);
        materialService.requireStudentSees(u, m);
        AssignmentSubmission sub = submissions.findByAssignment_IdAndStudent_Id(m.getId(), u.getId()).orElse(null);
        return studentDetail(m, sub);
    }

    private Map<String, Object> studentDetail(CourseMaterial m, AssignmentSubmission sub) {
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> out = MaterialService.map("id", m.getId(), "title", m.getTitle(), "description", m.getDescription(),
                "weekNumber", m.getWeekNumber(), "course", MaterialService.courseInfo(m.getSpace().getCourse()),
                "files", files.findByMaterial_IdOrderByCreatedAt(m.getId()).stream().map(MaterialService::fileInfo).toList(),
                "assignment", materialService.studentAssignment(m, sub, now),
                "maxFiles", MAX_SUBMISSION_FILES, "maxFileMb", storage.maxFileMb(),
                "accepted", new TreeSet<>(MaterialStorage.SUBMISSION_TYPES));
        Map<String, Object> mine = null;
        if (sub != null) {
            List<StoredFile> fs = files.findBySubmission_IdOrderByCreatedAt(sub.getId());
            mine = MaterialService.map("typedAnswer", sub.getTypedAnswer(), "submittedAt", sub.getSubmittedAt(), "late", sub.isLate(),
                    "files", fs.stream().filter(f -> f.getRole() == StoredFile.Role.SUBMISSION).map(MaterialService::fileInfo).toList(),
                    "locked", sub.getScore() != null);
            if (m.isMarksReleased() && sub.getScore() != null) {
                mine.put("score", sub.getScore());
                mine.put("feedback", sub.getFeedback());
                mine.put("feedbackFiles", fs.stream().filter(f -> f.getRole() == StoredFile.Role.FEEDBACK).map(MaterialService::fileInfo).toList());
            }
        }
        out.put("submission", mine);
        return out;
    }

    /** Hands in (or replaces) the student's work while submissions are open. keep = ids of earlier files to keep. */
    @Transactional
    public Map<String, Object> submit(User u, Long id, String typedAnswer, List<String> keep, List<MultipartFile> uploads) {
        if (u.getRole() != Role.NORMAL) throw new AccessDeniedException("Only students hand in assignments.");
        CourseMaterial m = assignment(id);
        materialService.requireStudentSees(u, m);
        LocalDateTime now = LocalDateTime.now();
        if (!MaterialService.isOpen(m, now))
            throw new IllegalArgumentException("Submissions closed on " + m.getClosesAt().format(WHEN) + ". Ask your lecturer if you need more time.");
        AssignmentSubmission sub = submissions.findByAssignment_IdAndStudent_Id(m.getId(), u.getId()).orElse(null);
        if (sub != null && sub.getScore() != null)
            throw new IllegalArgumentException("Your work has been marked, so it can't be changed.");

        String typed = typedAnswer == null ? null : typedAnswer.strip();
        if (typed != null && typed.isEmpty()) typed = null;
        if (typed != null && !m.isAllowTyped()) throw new IllegalArgumentException("This assignment takes files only.");
        if (typed != null && typed.length() > 100_000) throw new IllegalArgumentException("Your answer is too long (100,000 characters at most).");
        List<MultipartFile> newFiles = uploads == null ? List.of() : uploads.stream().filter(f -> f != null && !f.isEmpty()).toList();
        if (!newFiles.isEmpty() && !m.isAllowFiles()) throw new IllegalArgumentException("This assignment takes a typed answer only.");

        List<StoredFile> existing = sub == null ? List.of() : files.findBySubmission_IdOrderByCreatedAt(sub.getId()).stream()
                .filter(f -> f.getRole() == StoredFile.Role.SUBMISSION).toList();
        Set<String> keepIds = keep == null ? Set.of() : new HashSet<>(keep);
        List<StoredFile> kept = existing.stream().filter(f -> keepIds.contains(f.getId())).toList();
        if (kept.size() + newFiles.size() > MAX_SUBMISSION_FILES)
            throw new IllegalArgumentException("Hand in at most " + MAX_SUBMISSION_FILES + " files.");
        if (typed == null && kept.isEmpty() && newFiles.isEmpty())
            throw new IllegalArgumentException(m.isAllowFiles() && m.isAllowTyped() ? "Type your answer or attach your work first."
                    : m.isAllowFiles() ? "Attach your work first." : "Type your answer first.");

        // Store the new files before touching the old ones, so a refused file changes nothing
        List<StoredFile> stored = new ArrayList<>();
        for (MultipartFile up : newFiles) stored.add(storage.store(up, MaterialStorage.SUBMISSION_TYPES, StoredFile.Role.SUBMISSION, u.getId()));

        if (sub == null) {
            sub = new AssignmentSubmission();
            sub.setAssignment(m);
            sub.setStudent(u);
        }
        sub.setTypedAnswer(typed);
        sub.setSubmittedAt(now);
        sub.setUpdatedAt(now);
        sub.setLate(m.getDueAt() != null && now.isAfter(m.getDueAt()));
        sub.setAiScore(null);
        sub.setAiFeedback(null);
        sub.setAiError(null);
        sub.setAiAt(null);
        sub.setAiWriting(null);
        sub.setAiWritingNotes(null);
        submissions.save(sub);

        Set<String> removedShas = new HashSet<>();
        for (StoredFile f : existing) if (!keepIds.contains(f.getId())) { removedShas.add(f.getSha256()); files.delete(f); }
        for (StoredFile f : stored) { f.setSubmission(sub); files.save(f); officePreview.warm(f); }
        List<StoredFile> forText = List.copyOf(stored);
        if (!forText.isEmpty()) try { interactive.submit(() -> forText.forEach(texts::of)); } catch (Exception ignored) { }
        files.flush();
        removedShas.forEach(storage::deleteIfUnused);
        materialService.recordView(u, m.getId());
        return studentDetail(m, sub);
    }

    // ══ Staff: the class's work ══════════════════════════════════════════════

    @Transactional(readOnly = true)
    public Map<String, Object> roster(User u, Long id) {
        CourseMaterial m = managed(u, id);
        List<User> students = materialService.roster(m.getSpace());
        Map<Long, AssignmentSubmission> subs = submissions.findByAssignment_Id(m.getId()).stream()
                .collect(Collectors.toMap(s -> s.getStudent().getId(), s -> s, (a, b) -> a));
        // Students no longer on the class list who handed in still show
        Map<Long, User> people = new LinkedHashMap<>();
        students.forEach(s -> people.put(s.getId(), s));
        subs.values().forEach(s -> people.putIfAbsent(s.getStudent().getId(), s.getStudent()));

        List<StoredFile> subFiles = subs.isEmpty() ? List.of() : files.forSubmissions(subs.values().stream().map(AssignmentSubmission::getId).toList());
        Map<Long, List<StoredFile>> bySub = subFiles.stream().filter(f -> f.getRole() == StoredFile.Role.SUBMISSION)
                .collect(Collectors.groupingBy(f -> f.getSubmission().getId()));
        // The same file handed in by more than one student
        Map<String, Set<Long>> shaOwners = new HashMap<>();
        for (StoredFile f : subFiles) if (f.getRole() == StoredFile.Role.SUBMISSION)
            shaOwners.computeIfAbsent(f.getSha256(), k -> new HashSet<>()).add(f.getSubmission().getStudent().getId());

        Integrity check = integrity(m, subs.values());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (User st : people.values()) {
            AssignmentSubmission s = subs.get(st.getId());
            boolean handedIn = s != null && s.getSubmittedAt() != null;
            List<StoredFile> mine = s == null ? List.of() : bySub.getOrDefault(s.getId(), List.of());
            Set<Long> twins = new TreeSet<>();
            for (StoredFile f : mine) for (Long other : shaOwners.getOrDefault(f.getSha256(), Set.of())) if (!other.equals(st.getId())) twins.add(other);
            rows.add(MaterialService.map("studentId", st.getId(), "name", CurrentUserService.displayName(st), "username", st.getUsername(),
                    "status", s != null && s.getScore() != null ? "MARKED" : handedIn ? (s.isLate() ? "LATE" : "HANDED_IN") : "MISSING",
                    "submittedAt", handedIn ? s.getSubmittedAt() : null, "late", handedIn && s.isLate(),
                    "score", s == null ? null : s.getScore(), "aiScore", s == null ? null : s.getAiScore(),
                    "aiQueued", s != null && s.isAiQueued(), "aiError", s == null ? null : s.getAiError(),
                    "aiStage", s == null || progress.view(s.getId()) == null ? null : progress.view(s.getId()).get("stage"),
                    "files", mine.size(), "typed", s != null && s.getTypedAnswer() != null,
                    "sameFileAs", twins.stream().map(people::get).filter(Objects::nonNull).map(CurrentUserService::displayName).toList(),
                    "similarTo", topMatch(check, st.getId()),
                    "chatbotPhrases", check.chatbot().getOrDefault(st.getId(), List.of()).size(),
                    "aiWriting", s == null ? null : s.getAiWriting()));
        }
        rows.sort(Comparator.comparing(r -> String.valueOf(r.get("name")), String.CASE_INSENSITIVE_ORDER));
        long handed = rows.stream().filter(r -> r.get("submittedAt") != null).count();
        long marked = rows.stream().filter(r -> r.get("score") != null).count();
        return MaterialService.map("assignment", assignmentInfo(m), "students", rows,
                "counts", MaterialService.map("students", students.size(), "handedIn", handed, "marked", marked,
                        "late", rows.stream().filter(r -> Boolean.TRUE.equals(r.get("late"))).count(),
                        "aiQueued", rows.stream().filter(r -> Boolean.TRUE.equals(r.get("aiQueued"))).count(),
                        "aiRunning", rows.stream().filter(r -> r.get("aiStage") != null).count(),
                        "similar", rows.stream().filter(r -> r.get("similarTo") != null).count()),
                "aiProviders", ai.providers());
    }

    /** One student's work for the marking viewer (also for students who handed in nothing). */
    @Transactional(readOnly = true)
    public Map<String, Object> work(User u, Long id, Long studentId) {
        CourseMaterial m = managed(u, id);
        AssignmentSubmission s = submissions.findByAssignment_IdAndStudent_Id(m.getId(), studentId).orElse(null);
        User student = s != null ? s.getStudent() : materialService.roster(m.getSpace()).stream()
                .filter(x -> x.getId().equals(studentId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("That student isn't on this course's class list."));
        List<StoredFile> fs = s == null ? List.of() : files.findBySubmission_IdOrderByCreatedAt(s.getId());
        Map<String, Object> out = MaterialService.map("assignment", assignmentInfo(m),
                "student", MaterialService.map("id", student.getId(), "name", CurrentUserService.displayName(student),
                        "username", student.getUsername(), "photo", student.getProfilePic()),
                "submittedAt", s == null ? null : s.getSubmittedAt(), "late", s != null && s.isLate(),
                "typedAnswer", s == null ? null : s.getTypedAnswer(),
                "files", fs.stream().filter(f -> f.getRole() == StoredFile.Role.SUBMISSION).map(MaterialService::fileInfo).toList(),
                "feedbackFiles", fs.stream().filter(f -> f.getRole() == StoredFile.Role.FEEDBACK).map(MaterialService::fileInfo).toList(),
                "score", s == null ? null : s.getScore(), "feedback", s == null ? null : s.getFeedback(),
                "markedBy", s == null ? null : s.getMarkedByName(), "markedAt", s == null ? null : s.getMarkedAt(),
                "aiAssisted", s != null && s.isAiAssisted(),
                "aiScore", s == null ? null : s.getAiScore(), "aiFeedback", s == null ? null : s.getAiFeedback(),
                "aiProvider", s == null || s.getAiProvider() == null ? null : s.getAiProvider().name(),
                "aiAt", s == null ? null : s.getAiAt(), "aiError", s == null ? null : s.getAiError(),
                "aiQueued", s != null && s.isAiQueued(),
                "aiProgress", s == null ? null : progress.view(s.getId()),
                "aiWriting", s == null ? null : s.getAiWriting(), "aiWritingNotes", s == null ? null : s.getAiWritingNotes());
        if (s != null && s.getSubmittedAt() != null) {
            Integrity check = integrity(m, submissions.findByAssignment_Id(m.getId()));
            String mine = check.textByStudent().getOrDefault(studentId, "");
            out.put("similar", check.matches().getOrDefault(studentId, List.of()).stream().limit(3).map(x -> MaterialService.map(
                    "studentId", x.studentId(), "name", x.name(), "percent", x.percent(), "flagged", x.percent() >= SimilarityCheck.FLAG_PERCENT,
                    "passages", SimilarityCheck.sharedPassages(mine, check.textByStudent().getOrDefault(x.studentId(), ""), check.questionText(), 3)
                            .stream().map(SimilarityCheck.Passage::text).toList())).toList());
            out.put("chatbotPhrases", check.chatbot().getOrDefault(studentId, List.of()));
            out.put("hasText", SimilarityCheck.words(mine).size() >= SimilarityCheck.SHINGLE * 8);
        }
        return out;
    }

    /** Saves (or clears, with score null) the lecturer's mark. */
    @Transactional
    public Map<String, Object> mark(User u, Long id, Long studentId, MarkRequest r) {
        CourseMaterial m = managed(u, id);
        AssignmentSubmission s = submissions.findByAssignment_IdAndStudent_Id(m.getId(), studentId).orElse(null);
        if (s == null) {
            User student = materialService.roster(m.getSpace()).stream().filter(x -> x.getId().equals(studentId)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("That student isn't on this course's class list."));
            if (r.score() == null) return work(u, id, studentId);
            s = new AssignmentSubmission();       // marked on paper: nothing handed in online
            s.setAssignment(m);
            s.setStudent(student);
        }
        if (r.score() != null) {
            if (r.score().signum() < 0 || r.score().compareTo(m.getMaxMarks()) > 0)
                throw new IllegalArgumentException("The mark must be between 0 and " + plain(m.getMaxMarks()) + ".");
            s.setScore(r.score().setScale(2, RoundingMode.HALF_UP));
            s.setMarkedById(u.getId());
            s.setMarkedByName(CurrentUserService.displayName(u));
            s.setMarkedAt(LocalDateTime.now());
            s.setAiAssisted(Boolean.TRUE.equals(r.aiAssisted()));
        } else {
            s.setScore(null);
            s.setMarkedById(null);
            s.setMarkedByName(null);
            s.setMarkedAt(null);
            s.setAiAssisted(false);
        }
        String fb = r.feedback() == null ? null : r.feedback().strip();
        if (fb != null && fb.length() > 20_000) throw new IllegalArgumentException("Keep the feedback under 20,000 characters.");
        s.setFeedback(fb == null || fb.isEmpty() ? null : fb);
        s.setAiQueued(false);
        submissions.save(s);
        if (r.score() != null && m.isMarksReleased())
            notifications.notify(s.getStudent(), "ASSIGNMENT", "Assignment marked",
                    "Your mark for \"" + m.getTitle() + "\" is ready.", studentLink(m));
        return work(u, id, studentId);
    }

    /**
     * Starts the AI on one student's work and returns at once; the marking screen follows its progress
     * (aiProgress in work()) and finds the suggestion on the submission when it is done. The lecturer
     * then saves or edits it. Runs in the background, so a slow AI never holds the request open.
     */
    public Map<String, Object> aiSuggest(User u, Long id, Long studentId, String provider) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        Job job = tx.execute(st -> prepare(u, id, studentId, provider));
        if (!progress.running(job.submissionId())) {
            progress.start(job.submissionId(), AssignmentAiMarker.label(job.provider()));
            try { runNow.execute(() -> runJob(job.submissionId(), job.provider())); }
            catch (java.util.concurrent.RejectedExecutionException e) { progress.finish(job.submissionId()); throw new IllegalStateException("The server is busy. Try again in a moment."); }
        }
        return tx.execute(st -> work(u, id, studentId));
    }

    /** Queues every handed-in, unmarked submission without a suggestion; the worker suggests one at a time. */
    @Transactional
    public Map<String, Object> aiSuggestAll(User u, Long id, String provider) {
        CourseMaterial m = managed(u, id);
        try {
            if (provider != null && !provider.isBlank()) {
                LlmProvider p = MaterialService.parse(LlmProvider.class, provider, "AI");
                ai.pick(p);   // refuses when nothing is set up
                m.setAiProvider(p);
                materials.save(m);
            } else {
                ai.pick(m.getAiProvider());
            }
        } catch (AssignmentAiMarker.AiUnavailable e) {
            throw new IllegalArgumentException(e.getMessage());
        }
        int n = 0;
        for (AssignmentSubmission s : submissions.findByAssignment_Id(m.getId())) {
            if (s.getSubmittedAt() == null || s.getScore() != null || s.getAiScore() != null || progress.running(s.getId())) continue;
            s.setAiQueued(true);
            s.setAiError(null);
            submissions.save(s);
            n++;
        }
        if (n > 0) org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void afterCommit() { kickWorker(); }
                });
        Map<String, Object> out = roster(u, id);
        out.put("queued", n);
        return out;
    }

    @Transactional
    public Map<String, Object> cancelAiQueue(User u, Long id) {
        CourseMaterial m = managed(u, id);
        for (AssignmentSubmission s : submissions.findByAssignment_Id(m.getId()))
            if (s.isAiQueued()) { s.setAiQueued(false); submissions.save(s); }
        return roster(u, id);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void resumeQueue() {
        try { if (!submissions.queuedForAi().isEmpty()) kickWorker(); }
        catch (Exception e) { log.warn("[Assignments] Could not resume the AI queue: {}", e.getMessage()); }
    }

    private void kickWorker() {
        if (!draining.compareAndSet(false, true)) return;
        aiWorker.submit(() -> {
            try {
                TransactionTemplate tx = new TransactionTemplate(txManager);
                for (int guard = 0; guard < 5000; guard++) {
                    List<Long> ids = submissions.queuedForAi();
                    if (ids.isEmpty()) break;
                    Long sid = ids.get(0);
                    try {
                        LlmProvider wanted = tx.execute(st -> queuedProvider(sid));
                        if (wanted != null && !progress.running(sid)) {
                            progress.start(sid, AssignmentAiMarker.label(ai.pick(wanted)));
                            runJob(sid, wanted);
                        }
                    } catch (Exception e) {
                        log.warn("[Assignments] AI suggestion failed for submission {}: {}", sid, e.getMessage());
                    } finally {
                        tx.executeWithoutResult(st -> submissions.findById(sid).ifPresent(s -> { s.setAiQueued(false); submissions.save(s); }));
                    }
                }
            } finally {
                draining.set(false);
                if (!submissions.queuedForAi().isEmpty()) kickWorker();
            }
        });
    }

    private record Job(Long submissionId, LlmProvider provider) {}

    private Job prepare(User u, Long id, Long studentId, String provider) {
        CourseMaterial m = managed(u, id);
        AssignmentSubmission s = submissions.findByAssignment_IdAndStudent_Id(m.getId(), studentId)
                .filter(x -> x.getSubmittedAt() != null)
                .orElseThrow(() -> new IllegalArgumentException("This student hasn't handed in anything online to mark."));
        LlmProvider wanted = provider == null || provider.isBlank() ? m.getAiProvider() : MaterialService.parse(LlmProvider.class, provider, "AI");
        LlmProvider chosen;
        try { chosen = ai.pick(wanted); }
        catch (AssignmentAiMarker.AiUnavailable e) { throw new IllegalArgumentException(e.getMessage()); }
        if (!progress.running(s.getId())) { s.setAiError(null); submissions.save(s); }
        return new Job(s.getId(), chosen);
    }

    /** The AI for a queued submission, or null when it no longer needs one. */
    private LlmProvider queuedProvider(Long sid) {
        AssignmentSubmission s = submissions.findById(sid).orElse(null);
        if (s == null || !s.isAiQueued() || s.getScore() != null || s.getSubmittedAt() == null) return null;
        CourseMaterial m = s.getAssignment();
        if (m.getDeletedAt() != null || !access.onForCourse(m.getSpace().getCourse())) return null;
        return m.getAiProvider();
    }

    private record Loaded(String course, String title, String instructions, java.math.BigDecimal maxMarks, String guide,
                          String typed, List<StoredFile> files, List<StoredFile> questions) {}

    private Loaded load(Long sid) {
        AssignmentSubmission s = submissions.findById(sid).orElseThrow(() -> new IllegalArgumentException("Submission not found."));
        CourseMaterial m = s.getAssignment();
        Category c = m.getSpace().getCourse();
        String course = (c.getCourseCode() == null ? "" : c.getCourseCode() + " ") + (c.getTitle() == null ? "" : c.getTitle())
                + (c.getLevel() == null ? "" : " (level " + c.getLevel() + ")");
        List<StoredFile> fs = files.findBySubmission_IdOrderByCreatedAt(sid).stream().filter(f -> f.getRole() == StoredFile.Role.SUBMISSION).toList();
        // The lecturer's own files on the assignment are its question paper
        List<StoredFile> qs = files.findByMaterial_IdOrderByCreatedAt(m.getId());
        return new Loaded(course.trim(), m.getTitle(), m.getDescription(), m.getMaxMarks(), m.getMarkingGuide(), s.getTypedAnswer(), fs, qs);
    }

    /**
     * One file for the AI. A Word / PowerPoint file goes as its exact PDF when the server can make one, so the
     * AI also sees pictures and diagrams; the progress shows the conversion.
     */
    private AssignmentAiMarker.WorkFile readForAi(Long sid, StoredFile f, boolean question) {
        java.nio.file.Path pdf = officePreview.ready(f).orElse(null);
        if (pdf == null && officePreview.offered(f)) {
            progress.stage(sid, AiProgress.Stage.CONVERTING, (question ? "the question paper \u201c" : "\u201c") + f.getOriginalName() + "\u201d");
            try { pdf = officePreview.pdf(f); } catch (Exception e) { pdf = null; }
            progress.stage(sid, AiProgress.Stage.PREPARING, null);
        }
        try {
            if (pdf != null)
                return new AssignmentAiMarker.WorkFile(f.getOriginalName() + ".pdf", "application/pdf", java.nio.file.Files.readAllBytes(pdf));
            return new AssignmentAiMarker.WorkFile(f.getOriginalName(), f.getContentType(), storage.read(f));
        } catch (Exception e) {
            throw new AssignmentAiMarker.AiUnavailable("\"" + f.getOriginalName() + "\" is missing from the server's storage.");
        }
    }

    /** Runs outside any transaction (the AI can take minutes); reports each step; always clears its progress. */
    private void runJob(Long sid, LlmProvider wanted) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        try {
            progress.stage(sid, AiProgress.Stage.PREPARING, null);
            Loaded l = tx.execute(st -> load(sid));
            List<AssignmentAiMarker.WorkFile> work = new ArrayList<>();
            for (StoredFile f : l.files()) work.add(readForAi(sid, f, false));
            List<AssignmentAiMarker.WorkFile> questions = new ArrayList<>();
            for (StoredFile f : l.questions()) questions.add(readForAi(sid, f, true));
            AssignmentAiMarker.Task task = new AssignmentAiMarker.Task(l.course(), l.title(), l.instructions(), l.maxMarks(), l.guide(), l.typed(), work, questions);
            AssignmentAiMarker.Result r = ai.mark(task, wanted, sent -> progress.stage(sid, AiProgress.Stage.READING, sent));
            progress.stage(sid, AiProgress.Stage.SAVING, null);
            tx.executeWithoutResult(st -> submissions.findById(sid).ifPresent(s -> {
                s.setAiScore(r.score());
                s.setAiFeedback(r.feedback());
                s.setAiProvider(r.provider());
                s.setAiAt(LocalDateTime.now());
                s.setAiError(null);
                s.setAiWriting(r.aiWriting());
                s.setAiWritingNotes(r.aiWritingNotes());
                submissions.save(s);
            }));
        } catch (Exception e) {
            String msg = e instanceof AssignmentAiMarker.AiUnavailable || e instanceof IllegalArgumentException
                    ? e.getMessage() : "The AI couldn't mark this work. Try again, or mark it yourself.";
            if (!(e instanceof AssignmentAiMarker.AiUnavailable)) log.warn("[Assignments] AI marking failed for submission {}", sid, e);
            String stored = msg == null ? "The AI couldn't mark this work." : msg.length() > 480 ? msg.substring(0, 480) : msg;
            try {
                tx.executeWithoutResult(st -> submissions.findById(sid).ifPresent(s -> { s.setAiError(stored); submissions.save(s); }));
            } catch (Exception ignored) { }
        } finally {
            progress.finish(sid);
        }
    }

    // ══ Staff: closing, reopening, releasing ═════════════════════════════════

    @Transactional
    public Map<String, Object> reopen(User u, Long id, ReopenRequest r) {
        CourseMaterial m = managed(u, id);
        if (r == null || r.closesAt() == null || !r.closesAt().isAfter(LocalDateTime.now()))
            throw new IllegalArgumentException("Choose a new closing date in the future.");
        m.setClosesAt(r.closesAt());
        m.setClosedNotified(false);
        m.setUpdatedAt(LocalDateTime.now());
        materials.save(m);
        Set<Long> handed = submissions.findByAssignment_Id(m.getId()).stream().filter(s -> s.getSubmittedAt() != null)
                .map(s -> s.getStudent().getId()).collect(Collectors.toSet());
        List<User> waiting = materialService.roster(m.getSpace()).stream().filter(s -> !handed.contains(s.getId())).toList();
        if (MaterialService.studentSees(m.getSpace(), null, m, LocalDateTime.now()))
            notifications.notify(waiting, "ASSIGNMENT", "Submissions reopened",
                    "\"" + m.getTitle() + "\" takes submissions again until " + r.closesAt().format(WHEN) + ".", studentLink(m));
        return assignmentInfo(m);
    }

    @Transactional
    public Map<String, Object> closeNow(User u, Long id) {
        CourseMaterial m = managed(u, id);
        m.setClosesAt(LocalDateTime.now());
        m.setUpdatedAt(LocalDateTime.now());
        materials.save(m);
        return assignmentInfo(m);
    }

    @Transactional
    public Map<String, Object> releaseMarks(User u, Long id, boolean released) {
        CourseMaterial m = managed(u, id);
        boolean was = m.isMarksReleased();
        m.setMarksReleased(released);
        materials.save(m);
        if (released && !was) {
            List<User> marked = submissions.findByAssignment_Id(m.getId()).stream().filter(s -> s.getScore() != null)
                    .map(AssignmentSubmission::getStudent).toList();
            notifications.notify(marked, "ASSIGNMENT", "Assignment marked", "Your mark for \"" + m.getTitle() + "\" is ready.", studentLink(m));
        }
        return assignmentInfo(m);
    }

    @Transactional
    public Map<String, Object> addFeedbackFiles(User u, Long id, Long studentId, List<MultipartFile> uploads) {
        CourseMaterial m = managed(u, id);
        AssignmentSubmission s = submissions.findByAssignment_IdAndStudent_Id(m.getId(), studentId)
                .orElseThrow(() -> new IllegalArgumentException("Save a mark for this student first."));
        List<MultipartFile> real = uploads == null ? List.of() : uploads.stream().filter(f -> f != null && !f.isEmpty()).toList();
        if (real.isEmpty()) throw new IllegalArgumentException("Choose the marked file.");
        if (real.size() > MAX_SUBMISSION_FILES) throw new IllegalArgumentException("Upload at most " + MAX_SUBMISSION_FILES + " files.");
        for (MultipartFile up : real) {
            StoredFile f = storage.store(up, MaterialStorage.SUBMISSION_TYPES, StoredFile.Role.FEEDBACK, u.getId());
            f.setSubmission(s);
            files.save(f);
        }
        return work(u, id, studentId);
    }

    @Transactional
    public Map<String, Object> removeFeedbackFile(User u, Long id, Long studentId, String fileId) {
        CourseMaterial m = managed(u, id);
        StoredFile f = files.findById(fileId).filter(x -> x.getRole() == StoredFile.Role.FEEDBACK && x.getSubmission() != null
                        && x.getSubmission().getAssignment().getId().equals(m.getId()) && x.getSubmission().getStudent().getId().equals(studentId))
                .orElseThrow(() -> new IllegalArgumentException("File not found."));
        String sha = f.getSha256();
        files.delete(f);
        files.flush();
        storage.deleteIfUnused(sha);
        return work(u, id, studentId);
    }

    // ══ Staff: Marks Sheet ═══════════════════════════════════════════════════

    /** Marks sheets this course is on (this session), with their columns; drafts only can take marks. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> sheets(User u, Long id) {
        return sheetTargets(managed(u, id).getSpace().getCourse());
    }

    /** Marks sheets that include the course this session, with their columns (shared with the course's marks table). */
    List<Map<String, Object>> sheetTargets(Category c) {
        return spaces.sheetsWithCourse(c.getCid(), academicSessions.current().getId()).stream().map(sh -> {
            boolean locked = "SUBMITTED".equals(sh.getStatus()) || "PUBLISHED".equals(sh.getStatus());
            return MaterialService.map("sheetId", sh.getId(),
                    "label", (sh.getProgram() == null ? "" : sh.getProgram().getName()) + (sh.getLevel() == null ? "" : " · " + sh.getLevel())
                            + (sh.getSemester() == null ? "" : " · semester " + sh.getSemester()),
                    "status", sh.getStatus(), "locked", locked,
                    "sections", sh.getSections().stream().map(sec -> MaterialService.map("id", sec.getId(), "name", sec.getSectionName(),
                            "maxScore", sec.getMaxScore())).toList());
        }).toList();
    }

    @Transactional
    public Map<String, Object> sendToSheet(User u, Long id, SheetRequest r) {
        CourseMaterial m = managed(u, id);
        if (r == null || r.sheetId() == null || r.sectionId() == null) throw new IllegalArgumentException("Choose the marks sheet and its column.");
        Category c = m.getSpace().getCourse();
        SemesterSheet sheet = spaces.sheetsWithCourse(c.getCid(), academicSessions.current().getId()).stream()
                .filter(s -> s.getId().equals(r.sheetId())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("That marks sheet doesn't include this course."));
        MarkSheetSection section = sheet.getSections().stream().filter(s -> s.getId().equals(r.sectionId())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("That column isn't on this marks sheet."));
        boolean scale = r.scale() == null || r.scale();
        Map<Long, BigDecimal> scores = new HashMap<>();
        for (AssignmentSubmission s : submissions.findByAssignment_Id(m.getId())) {
            if (s.getScore() == null) continue;
            BigDecimal v = scale
                    ? s.getScore().multiply(section.getMaxScore()).divide(m.getMaxMarks(), 2, RoundingMode.HALF_UP)
                    : s.getScore();
            scores.put(s.getStudent().getId(), v);
        }
        if (scores.isEmpty()) throw new IllegalArgumentException("No marks to send yet.");
        int n = marksEntry.applyExternalMarks(sheet.getId(), section.getId(), c.getCid(), scores);
        return MaterialService.map("updated", n, "marked", scores.size(), "column", section.getSectionName(),
                "message", n == 0 ? "None of the marked students are on that marks sheet yet."
                        : n + " student" + (n == 1 ? "'s" : "s'") + " marks went into \"" + section.getSectionName() + "\".");
    }

    // ══ Helpers ══════════════════════════════════════════════════════════════

    private static Map<String, Object> topMatch(Integrity check, Long studentId) {
        List<SimilarityCheck.Match> ms = check.matches().getOrDefault(studentId, List.of());
        if (ms.isEmpty() || ms.get(0).percent() < SimilarityCheck.FLAG_PERCENT) return null;
        return MaterialService.map("studentId", ms.get(0).studentId(), "name", ms.get(0).name(), "percent", ms.get(0).percent());
    }

    Map<String, Object> assignmentInfo(CourseMaterial m) {
        LocalDateTime now = LocalDateTime.now();
        return MaterialService.map("id", m.getId(), "title", m.getTitle(), "description", m.getDescription(),
                "weekNumber", m.getWeekNumber(), "course", MaterialService.courseInfo(m.getSpace().getCourse()),
                "dueAt", m.getDueAt(), "closesAt", m.getClosesAt(), "open", MaterialService.isOpen(m, now),
                "reopened", m.getClosesAt() != null && m.getDueAt() != null && m.getClosesAt().isAfter(m.getDueAt()),
                "maxMarks", m.getMaxMarks(), "markingGuide", m.getMarkingGuide(),
                "aiProvider", m.getAiProvider() == null ? null : m.getAiProvider().name(),
                "allowTyped", m.isAllowTyped(), "allowFiles", m.isAllowFiles(), "marksReleased", m.isMarksReleased(),
                "visibility", m.getVisibility().name(),
                "files", files.findByMaterial_IdOrderByCreatedAt(m.getId()).stream().map(MaterialService::fileInfo).toList());
    }

    private CourseMaterial assignment(Long id) {
        CourseMaterial m = materials.findById(id).filter(x -> x.getDeletedAt() == null && x.isAssignment())
                .orElseThrow(() -> new IllegalArgumentException("Assignment not found."));
        return m;
    }

    private CourseMaterial managed(User u, Long id) {
        CourseMaterial m = assignment(id);
        access.requireOnFor(u, m.getSpace().getCourse());
        MaterialAccess.requireManage(u, m.getSpace().getCourse());
        return m;
    }

    static String studentLink(CourseMaterial m) {
        return "/user-dashboard/materials/" + m.getSpace().getCourse().getCid() + "?item=" + m.getId();
    }

    static String staffLink(User staff, CourseMaterial m) {
        return ExamAccess.homePath(staff) + "/materials/assignment/" + m.getId();
    }

    private static String plain(BigDecimal b) {
        return b == null ? "" : b.stripTrailingZeros().toPlainString();
    }
}
