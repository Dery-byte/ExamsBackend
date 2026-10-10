//package com.exam.service;
//
//import com.exam.DTO.QuizTimerRequestDTO;
//import com.exam.DTO.QuizTimerResponseDTO;
//import com.exam.DTO.ViolationTimerResponseDTO;
//import com.exam.DTO.VoilationTimerRequestDTO;
//import com.exam.helper.ResourceNotFoundException;
//import com.exam.model.QuizTimer;
//import com.exam.model.User;
//import com.exam.model.exam.Quiz;
//import com.exam.repository.QuizRepository;
//import com.exam.repository.QuizTimerRepository;
//import com.exam.repository.UserRepository;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.stereotype.Service;
//import org.springframework.transaction.annotation.Transactional;
//
//import java.time.LocalDateTime;
//import java.util.Optional;
//
//@Service
//@Transactional
//public class QuizTimerService {
//
//    @Autowired
//    private QuizTimerRepository quizTimerRepository;
//
//    @Autowired
//    private UserRepository userRepository;
//
//    @Autowired
//    private QuizRepository quizRepository;
//
//
//
//
//    public QuizTimerResponseDTO getQuizTimer(Long userId, Long quizId) {
//        Optional<QuizTimer> timer = quizTimerRepository.findByUserIdAndQuiz_qId(userId, quizId);
//
//        if (timer.isPresent()) {
//            QuizTimer qt = timer.get();
//            QuizTimerResponseDTO response = new QuizTimerResponseDTO();
//            response.setRemainingTime(qt.getRemainingTime());
//            response.setUpdatedAt(qt.getUpdatedAt());
//            return response;
//        }
//
//        return null;
//    }
//    public QuizTimerResponseDTO saveQuizTimer(Long userId, Long quizId, QuizTimerRequestDTO request) {
//        User user = userRepository.findById(userId)
//                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
//        Quiz quiz = quizRepository.findById(quizId)
//                .orElseThrow(() -> new ResourceNotFoundException("Quiz not found"));
//        QuizTimer timer = quizTimerRepository.findByUserIdAndQuiz_qId(userId, quizId)
//                .orElse(new QuizTimer());
//        timer.setUser(user);
//        timer.setQuiz(quiz);
//        timer.setRemainingTime(request.getRemainingTime());
//        timer.setUpdatedAt(LocalDateTime.now());
//        QuizTimer saved = quizTimerRepository.save(timer);
//        QuizTimerResponseDTO response = new QuizTimerResponseDTO();
//        response.setRemainingTime(saved.getRemainingTime());
//        response.setUpdatedAt(saved.getUpdatedAt());
//        return response;
//    }
//
//    public void deleteQuizTimer(Long userId, Long quizId) {
//        quizTimerRepository.deleteByUserIdAndQuiz_qId(userId, quizId);
//    }
//
//
//
//
//    @Transactional
//    public ViolationTimerResponseDTO saveViolationDelayTime(
//            Long quizId,
//            Long userId,
//            VoilationTimerRequestDTO requestDTO) {
//
//        QuizTimer timer = quizTimerRepository.findByUserIdAndQuiz_qId(userId, quizId)
//                .orElseThrow(() ->
//                        new ResourceNotFoundException(
//                                "QuizTimer not found for userId: " + userId + " quizId: " + quizId
//                        )
//                );
//
//        timer.setViolationDelayTime(requestDTO.getViolationDelayTime());
//        timer.setUpdatedAt(LocalDateTime.now());
//
//        QuizTimer saved = quizTimerRepository.save(timer);
//
//        ViolationTimerResponseDTO response = new ViolationTimerResponseDTO();
//        response.setViolationDelayTime(saved.getViolationDelayTime());
//
//        return response;
//    }
//
//
//
//
//    @Transactional(readOnly = true)
//    public ViolationTimerResponseDTO getViolationDelayTime(
//            Long quizId,
//            Long userId) {
//
//        QuizTimer timer = quizTimerRepository.findByUserIdAndQuiz_qId(userId, quizId)
//                .orElseThrow(() ->
//                        new ResourceNotFoundException(
//                                "QuizTimer not found for userId: " + userId + " quizId: " + quizId
//                        )
//                );
//
//        ViolationTimerResponseDTO response = new ViolationTimerResponseDTO();
//        response.setViolationDelayTime(timer.getViolationDelayTime());
//
//        return response;
//    }
//
//
//
//    public ViolationTimerResponseDTO saveViolationCount(Long quizId, Long userId, VoilationTimerRequestDTO request) {
//        QuizTimer timer = quizTimerRepository.findByUserIdAndQuiz_qId(userId, quizId)
//                .orElseGet(() -> {
//                    QuizTimer t = new QuizTimer();
//                    t.setUser(userRepository.findById(userId).orElseThrow());
//                    t.setQuiz(quizRepository.findById(quizId).orElseThrow());
//                    t.setRemainingTime(0);
//                    return t;
//                });
//        timer.setTotalViolationCount(request.getTotalViolationCount());
//        timer.setUpdatedAt(LocalDateTime.now());
//        quizTimerRepository.save(timer);
//        ViolationTimerResponseDTO response = new ViolationTimerResponseDTO();
//        response.setTotalViolationCount(timer.getTotalViolationCount());
//        return response;
//    }
//
//    public ViolationTimerResponseDTO getViolationCount(Long quizId, Long userId) {
//        QuizTimer timer = quizTimerRepository.findByUserIdAndQuiz_qId(userId, quizId)
//                .orElse(null);
//        ViolationTimerResponseDTO response = new ViolationTimerResponseDTO();
//        response.setTotalViolationCount(timer != null ? timer.getTotalViolationCount() : 0);
//        return response;
//    }
//
//}






