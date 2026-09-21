//package com.exam.service;
//
//import com.exam.DTO.QuizDTO;
//import com.exam.DTO.QuizUpdateRequest;
//import com.exam.model.User;
//import com.exam.model.exam.*;
//import com.exam.repository.*;
//import jakarta.persistence.EntityNotFoundException;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.stereotype.Service;
//import org.springframework.transaction.annotation.Transactional;
//
//import java.security.Principal;
//import java.util.HashSet;
//import java.util.List;
//import java.util.Set;
//
//@Service
//public class QuizService {
//
//
//    @Autowired
//    private QuizRepository quizRepository;
//    @Autowired
//    private ReportRepository reportRepository;
//    @Autowired
//    private TheoryQuestionsRepository theoryQuestionsRepository;
//
//    @Autowired
//    private AnswerRepository answerRepository;
//
//
//    @Autowired
//    private NumberOfTheoryToAnswerRepository numberOfTheoryToAnswerRepository;
//    @Autowired
//    private UserRepository userRepository;
//
//    @Autowired
//    private CategoryRepository categoryRepository;
//
//    public Quiz addQuiz(Quiz quiz){
////        quiz.setStartTimeFromAMPM(quiz.getStartTime());
//        return this.quizRepository.save(quiz);
//    }
//
//
//
//
//
////    public Quiz updateQuiz(Quiz quiz){
////        return this.quizRepository.save(quiz);
////    }
//
//    public QuizDTO updateQuiz(QuizUpdateRequest request) {
//        if (request.getqId() == null) {
//            throw new IllegalArgumentException("Quiz ID cannot be null");
//        }
//
//        Quiz quiz = quizRepository.findById(request.getqId())
//                .orElseThrow(() -> new RuntimeException("Quiz not found with id: " + request.getqId()));
//        // Update fields
//        if (request.getTitle() != null) {
//            quiz.setTitle(request.getTitle());
//        }
//        if (request.getDescription() != null) {
//            quiz.setDescription(request.getDescription());
//        }
//        if (request.getMaxMarks() != null) {
//            quiz.setMaxMarks(request.getMaxMarks());
//        }
//        if (request.getQuizTime() != null) {
//            quiz.setQuizTime(request.getQuizTime());
//        }
//        if (request.getNumberOfQuestions() != null) {
//            quiz.setNumberOfQuestions(request.getNumberOfQuestions());
//        }
//        if (request.getQuizpassword() != null) {
//            quiz.setQuizpassword(request.getQuizpassword());
//        }
//        if (request.getStatus() != null) {
//            quiz.setStatus(request.getStatus());
//        }
//        if (request.getQuizType() != null) {
//            quiz.setQuizType(request.getQuizType());
//        }
//        if (request.getStartTime() != null) {
//            quiz.setStartTime(request.getStartTime());
//        }
//        if (request.getQuizDate() != null) {
//            quiz.setQuizDate(request.getQuizDate());
//        }
//        if (request.getDelayMultiplier() != null) {
//            quiz.setDelayMultiplier(request.getDelayMultiplier());
//        }
//        if (request.getAutoSubmitCountdownSeconds() != null) {
//            quiz.setAutoSubmitCountdownSeconds(request.getAutoSubmitCountdownSeconds());
//        }
//
//        if (request.getDelayIncrementOnRepeat() != null) {
//            quiz.setDelayIncrementOnRepeat(request.getDelayIncrementOnRepeat());
//        }
//        if (request.getEnableWatermark() != null) {
//            quiz.setEnableWatermark(request.getEnableWatermark());
//        }
//        if (request.getEnableDevToolsBlocking() != null) {
//            quiz.setEnableDevToolsBlocking(request.getEnableDevToolsBlocking());
//        }
//        if (request.getMaxViolations() != null) {
//            quiz.setMaxViolations(request.getMaxViolations());
//        }
//        if (request.getViolationAction() != null) {
//            quiz.setViolationAction(request.getViolationAction());
//        }
//        if (request.getDelaySeconds() != null) {
//            quiz.setDelaySeconds(request.getDelaySeconds());
//        }
//
//        if (request.getEnableFullscreenLock() != null) {
//            quiz.setEnableFullscreenLock(request.getEnableFullscreenLock());
//        }
//
//        if (request.getEnableScreenshotBlocking() != null) {
//            quiz.setEnableScreenshotBlocking(request.getEnableScreenshotBlocking());
//        }
//
//        if (request.getDelaySeconds() != null) {
//            quiz.setDelayIncrementOnRepeat(request.setDelayIncrementOnRepeat(true));
//        }
//
//
//
//
//
//        quiz.setActive(request.isActive());
//
//        // Update category if provided
//        if (request.getCategoryId() != null) {
//            Category category = categoryRepository.findById(request.getCategoryId())
//                    .orElseThrow(() -> new RuntimeException("Category not found"));
//            quiz.setCategory(category);
//        }
//
//        // User field is NOT touched, so it remains unchanged
//
//        Quiz updated = quizRepository.save(quiz);
//        return new QuizDTO(updated);
//    }
//
//
//
//
//
//
//
//
//
//
//
//
//
//
//
//    public Set<Quiz> getQuizzes(){
//        return new HashSet<>(this.quizRepository.findAll());
//    }
//
//    public Quiz getQuiz(Long qid){
//        return this.quizRepository.findById(qid).get();
//
//    }
////    public String getQuizPass(Long qid){
////        return this.quizRepository.getQuizPassword(qid);
////
////    }
//
////
////    public void deleteQuiz(Long quizId){
////        Quiz quiz = quizRepository.findById(quizId).get();
////        this.quizRepository.delete(quiz);
////
////    }
//
//
//
//    @Transactional
//    public void deleteQuiz(Long quizId) {
//
//        // 1. Delete all answers for theory questions of this quiz
//        List<TheoryQuestions> theoryQuestions = theoryQuestionsRepository.findByQuiz_qId(quizId);
//        for (TheoryQuestions tq : theoryQuestions) {
//            answerRepository.deleteByTheoryQuestionId(tq.getTqId());
//        }
//        // Delete children first
//        theoryQuestionsRepository.deleteByQuiz_qId(quizId);
//        numberOfTheoryToAnswerRepository.deleteByQuiz_Id(quizId);
//        // Delete quiz if present
//        quizRepository.findById(quizId).ifPresent(quizRepository::delete);
//    }
//
//
//
//    public List<Quiz> getQuizzesOfCategory(Category category) {
//        return this.quizRepository.findBycategory(category);
//    }
//
//    //get Active Quizzes
//    public List<Quiz> getActiveQuizzes(){
//        return this.quizRepository.findByActive(true);
//    }
//
//    //Get Acvtive And Categories
//    public List<Quiz> getActiveQuizzesofCategory(Category c){
//        return this.quizRepository.findByCategoryAndActive(c, true);
//    }
//
//
//
//
//
//
//
//
//    public List<Quiz> getTakenQuizzesOfCategory(Category category) {
//        List<Report> reports = reportRepository.findByQuiz_Category(category);
//        // extract unique quizzes
//        return reports.stream()
//                .map(Report::getQuiz)
//                .distinct()
//                .toList();
//    }
//
//
//
//
//
////    public List<Quiz> getTakenQuizzesOfCategoryByUser(Long userId, Category category) {
////        List<Report> reports =
////                reportRepository.findByUser_IdAndQuiz_Category(userId, category);
////        return reports.stream()
////                .map(Report::getQuiz)
////                .distinct()
////                .toList();
////    }
//
//
//
//
//
//    public List<Quiz> getTakenQuizzesOfCategoryByUser(Long userId, Category category) {
//        List<Report> reports =
//                reportRepository.findByUser_IdAndQuiz_Category(userId, category);
//        return reports.stream()
//                .map(Report::getQuiz)
//                .distinct()
//                .toList();
//    }
//
//
//
//
//
//    // ADD QUIZ AND USER
//    @Transactional
//    public Quiz addQuizForLoggedInUser(Quiz quiz, Principal principal) {
//        String username = principal.getName();
//        User user = userRepository.findByUsername(username)
//                .orElseThrow(() -> new EntityNotFoundException("User not found"));
//        quiz.setUser(user); // link quiz to user
//        return quizRepository.save(quiz);
//    }
//
//// fetch quiz based on the users
//    @Transactional(readOnly = true)
//    public List<Quiz> getQuizzesForLoggedInUser(Principal principal) {
//        String username = principal.getName();
//        User user = userRepository.findByUsername(username)
//                .orElseThrow(() -> new EntityNotFoundException("User not found"));
//        return quizRepository.findByUser_Id(user.getId());
//    }
//
//
//}



