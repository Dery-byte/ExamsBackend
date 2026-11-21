package com.exam.service.examops;

import com.exam.model.User;
import com.exam.model.exam.Quiz;
import com.exam.model.examops.QuizCodeLock;
import com.exam.repository.QuizCodeLockRepository;
import com.exam.repository.QuizRepository;
import com.exam.repository.UserRepository;
import com.exam.service.comms.CurrentUserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Caps wrong quiz access codes: after {@link #MAX_WRONG} wrong codes for a quiz (since the student's
 * last correct one) the code box locks for that quiz until the quiz's lecturer, the HOD or the Super
 * Admin allow attempts again. The per-minute limit (a short wait with a countdown) is separate.
 */
@Service
public class QuizCodeLockService {

    public static final int MAX_WRONG = 10;
    public static final String LOCKED_MESSAGE =
            "You have entered a wrong access code too many times. Ask your lecturer to let you try again.";

    @Autowired private QuizCodeLockRepository repository;
    @Autowired private QuizRepository quizRepository;
    @Autowired private UserRepository userRepository;

    /** The student's state after a wrong code: how many so far, how many left, and whether now locked. */
    public record WrongCode(int wrongCount, int triesLeft, boolean locked) {}

    @Transactional(readOnly = true)
    public boolean isLocked(Long studentId, Long quizId) {
        return repository.findByQuiz_qIdAndStudent_Id(quizId, studentId).map(c -> c.getLockedAt() != null).orElse(false);
    }

    /** Counts a wrong code; at MAX_WRONG the code box locks for this quiz. */
    @Transactional
    public WrongCode recordWrong(User student, Quiz quiz) {
        QuizCodeLock c = repository.findForUpdate(quiz.getqId(), student.getId()).orElseGet(() -> {
            QuizCodeLock n = new QuizCodeLock();
            n.setQuiz(quiz);
            n.setStudent(student);
            return n;
        });
        c.setWrongCount(c.getWrongCount() + 1);
        c.setLastWrongAt(LocalDateTime.now());
        if (c.getWrongCount() >= MAX_WRONG && c.getLockedAt() == null) c.setLockedAt(LocalDateTime.now());
        repository.save(c);
        return new WrongCode(c.getWrongCount(), Math.max(0, MAX_WRONG - c.getWrongCount()), c.getLockedAt() != null);
    }

    /** A correct code: the count starts again, so occasional typos never add up. */
    @Transactional
    public void recordCorrect(Long studentId, Long quizId) {
        repository.findForUpdate(quizId, studentId).ifPresent(c -> {
            if (c.getWrongCount() == 0 && c.getLockedAt() == null) return;
            c.setWrongCount(0);
            c.setLockedAt(null);
            repository.save(c);
        });
    }

    /** Staff: the quiz's students locked out of the access code. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> lockedForQuiz(Long quizId) {
        return repository.findLockedForQuiz(quizId).stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("studentId", c.getStudent().getId());
            m.put("name", CurrentUserService.displayName(c.getStudent()));
            m.put("username", c.getStudent().getUsername());
            m.put("wrongCount", c.getWrongCount());
            m.put("lockedAt", c.getLockedAt());
            return m;
        }).toList();
    }

    /** Staff who manage the quiz let a locked-out student enter the code again (a fresh 10 tries). */
    @Transactional
    public Map<String, Object> allowAgain(User staff, Long quizId, Long studentId) {
        Quiz quiz = quizRepository.findById(quizId).orElseThrow(() -> new IllegalArgumentException("Quiz not found."));
        ExamAccess.requireQuiz(staff, quiz);
        userRepository.findById(studentId).orElseThrow(() -> new IllegalArgumentException("Student not found."));
        QuizCodeLock c = repository.findForUpdate(quizId, studentId)
                .orElseThrow(() -> new IllegalArgumentException("This student is not locked out of the access code."));
        c.setWrongCount(0);
        c.setLockedAt(null);
        c.setReopenedByName(CurrentUserService.displayName(staff));
        c.setReopenedAt(LocalDateTime.now());
        repository.save(c);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("studentId", studentId);
        out.put("allowed", true);
        return out;
    }
}