package com.exam.service;

import com.exam.DTO.QuizTimerRequestDTO;
import com.exam.DTO.QuizTimerResponseDTO;
import com.exam.DTO.ViolationTimerResponseDTO;
import com.exam.DTO.VoilationTimerRequestDTO;
import com.exam.helper.ResourceNotFoundException;
import com.exam.model.QuizTimer;
import com.exam.model.exam.AttemptStatus;
import com.exam.model.exam.NumberOfTheoryToAnswer;
import com.exam.model.exam.Quiz;
import com.exam.repository.NumberOfTheoryToAnswerRepository;
import com.exam.repository.QuizAttemptRepository;
import com.exam.repository.QuizRepository;
import com.exam.repository.QuizTimerRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
@Transactional
public class QuizTimerService {

    @Autowired private QuizTimerRepository quizTimerRepository;
    @Autowired private QuizRepository quizRepository;
    @Autowired private NumberOfTheoryToAnswerRepository numberOfTheoryToAnswerRepository;
    @Autowired private com.exam.service.SystemSettingService systemSettingService;
    @Autowired private QuizAttemptRepository quizAttemptRepository;

    /** Allowance for network latency when a client reports a little more time than the server expects. */
    private static final int SAVE_TOLERANCE_SECONDS = 5;

    /**
     * Time actually left on a checkpoint. If the exam clock runs while the student is away,
     * the seconds since the last checkpoint are subtracted.
     */
    private int effectiveRemaining(QuizTimer t) {
        int remaining = t.getRemainingTime() == null ? 0 : t.getRemainingTime();
        if (t.getUpdatedAt() == null || !clockRunsWhileAway()) {
            return remaining;
        }
        long away = java.time.Duration.between(t.getUpdatedAt(), LocalDateTime.now()).getSeconds();
        return (int) Math.max(0, remaining - Math.max(0, away));
    }

    // ─────────────────────────────────────────────
    //  TIMER  — save & get
    // ─────────────────────────────────────────────

    /**
     * Called on: 60s interval, tab blur, beforeunload.
     *
     * Key changes from original:
     *  1. The row is created atomically in the DB (concurrent first saves cannot collide).
     *  2. Server validates remainingTime against quiz duration — rejects tampering.
     *  3. status field tells the client whether save succeeded.
     */



    public QuizTimerResponseDTO saveQuizTimer(Long userId, Long quizId,
                                              QuizTimerRequestDTO request) {
        // Guard: null or negative time is invalid
        if (request.getRemainingTime() == null || request.getRemainingTime() < 0) {
            throw new IllegalArgumentException("Remaining time must be a non-negative value");
        }
        int maxAllowedSeconds = maxAllowedSeconds(quizId);

        // Anti-tamper: client must not report more time than allowed
        if (request.getRemainingTime() > maxAllowedSeconds) {
            throw new IllegalArgumentException(
                    "Reported remaining time exceeds total quiz duration");
        }

        // -----------------------------
        // Upsert: one tab switch fires several saves at once, so the row is created atomically
        // in the DB and then locked — a find-then-insert here collides on the (user_id, quiz_id) key
        boolean created = quizTimerRepository.insertIfAbsent(
                userId, quizId, request.getRemainingTime(), LocalDateTime.now()) == 1;
        QuizTimer timer = lockTimer(userId, quizId);

        // A checkpoint can only move the clock down: never accept more time than is actually left
        int accepted = request.getRemainingTime();
        if (!created) {
            accepted = Math.min(accepted, effectiveRemaining(timer) + SAVE_TOLERANCE_SECONDS);
        }
        timer.setRemainingTime(Math.max(0, accepted));
        timer.setUpdatedAt(LocalDateTime.now());
        QuizTimer saved = quizTimerRepository.save(timer);

        // -----------------------------
        // Build response DTO
        QuizTimerResponseDTO response = new QuizTimerResponseDTO();
        response.setRemainingTime(saved.getRemainingTime());
        response.setUpdatedAt(saved.getUpdatedAt());
        response.setTotalViolationCount(saved.getTotalViolationCount());
        response.setStatus("saved");

        return response;
    }