package com.exam.service;

import com.exam.DTO.QuizDTO;
import com.exam.DTO.QuizUpdateRequest;
import com.exam.model.QuizStatus;
import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.exam.*;
import com.exam.repository.*;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class QuizService {

    @Autowired private QuizRepository                     quizRepository;
    @Autowired private ReportRepository                   reportRepository;
    @Autowired private TheoryQuestionsRepository          theoryQuestionsRepository;
    @Autowired private AnswerRepository                   answerRepository;
    @Autowired private NumberOfTheoryToAnswerRepository   numberOfTheoryToAnswerRepository;
    @Autowired private UserRepository                     userRepository;
    @Autowired private CategoryRepository                 categoryRepository;
    @Autowired private QuestionsRepository                questionsRepository;
    @Autowired private ProgramRepository                  programRepository;
    @Autowired private QuizAttemptRepository              quizAttemptRepository;
    @Autowired private Registered_coursesRepository       registeredCoursesRepository;

    // ── Create ────────────────────────────────────────────────────────────────









    public Quiz addQuiz(Quiz quiz) {
        quiz.setPrograms(resolveAllowedPrograms(quiz.getProgramIds(), categoryOf(quiz), true));
        quiz.setMaxAttempts(validMaxAttempts(quiz.getMaxAttempts()));
        normalizeAutoClose(quiz);
        stampPublishedAt(quiz, false);
        return quizRepository.save(quiz);
    }


    public Quiz lectureAddQuiz(Quiz quiz) {
        String username = SecurityContextHolder.getContext()
                .getAuthentication()
                .getName();

        User currentUser = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));

        quiz.setUser(currentUser);
        quiz.setPrograms(resolveAllowedPrograms(quiz.getProgramIds(), categoryOf(quiz), true));
        quiz.setMaxAttempts(validMaxAttempts(quiz.getMaxAttempts()));
        normalizeAutoClose(quiz);
        stampPublishedAt(quiz, false);
        return quizRepository.save(quiz);
    }










    @Transactional
    public Quiz addQuizForLoggedInUser(Quiz quiz, Principal principal) {
        User user = resolveUser(principal);
        quiz.setUser(user);
        quiz.setPrograms(resolveAllowedPrograms(quiz.getProgramIds(), categoryOf(quiz), false));
        quiz.setMaxAttempts(validMaxAttempts(quiz.getMaxAttempts()));
        normalizeAutoClose(quiz);
        stampPublishedAt(quiz, false);
        return quizRepository.save(quiz);
    }


    //    //Get Acvtive And Categories
    public List<Quiz> getActiveQuizzesofCategory(Category c){
        return this.quizRepository.findByCategoryAndActive(c, true);
    }


    // ── Read ──────────────────────────────────────────────────────────────────

    public Set<Quiz> getQuizzes() {
        return new HashSet<>(quizRepository.findAll());
    }

    public Quiz getQuiz(Long qid) {
        return quizRepository.findById(qid)
                .orElseThrow(() -> new EntityNotFoundException("Quiz not found with id: " + qid));
    }

    public List<Quiz> getQuizzesOfCategory(Category category) {
        return quizRepository.findBycategory(category);
    }

    public List<Quiz> getActiveQuizzes() {
        return quizRepository.findByActive(true);
    }

    public List<Quiz> getActiveQuizzesOfCategory(Category category) {
        return quizRepository.findByCategoryAndActive(category, true);
    }

    public List<Quiz> getTakenQuizzesOfCategory(Category category) {
        return reportRepository.findByQuiz_Category(category)
                .stream()
                .map(Report::getQuiz)
                .distinct()
                .toList();
    }

    public List<Quiz> getTakenQuizzesOfCategoryByUser(Long userId, Category category) {
        return reportRepository.findByUser_IdAndQuiz_Category(userId, category)
                .stream()
                .map(Report::getQuiz)
                .distinct()
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Quiz> getQuizzesForLoggedInUser(Principal principal) {
        User user = resolveUser(principal);
        return quizRepository.findByUser_Id(user.getId());
    }

    // ── Update ────────────────────────────────────────────────────────────────

    @Transactional
    public QuizDTO updateQuiz(QuizUpdateRequest req) {
        if (req.getqId() == null) {
            throw new IllegalArgumentException("Quiz ID cannot be null");
        }

        Quiz quiz = quizRepository.findById(req.getqId())
                .orElseThrow(() -> new EntityNotFoundException("Quiz not found with id: " + req.getqId()));

        // ── Core fields ───────────────────────────────────────────────────────
        if (req.getTitle()               != null) quiz.setTitle(req.getTitle());
        if (req.getDescription()         != null) quiz.setDescription(req.getDescription());
        if (req.getMaxMarks()            != null) quiz.setMaxMarks(req.getMaxMarks());
        if (req.getQuizTime()            != null) quiz.setQuizTime(req.getQuizTime());
        if (req.getNumberOfQuestions()   != null) quiz.setNumberOfQuestions(req.getNumberOfQuestions());
        if (req.getQuizpassword()        != null) quiz.setQuizpassword(req.getQuizpassword());
        if (req.getStatus()              != null) quiz.setStatus(req.getStatus());
        if (req.getQuizType()            != null) quiz.setQuizType(req.getQuizType());
        if (req.getStartTime()           != null) quiz.setStartTime(req.getStartTime());
        if (req.getQuizDate()            != null) quiz.setQuizDate(req.getQuizDate());

        // ── Proctoring fields ─────────────────────────────────────────────────
        if (req.getViolationAction()            != null) quiz.setViolationAction(req.getViolationAction());
        if (req.getAutoSubmitCountdownSeconds() != null) quiz.setAutoSubmitCountdownSeconds(req.getAutoSubmitCountdownSeconds());
        if (req.getMaxViolations()              != null) quiz.setMaxViolations(req.getMaxViolations());
        if (req.getDelaySeconds()               != null) quiz.setDelaySeconds(req.getDelaySeconds());
        if (req.getDelayMultiplier()            != null) quiz.setDelayMultiplier(req.getDelayMultiplier());
        if (req.getMaxDelaySeconds()            != null) quiz.setMaxDelaySeconds(req.getMaxDelaySeconds());
        if (req.getDelayIncrementOnRepeat()     != null) quiz.setDelayIncrementOnRepeat(req.getDelayIncrementOnRepeat());
        if (req.getEnableWatermark()            != null) quiz.setEnableWatermark(req.getEnableWatermark());
        if (req.getEnableFullscreenLock()       != null) quiz.setEnableFullscreenLock(req.getEnableFullscreenLock());
        if (req.getEnableScreenshotBlocking()   != null) quiz.setEnableScreenshotBlocking(req.getEnableScreenshotBlocking());
        if (req.getEnableDevToolsBlocking()     != null) quiz.setEnableDevToolsBlocking(req.getEnableDevToolsBlocking());

        // ── LLM Provider ──────────────────────────────────────────────────────
        if (req.getLlmProvider()                != null) quiz.setLlmProvider(req.getLlmProvider());

        // active is a primitive boolean — always apply
        boolean wasActive = quiz.isActive();
        quiz.setActive(req.isActive());
        quiz.setAutoOpen(req.isAutoOpen());
        quiz.setAutoClose(req.isAutoClose());
        quiz.setAutoCloseFraction(req.getAutoCloseFraction());
        normalizeAutoClose(quiz);
        stampPublishedAt(quiz, wasActive);

        // ── Category ──────────────────────────────────────────────────────────
        if (req.getCategoryId() != null) {
            Category category = categoryRepository.findById(req.getCategoryId())
                    .orElseThrow(() -> new EntityNotFoundException("Category not found with id: " + req.getCategoryId()));
            quiz.setCategory(category);
        }

        if (req.getMaxAttempts() != null) quiz.setMaxAttempts(validMaxAttempts(req.getMaxAttempts()));

        // ── Allowed programs (null = leave unchanged) ─────────────────────────
        // Emptying the list is only rejected when the quiz already has programs,
        // so legacy quizzes (no programs) stay editable.
        if (req.getProgramIds() != null) {
            boolean requireSelection = !quiz.getPrograms().isEmpty();
            quiz.setPrograms(resolveAllowedPrograms(req.getProgramIds(), quiz.getCategory(), requireSelection));
        }

        return new QuizDTO(quizRepository.save(quiz));
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    @Transactional
    public void deleteQuiz(Long quizId) {
        Quiz quiz = quizRepository.findById(quizId)
                .orElseThrow(() -> new EntityNotFoundException("Quiz not found with id: " + quizId));

        // 1. Delete theory answers → theory questions → theory count
        List<TheoryQuestions> theoryQuestions = theoryQuestionsRepository.findByQuiz_qId(quizId);
        for (TheoryQuestions tq : theoryQuestions) {
            answerRepository.deleteByTheoryQuestionId(tq.getTqId());
        }
        theoryQuestionsRepository.deleteByQuiz_qId(quizId);
        numberOfTheoryToAnswerRepository.deleteByQuiz_Id(quizId);

        // 2. Delete OBJ questions (matchingPairs are cascade-deleted via orphanRemoval)
        questionsRepository.deleteByQuiz_Id(quizId);

        // 3. Delete attempts, then reports
        quizAttemptRepository.deleteByQuizId(quizId);
        reportRepository.deleteByQuiz(quiz);

        // 4. Finally delete the quiz itself
        quizRepository.delete(quiz);
    }

    /** Attempts allowed per student: 1..MAX_ATTEMPTS_LIMIT; null (not supplied) means 1. */
    private Integer validMaxAttempts(Integer requested) {
        if (requested == null) return 1;
        if (requested < 1 || requested > AttemptService.MAX_ATTEMPTS_LIMIT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Attempts allowed must be between 1 and " + AttemptService.MAX_ATTEMPTS_LIMIT);
        }
        return requested;
    }

    // ── Allowed programs ──────────────────────────────────────────────────────

    private Category categoryOf(Quiz quiz) {
        if (quiz.getCategory() == null || quiz.getCategory().getCid() == null) return null;
        return categoryRepository.findById(quiz.getCategory().getCid()).orElse(null);
    }

    private User currentUser() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new EntityNotFoundException("User not found: " + username));
    }

    /**
     * Resolves program ids into Program entities (client-supplied Program objects are
     * never trusted), enforcing who may assign what:
     *  - ADMIN (HOD):  only programs of their own department.
     *  - LECTURER:     only programs attached to the quiz's course (category).
     *  - SUPER_ADMIN:  any program.
     * When requireSelection is true, an ADMIN must pick at least one program, and so
     * must a LECTURER whose course has programs.
     */
    private Set<Program> resolveAllowedPrograms(List<Long> rawIds, Category category, boolean requireSelection) {
        List<Long> ids = rawIds == null ? List.of() : rawIds.stream().distinct().toList();
        Set<Program> programs = new HashSet<>(programRepository.findAllById(ids));
        if (programs.size() != ids.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "One or more selected programs do not exist");
        }

        User caller = currentUser();
        if (caller.getRole() == Role.ADMIN) {
            if (requireSelection && programs.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select at least one program for this quiz");
            }
            Long deptId = caller.getDepartment() == null ? null : caller.getDepartment().getId();
            boolean allInDept = deptId != null && programs.stream()
                    .allMatch(p -> p.getDepartment() != null && deptId.equals(p.getDepartment().getId()));
            if (!programs.isEmpty() && !allInDept) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only assign programs from your own department");
            }
        } else if (caller.getRole() == Role.LECTURER) {
            Set<Long> courseProgramIds = category == null || category.getPrograms() == null
                    ? Set.of()
                    : category.getPrograms().stream().map(Program::getId).collect(Collectors.toSet());
            if (requireSelection && !courseProgramIds.isEmpty() && programs.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select at least one program for this quiz");
            }
            if (!programs.stream().allMatch(p -> courseProgramIds.contains(p.getId()))) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only assign programs attached to the selected course");
            }
        }
        return programs;
    }

    /** A lecturer can turn auto-close on without picking a fraction; default that to HALF rather than reject it. */
    private void normalizeAutoClose(Quiz quiz) {
        if (quiz.isAutoClose() && quiz.getAutoCloseFraction() == null) {
            quiz.setAutoCloseFraction(AutoCloseFraction.HALF);
        }
    }

    /**
     * Keeps {@code publishedAt} in step with {@code active}: stamped the moment a quiz goes live
     * (from draft or on creation), cleared the moment it's taken back to draft. Never trusts a
     * client-supplied value — the entity field itself is read-only over JSON (see {@link Quiz}).
     */
    private void stampPublishedAt(Quiz quiz, boolean wasActive) {
        if (quiz.isActive() && !wasActive) {
            quiz.setPublishedAt(java.time.LocalDateTime.now());
        } else if (!quiz.isActive() && wasActive) {
            quiz.setPublishedAt(null);
        }
    }

    // ── Auto-open ────────────────────────────────────────────────────────────

    /**
     * If the quiz is set to auto-open and its scheduled date/time has arrived, publishes and opens
     * it (sets active=true and status=OPEN — the same state a manual "go live" + "open" would
     * leave it in). A no-op for quizzes that don't have auto-open on, aren't due yet, or are
     * already published and open.
     * <p>
     * Called from two places so opening never depends on either alone:
     *  - {@link #openDueQuizzes()}, a periodic sweep that catches every scheduled quiz on its own;
     *  - {@link #assertStudentMayAccess(Quiz, User)}, so a student following a direct link the
     *    moment the clock ticks over is never blocked by sweep latency.
     */
    @Transactional
    public Quiz ensureAutoOpened(Quiz quiz) {
        if (quiz == null || !quiz.isAutoOpen()) return quiz;
        if (quiz.isActive() && quiz.getStatus() == QuizStatus.OPEN) {
            // Already open — normally nothing to do. But a quiz auto-opened before publishedAt
            // existed (or before a since-fixed bug in this method) is stuck active with no
            // publishedAt forever, since that field is only ever set on a false→true transition.
            // Backfill it here using its own schedule — the best available estimate of when an
            // auto-open quiz actually went live — so the "Published on …" display recovers on
            // the very next time anyone touches this quiz, with no migration needed.
            if (quiz.getPublishedAt() == null && quiz.getQuizDate() != null && quiz.getStartTime() != null) {
                quiz.setPublishedAt(java.time.LocalDateTime.of(quiz.getQuizDate(), quiz.getStartTime()));
                return quizRepository.save(quiz);
            }
            return quiz;
        }
        if (quiz.getQuizDate() == null || quiz.getStartTime() == null) return quiz;

        java.time.LocalDateTime dueAt = java.time.LocalDateTime.of(quiz.getQuizDate(), quiz.getStartTime());
        if (java.time.LocalDateTime.now().isBefore(dueAt)) return quiz;

        boolean wasActive = quiz.isActive();
        quiz.setActive(true);
        quiz.setStatus(QuizStatus.OPEN);
        stampPublishedAt(quiz, wasActive);
        return quizRepository.save(quiz);
    }

    /** Periodic backstop: opens every due, auto-open quiz even if nobody has tried to access it yet. */
    @org.springframework.scheduling.annotation.Scheduled(fixedRate = 30_000)
    @Transactional
    public void openDueQuizzes() {
        quizRepository.findByAutoOpenTrueAndActiveFalse().forEach(this::ensureAutoOpened);
    }

    // ── Auto-close ───────────────────────────────────────────────────────────

    /**
     * The exam duration in minutes, exactly as the student's own on-screen timer counts it
     * (Instructions/StartQuiz sum these same two numbers): the objective section's quizTime, plus
     * — for THEORY/BOTH — the theory section's timeAllowed. The latter is configured separately,
     * when theory questions are set up, often after the quiz is created/published; reading it live
     * here (rather than a value captured once) is what lets auto-close work correctly even though
     * that number can still change after the quiz has gone live.
     */
    private int resolveDurationMinutes(Quiz quiz) {
        int objMinutes = parseMinutes(quiz.getQuizTime());
        int theoryMinutes = numberOfTheoryToAnswerRepository.findByQuiz_qId(quiz.getqId()).stream()
                .findFirst()
                .map(NumberOfTheoryToAnswer::getTimeAllowed)
                .filter(java.util.Objects::nonNull)
                .orElse(0);
        return objMinutes + theoryMinutes;
    }

    private static int parseMinutes(String raw) {
        if (raw == null || raw.isBlank()) return 0;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * If the quiz has auto-close on and is currently open, closes it once the chosen fraction
     * (half or a quarter) of its total duration has elapsed since it was published. A no-op while
     * the duration isn't known yet — e.g. a THEORY/BOTH quiz whose theory time-allowed hasn't been
     * configured — so it never closes against a duration of zero; it simply starts counting the
     * moment that duration becomes known (checked live, every time, not just once).
     */
    @Transactional
    public Quiz ensureAutoClosed(Quiz quiz) {
        if (quiz == null || !quiz.isAutoClose()) return quiz;
        if (!quiz.isActive() || quiz.getStatus() != QuizStatus.OPEN) return quiz;
        if (quiz.getPublishedAt() == null) return quiz;

        int durationMinutes = resolveDurationMinutes(quiz);
        if (durationMinutes <= 0) return quiz;

        AutoCloseFraction fraction = quiz.getAutoCloseFraction() != null ? quiz.getAutoCloseFraction() : AutoCloseFraction.HALF;
        long secondsUntilClose = Math.round(durationMinutes * 60.0 / fraction.getDivisor());
        java.time.LocalDateTime closeAt = quiz.getPublishedAt().plusSeconds(secondsUntilClose);
        if (java.time.LocalDateTime.now().isBefore(closeAt)) return quiz;

        quiz.setStatus(QuizStatus.CLOSED);
        return quizRepository.save(quiz);
    }

    /** Periodic backstop: closes every due, auto-close quiz even if nobody has tried to access it yet. */
    @org.springframework.scheduling.annotation.Scheduled(fixedRate = 30_000)
    @Transactional
    public void closeDueQuizzes() {
        quizRepository.findByAutoCloseTrueAndActiveTrueAndStatus(QuizStatus.OPEN).forEach(this::ensureAutoClosed);
    }

    // ── Student access (program + course enrollment) ────────────────────────────

    /** A quiz with no programs is open to everyone; otherwise the student's program must be listed. */
    private boolean isOpenToStudent(Quiz quiz, User student) {
        if (quiz.getPrograms() == null || quiz.getPrograms().isEmpty()) return true;
        if (student.getProgram() == null) return false;
        Long programId = student.getProgram().getId();
        return quiz.getPrograms().stream().anyMatch(p -> p.getId().equals(programId));
    }

    /**
     * A quiz belongs to a course (category); the student must be registered for that course.
     * A quiz with no course (shouldn't normally happen — the create forms require one) is open,
     * since there is nothing to be enrolled in.
     */
    private boolean isEnrolledInCourse(Quiz quiz, User student) {
        if (quiz.getCategory() == null) return true;
        return registeredCoursesRepository.countByCategoryAndUser(quiz.getCategory(), student) > 0;
    }

    /** Null if the student may access this quiz; otherwise the reason they may not, for the error message. */
    private String accessDenialReason(Quiz quiz, User student) {
        if (!isOpenToStudent(quiz, student)) return "This quiz is not available for your program";
        if (!isEnrolledInCourse(quiz, student)) return "You are not enrolled in the course this quiz belongs to";
        return null;
    }

    private User studentOrNull(Principal principal) {
        if (principal == null) return null;
        return userRepository.findByUsername(principal.getName())
                .filter(u -> u.getRole() == Role.NORMAL)
                .orElse(null);
    }

    /** Hides quizzes the calling student's program/enrollment doesn't allow. Non-students see everything. */
    public List<Quiz> filterForCaller(List<Quiz> quizzes, Principal principal) {
        User student = studentOrNull(principal);
        if (student == null) return quizzes;
        return quizzes.stream().filter(q -> accessDenialReason(q, student) == null).toList();
    }

    /** Throws 403 if the caller is a student who may not access this quiz (program or non-enrollment). */
    public void assertStudentMayAccess(Long quizId, Principal principal) {
        assertStudentMayAccess(getQuiz(quizId), studentOrNull(principal));
    }

    /** Throws 403 if student is a NORMAL user who may not access this quiz. Non-students (or null) pass through. */
    public void assertStudentMayAccess(Quiz quiz, User student) {
        ensureAutoOpened(quiz);    // catch up immediately rather than wait for the next sweep
        ensureAutoClosed(quiz);    // same, for closing — order matters: a quiz can open then immediately need closing
        if (student == null || student.getRole() != Role.NORMAL) return;
        String reason = accessDenialReason(quiz, student);
        if (reason != null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, reason);
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private User resolveUser(Principal principal) {
        return userRepository.findByUsername(principal.getName())
                .orElseThrow(() -> new EntityNotFoundException("User not found: " + principal.getName()));
    }
}