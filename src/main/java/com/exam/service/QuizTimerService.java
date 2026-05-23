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
import com.exam.model.User;
import com.exam.model.exam.NumberOfTheoryToAnswer;
import com.exam.model.exam.Quiz;
import com.exam.repository.NumberOfTheoryToAnswerRepository;
import com.exam.repository.QuizRepository;
import com.exam.repository.QuizTimerRepository;
import com.exam.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@Transactional
public class QuizTimerService {

    @Autowired private QuizTimerRepository quizTimerRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private QuizRepository quizRepository;
    @Autowired private NumberOfTheoryToAnswerRepository numberOfTheoryToAnswerRepository;

    // ─────────────────────────────────────────────
    //  TIMER  — save & get
    // ─────────────────────────────────────────────

    /**
     * Called on: 60s interval, tab blur, beforeunload.
     *
     * Key changes from original:
     *  1. User is only fetched from DB on first timer creation, not on every save.
     *  2. Server validates remainingTime against quiz duration — rejects tampering.
     *  3. status field tells the client whether save succeeded.
     */



    public QuizTimerResponseDTO saveQuizTimer(Long userId, Long quizId,
                                              QuizTimerRequestDTO request) {
        // Guard: null or negative time is invalid
        if (request.getRemainingTime() == null || request.getRemainingTime() < 0) {
            throw new IllegalArgumentException("Remaining time must be a non-negative value");
        }
        // Fetch quiz — needed for duration cap check
        Quiz quiz = quizRepository.findById(quizId)
                .orElseThrow(() -> new ResourceNotFoundException("Quiz not found"));
        // -----------------------------
        // Step 1: Parse quizTime to seconds
        int quizTimeSeconds = parseQuizDurationToSeconds(quiz.getQuizTime());
        // Step 2: Fetch all theory sections for this quiz and sum TimeAllowed
        List<NumberOfTheoryToAnswer> theorySections = numberOfTheoryToAnswerRepository
                .findByQuiz_qId(quizId); // custom repository method

        int theoryTimeSeconds = theorySections.stream()
                .mapToInt(NumberOfTheoryToAnswer::getTimeAllowed) // DB value
                .map(this::parseQuizDurationToSeconds)           // convert to seconds
                .sum();

        // Step 3: Combine for max allowed seconds
        int maxAllowedSeconds = quizTimeSeconds + theoryTimeSeconds;

        // -----------------------------
        // Debug logs
        System.out.println("=======================================================");
        System.out.println(">>> [saveQuizTimer] quizId: " + quizId + ", userId: " + userId);
        System.out.println(">>> Quiz time seconds: " + quizTimeSeconds);
        System.out.println(">>> Theory sections total seconds: " + theoryTimeSeconds);
        System.out.println(">>> Max allowed seconds: " + maxAllowedSeconds);
        System.out.println(">>> Reported remaining time: " + request.getRemainingTime());

        // Anti-tamper: client must not report more time than allowed
        if (request.getRemainingTime() > maxAllowedSeconds) {
            System.out.println(">>> ERROR: Reported remaining time exceeds allowed max!");
            throw new IllegalArgumentException(
                    "Reported remaining time exceeds total quiz duration");
        }

        // -----------------------------
        // Upsert: on first creation fetch User; on subsequent saves skip User DB call
        QuizTimer timer = quizTimerRepository
                .findByUserIdAndQuiz_qId(userId, quizId)
                .orElseGet(() -> {
                    User user = userRepository.findById(userId)
                            .orElseThrow(() -> new ResourceNotFoundException("User not found"));
                    QuizTimer t = new QuizTimer();
                    t.setUser(user);
                    t.setQuiz(quiz);
                    t.setTotalViolationCount(0);
                    return t;
                });

        timer.setRemainingTime(request.getRemainingTime());
        timer.setUpdatedAt(LocalDateTime.now());
        QuizTimer saved = quizTimerRepository.save(timer);

        // -----------------------------
        // Build response DTO
        QuizTimerResponseDTO response = new QuizTimerResponseDTO();
        response.setRemainingTime(saved.getRemainingTime());
        response.setUpdatedAt(saved.getUpdatedAt());
        response.setTotalViolationCount(saved.getTotalViolationCount());
        response.setStatus("saved");

        System.out.println(">>> [saveQuizTimer] Timer saved successfully");

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
                    QuizTimerResponseDTO response = new QuizTimerResponseDTO();
                    response.setRemainingTime(qt.getRemainingTime());
                    response.setUpdatedAt(qt.getUpdatedAt());
                    response.setTotalViolationCount(qt.getTotalViolationCount());
                    response.setStatus("saved");
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











    @Transactional
    public ViolationTimerResponseDTO saveViolationDelayTime(Long quizId, Long userId,
                                                            VoilationTimerRequestDTO requestDTO) {
        QuizTimer timer = quizTimerRepository
                .findByUserIdAndQuiz_qId(userId, quizId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "QuizTimer not found for userId: " + userId + " quizId: " + quizId));
        timer.setViolationDelayTime(requestDTO.getViolationDelayTime());
        timer.setUpdatedAt(LocalDateTime.now());
        QuizTimer saved = quizTimerRepository.save(timer);
        ViolationTimerResponseDTO response = new ViolationTimerResponseDTO();
        response.setViolationDelayTime(saved.getViolationDelayTime());
        return response;
    }












    @Transactional(readOnly = true)
    public ViolationTimerResponseDTO getViolationDelayTime(Long quizId, Long userId) {
        QuizTimer timer = quizTimerRepository
                .findByUserIdAndQuiz_qId(userId, quizId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "QuizTimer not found for userId: " + userId + " quizId: " + quizId));

        ViolationTimerResponseDTO response = new ViolationTimerResponseDTO();
        response.setViolationDelayTime(timer.getViolationDelayTime());
        return response;
    }












    // ─────────────────────────────────────────────
    //  VIOLATION COUNT
    // ─────────────────────────────────────────────

    public ViolationTimerResponseDTO saveViolationCount(Long quizId, Long userId,
                                                        VoilationTimerRequestDTO request) {
        QuizTimer timer = quizTimerRepository
                .findByUserIdAndQuiz_qId(userId, quizId)
                .orElseGet(() -> {
                    QuizTimer t = new QuizTimer();
                    t.setUser(userRepository.findById(userId).orElseThrow());
                    t.setQuiz(quizRepository.findById(quizId).orElseThrow());
                    t.setRemainingTime(0);
                    t.setTotalViolationCount(0);
                    return t;
                });

        timer.setTotalViolationCount(request.getTotalViolationCount());
        timer.setUpdatedAt(LocalDateTime.now());
        quizTimerRepository.save(timer);

        ViolationTimerResponseDTO response = new ViolationTimerResponseDTO();
        response.setTotalViolationCount(timer.getTotalViolationCount());
        return response;
    }














    @Transactional(readOnly = true)
    public ViolationTimerResponseDTO getViolationCount(Long quizId, Long userId) {
        QuizTimer timer = quizTimerRepository
                .findByUserIdAndQuiz_qId(userId, quizId)
                .orElse(null);

        ViolationTimerResponseDTO response = new ViolationTimerResponseDTO();
        response.setTotalViolationCount(timer != null ? timer.getTotalViolationCount() : 0);
        return response;
    }

    // ─────────────────────────────────────────────
    //  INTERNAL HELPER
    // ─────────────────────────────────────────────

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