    /**
     * Called once when the student loads or reloads the quiz on any device.
     * Client checks status:
     *   "saved"     → resume countdown from remainingTime
     *   "not_found" → no checkpoint yet, start from full quiz duration
     */
    @Transactional(readOnly = true)
    public QuizTimerResponseDTO getQuizTimer(Long userId, Long quizId) {
        return quizTimerRepository
                .findByUserIdAndQuiz_qId(userId, quizId)
                .map(qt -> {
                    int left = effectiveRemaining(qt);
                    QuizTimerResponseDTO response = new QuizTimerResponseDTO();
                    response.setRemainingTime(left);
                    response.setUpdatedAt(qt.getUpdatedAt());
                    response.setTotalViolationCount(qt.getTotalViolationCount());
                    // "expired" → the client must submit straight away instead of restarting the clock
                    response.setStatus(left > 0 ? "saved" : "expired");
                    return response;
                })
                .orElseGet(() -> {
                    // No checkpoint yet — client starts fresh from full duration
                    QuizTimerResponseDTO response = new QuizTimerResponseDTO();
                    response.setStatus("not_found");
                    return response;
                });
    }

    public void deleteQuizTimer(Long userId, Long quizId) {
        quizTimerRepository.deleteByUserIdAndQuiz_qId(userId, quizId);
    }

    // ─────────────────────────────────────────────
    //  VIOLATION DELAY
    // ─────────────────────────────────────────────
    //
    // Violation saves never touch updatedAt: that is the exam clock's checkpoint time, and moving it
    // without a new remainingTime would hand the student back the seconds since the last checkpoint.

    /**
     * Starts (or clears, with 0) a violation lock-out. The client sends the length when the lock-out
     * starts; the server stores when it ends, and a student who signs in again — on any device —
     * resumes the same lock-out. When the lock-out pauses while the student is away, the client also
     * reports what is left every few seconds and when the page closes.
     */
    @Transactional
    public ViolationTimerResponseDTO saveViolationDelayTime(Long quizId, Long userId,
                                                            VoilationTimerRequestDTO requestDTO) {
        int seconds = requestDTO.getViolationDelayTime() == null ? 0 : Math.max(0, requestDTO.getViolationDelayTime());
        ViolationTimerResponseDTO response = new ViolationTimerResponseDTO();
        Optional<QuizTimer> found = seconds > 0
                ? timerForViolation(userId, quizId)
                : quizTimerRepository.findForUpdate(userId, quizId);
        found.ifPresent(timer -> {
            timer.setViolationDelayTime(seconds);
            timer.setViolationDelayUntil(seconds > 0 ? LocalDateTime.now().plusSeconds(seconds) : null);
            quizTimerRepository.save(timer);
        });
        response.setViolationDelayTime(found.isPresent() ? seconds : 0);
        response.setPausesWhileAway(!lockoutRunsWhileAway());
        return response;
    }

    /** Seconds of lock-out still to serve (0 when none). */
    @Transactional(readOnly = true)
    public ViolationTimerResponseDTO getViolationDelayTime(Long quizId, Long userId) {
        boolean runsWhileAway = lockoutRunsWhileAway();
        ViolationTimerResponseDTO response = new ViolationTimerResponseDTO();
        response.setViolationDelayTime(quizTimerRepository.findByUserIdAndQuiz_qId(userId, quizId)
                .map(t -> remainingDelay(t, runsWhileAway))
                .orElse(0));
        response.setPausesWhileAway(!runsWhileAway);
        return response;
    }

    /**
     * The lock-out has its own Super Admin setting, separate from the exam clock. When it runs while
     * the student is away, it ends at the stored time. When it pauses, it stopped at the last time
     * the student's page reported what was left (every few seconds, and as the page closed), so that
     * is what is still to serve — however long they were gone.
     */
    private int remainingDelay(QuizTimer t, boolean runsWhileAway) {
        LocalDateTime until = t.getViolationDelayUntil();
        int length = t.getViolationDelayTime() == null ? 0 : Math.max(0, t.getViolationDelayTime());
        if (until == null) return 0;
        if (!runsWhileAway) return length;
        long left = Duration.between(LocalDateTime.now(), until).getSeconds();
        return (int) Math.max(0, Math.min(length, left));
    }

    // ─────────────────────────────────────────────
    //  VIOLATION COUNT
    // ─────────────────────────────────────────────

