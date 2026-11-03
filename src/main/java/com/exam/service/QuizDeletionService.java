package com.exam.service;

import com.exam.model.exam.Quiz;
import com.exam.repository.QuizRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Decides whether a quiz (or its questions, or its course) may be deleted, and deletes a quiz with
 * everything that hangs off it. A quiz "has results" once any student has an attempt, a report or a
 * saved answer on it; from then on its questions are locked and only the Super Admin may delete the
 * quiz itself, with a reason (see SuperAdminQuizController).
 */
@Service
public class QuizDeletionService {

    public static final String QUESTION_LOCKED =
            "Students have already taken this quiz, so this question can't be deleted. Their answers and marks depend on it.";
    public static final String QUIZ_LOCKED =
            "Students have already taken this quiz, so it can't be deleted. Their results would be lost. "
                    + "Unpublish it instead to hide it from students.";

    @PersistenceContext private EntityManager em;
    @Autowired private QuizRepository quizRepository;

    // ── Checks ───────────────────────────────────────────────────────────────

    /** Number of different students with an attempt, report or saved answer on this quiz. */
    public long studentsWithResults(Long quizId) {
        Set<Long> students = new HashSet<>();
        students.addAll(ids("SELECT DISTINCT a.user.id FROM QuizAttempt a WHERE a.quiz.qId = :id", quizId));
        students.addAll(ids("SELECT DISTINCT r.user.id FROM report r WHERE r.quiz.qId = :id AND r.user IS NOT NULL", quizId));
        students.addAll(ids("SELECT DISTINCT sa.user.id FROM StudentAnswer sa WHERE sa.question.quiz.qId = :id", quizId));
        students.addAll(ids("SELECT DISTINCT a.user.id FROM Answer a WHERE a.quiz.qId = :id", quizId));
        return students.size();
    }

    public boolean hasResults(Long quizId) {
        return studentsWithResults(quizId) > 0;
    }

    /** The quizzes among {@code quizIds} that have student results. */
    public Set<Long> quizIdsWithResults(Collection<Long> quizIds) {
        Set<Long> out = new HashSet<>();
        if (quizIds == null || quizIds.isEmpty()) return out;
        out.addAll(ids("SELECT DISTINCT a.quiz.qId FROM QuizAttempt a WHERE a.quiz.qId IN :id", quizIds));
        out.addAll(ids("SELECT DISTINCT r.quiz.qId FROM report r WHERE r.quiz.qId IN :id", quizIds));
        out.addAll(ids("SELECT DISTINCT sa.question.quiz.qId FROM StudentAnswer sa WHERE sa.question.quiz.qId IN :id", quizIds));
        out.addAll(ids("SELECT DISTINCT a.quiz.qId FROM Answer a WHERE a.quiz.qId IN :id", quizIds));
        return out;
    }

    /** The courses among {@code courseIds} with at least one quiz that has student results. */
    public Set<Long> courseIdsWithResults(Collection<Long> courseIds) {
        Set<Long> out = new HashSet<>();
        if (courseIds == null || courseIds.isEmpty()) return out;
        out.addAll(ids("SELECT DISTINCT a.quiz.category.cid FROM QuizAttempt a WHERE a.quiz.category.cid IN :id", courseIds));
        out.addAll(ids("SELECT DISTINCT r.quiz.category.cid FROM report r WHERE r.quiz.category.cid IN :id", courseIds));
        out.addAll(ids("SELECT DISTINCT sa.question.quiz.category.cid FROM StudentAnswer sa WHERE sa.question.quiz.category.cid IN :id", courseIds));
        out.addAll(ids("SELECT DISTINCT a.quiz.category.cid FROM Answer a WHERE a.quiz.category.cid IN :id", courseIds));
        return out;
    }

    /** Throws 409 when the question's quiz has student results. */
    public void requireQuestionDeletable(Quiz quiz) {
        if (quiz != null && hasResults(quiz.getqId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, QUESTION_LOCKED);
        }
    }

    /** Throws 409 when the quiz has student results. */
    public void requireQuizDeletable(Long quizId) {
        if (hasResults(quizId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, QUIZ_LOCKED);
        }
    }

    /** Throws 409 when any quiz in the course has student results. */
    public void requireCourseDeletable(Long courseId, List<Quiz> quizzes) {
        long withResults = quizIdsWithResults(quizzes.stream().map(Quiz::getqId).toList()).size();
        if (withResults > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Students have results in " + withResults + (withResults == 1 ? " quiz" : " quizzes")
                            + " in this course, so it can't be deleted. Their results would be lost.");
        }
    }

    // ── Delete ───────────────────────────────────────────────────────────────

    /**
     * Deletes the quiz and everything that refers to it: student answers and results, timers,
     * progress, proctoring events, unlocks, questions. Callers check {@link #requireQuizDeletable}
     * first unless the Super Admin is deleting results on purpose.
     */
    @Transactional
    public void purge(Long quizId) {
        String quizQuestions = "(SELECT q.quesId FROM Questions q WHERE q.quiz.qId = :id)";
        String quizTheory = "(SELECT t.TqId FROM TheoryQuestions t WHERE t.quiz.qId = :id)";
        String quizReports = "(SELECT r.id FROM report r WHERE r.quiz.qId = :id)";

        // Students' work first, then the questions it points at, then the quiz
        run("DELETE FROM StudentAnswer sa WHERE sa.question.quesId IN " + quizQuestions, quizId);
        run("DELETE FROM MatchingPair mp WHERE mp.question.quesId IN " + quizQuestions, quizId);
        run("DELETE FROM Answer a WHERE a.theoryQuestion.TqId IN " + quizTheory, quizId);
        run("DELETE FROM Answer a WHERE a.quiz.qId = :id", quizId);
        run("DELETE FROM RemarkRequest rr WHERE rr.report.id IN " + quizReports, quizId);
        run("DELETE FROM UserQuizAnswer ua WHERE ua.quizId = :id", quizId);
        run("DELETE FROM TheoryProgress tp WHERE tp.quiz.qId = :id", quizId);
        run("DELETE FROM QuizTimer qt WHERE qt.quiz.qId = :id", quizId);
        run("DELETE FROM ProctoringEvent pe WHERE pe.quiz.qId = :id", quizId);
        run("DELETE FROM QuizUnlock qu WHERE qu.quiz.qId = :id", quizId);
        run("DELETE FROM QuizAttempt a WHERE a.quiz.qId = :id", quizId);
        run("DELETE FROM report r WHERE r.quiz.qId = :id", quizId);
        run("DELETE FROM TheoryQuestions t WHERE t.quiz.qId = :id", quizId);
        run("DELETE FROM NumberOfTheoryToAnswer n WHERE n.quiz.qId = :id", quizId);
        run("DELETE FROM Questions q WHERE q.quiz.qId = :id", quizId);

        // Bulk deletes skip the persistence context; start clean so the quiz's cascades see no stale rows
        em.flush();
        em.clear();
        quizRepository.findById(quizId).ifPresent(quizRepository::delete);
    }

    private List<Long> ids(String jpql, Object id) {
        return em.createQuery(jpql, Long.class).setParameter("id", id).getResultList();
    }

    private void run(String jpql, Long quizId) {
        em.createQuery(jpql).setParameter("id", quizId).executeUpdate();
    }
}