    public ViolationTimerResponseDTO saveViolationCount(Long quizId, Long userId,
                                                        VoilationTimerRequestDTO request) {
        int count = recordViolationCount(quizId, userId,
                request.getTotalViolationCount() == null ? 0 : request.getTotalViolationCount());
        ViolationTimerResponseDTO response = new ViolationTimerResponseDTO();
        response.setTotalViolationCount(count);
        return response;
    }

    /**
     * Stores the student's violation count for the attempt in progress and returns the stored count.
     * The count only goes up, so a late or repeated save (the client retries failed ones) can never
     * undo a violation.
     */
    public int recordViolationCount(Long quizId, Long userId, int violationNumber) {
        return timerForViolation(userId, quizId).map(timer -> {
            int current = timer.getTotalViolationCount() == null ? 0 : timer.getTotalViolationCount();
            if (violationNumber > current) {
                timer.setTotalViolationCount(violationNumber);
                quizTimerRepository.save(timer);
            }
            return Math.max(current, violationNumber);
        }).orElse(0);
    }

    @Transactional(readOnly = true)
    public ViolationTimerResponseDTO getViolationCount(Long quizId, Long userId) {
        QuizTimer timer = quizTimerRepository
                .findByUserIdAndQuiz_qId(userId, quizId)
                .orElse(null);

        ViolationTimerResponseDTO response = new ViolationTimerResponseDTO();
        response.setTotalViolationCount(timer != null && timer.getTotalViolationCount() != null ? timer.getTotalViolationCount() : 0);
        return response;
    }

    // ─────────────────────────────────────────────
    //  INTERNAL HELPER
    // ─────────────────────────────────────────────

    private QuizTimer lockTimer(Long userId, Long quizId) {
        return quizTimerRepository.findForUpdate(userId, quizId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "QuizTimer not found for userId: " + userId + " quizId: " + quizId));
    }

    /**
     * The student's timer row, locked, for recording a violation. A violation can arrive before the
     * first clock checkpoint, so the row is created then — seeded with the full duration, not 0 (a 0
     * would make the next checkpoint clamp the exam clock to 5 s). Never created once the attempt is
     * over: a retried save arriving after submission must not leave a stale row for the next attempt.
     */
    private Optional<QuizTimer> timerForViolation(Long userId, Long quizId) {
        if (quizTimerRepository.findByUserIdAndQuiz_qId(userId, quizId).isEmpty()) {
            if (!quizAttemptRepository.existsByUser_IdAndQuiz_qIdAndStatus(userId, quizId, AttemptStatus.IN_PROGRESS)) {
                return Optional.empty();
            }
            quizTimerRepository.insertIfAbsent(userId, quizId, maxAllowedSeconds(quizId), LocalDateTime.now());
        }
        return quizTimerRepository.findForUpdate(userId, quizId);
    }

    private boolean clockRunsWhileAway() {
        return systemSettingService.getBooleanSetting(com.exam.service.SystemSettingService.EXAM_CLOCK_RUNS_WHILE_AWAY, true);
    }

    private boolean lockoutRunsWhileAway() {
        return systemSettingService.getBooleanSetting(com.exam.service.SystemSettingService.EXAM_LOCKOUT_RUNS_WHILE_AWAY, false);
    }

    /** Full exam length in seconds: the quiz time plus the time allowed for every theory section. */
    private int maxAllowedSeconds(Long quizId) {
        Quiz quiz = quizRepository.findById(quizId)
                .orElseThrow(() -> new ResourceNotFoundException("Quiz not found"));
        int theoryTimeSeconds = numberOfTheoryToAnswerRepository.findByQuiz_qId(quizId).stream()
                .mapToInt(NumberOfTheoryToAnswer::getTimeAllowed)
                .map(this::parseQuizDurationToSeconds)
                .sum();
        return parseQuizDurationToSeconds(quiz.getQuizTime()) + theoryTimeSeconds;
    }

    /**
     * Parses quizTime string to seconds.
     * Confirm which format your data uses and adjust accordingly:
     *   "30"     → 30 minutes → 1800 seconds
     *   "1:30"   → 1h 30m    → 5400 seconds
     */
    private int parseQuizDurationToSeconds(String quizTime) {
        try {
            if (quizTime != null && quizTime.contains(":")) {
                String[] parts = quizTime.split(":");
                return (Integer.parseInt(parts[0]) * 3600)
                        + (Integer.parseInt(parts[1]) * 60);
            }
            return Integer.parseInt(quizTime.trim()) * 60;
        } catch (NumberFormatException e) {
            return 10800; // safe fallback: 3 hours
        }
    }

    private int parseQuizDurationToSeconds(Integer minutes) {
        if (minutes == null) return 0;
        return minutes * 60;
    }

}