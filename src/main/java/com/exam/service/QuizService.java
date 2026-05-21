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
import com.exam.model.User;
import com.exam.model.exam.*;
import com.exam.repository.*;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.Principal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    // ── Create ────────────────────────────────────────────────────────────────









    public Quiz addQuiz(Quiz quiz) {
        return quizRepository.save(quiz);
    }


    public Quiz lectureAddQuiz(Quiz quiz) {
        String username = SecurityContextHolder.getContext()
                .getAuthentication()
                .getName();

        User currentUser = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));

        quiz.setUser(currentUser);
        return quizRepository.save(quiz);
    }










    @Transactional
    public Quiz addQuizForLoggedInUser(Quiz quiz, Principal principal) {
        User user = resolveUser(principal);
        quiz.setUser(user);
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

        // active is a primitive boolean — always apply
        quiz.setActive(req.isActive());

        // ── Category ──────────────────────────────────────────────────────────
        if (req.getCategoryId() != null) {
            Category category = categoryRepository.findById(req.getCategoryId())
                    .orElseThrow(() -> new EntityNotFoundException("Category not found with id: " + req.getCategoryId()));
            quiz.setCategory(category);
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

        // 3. Delete reports
        reportRepository.deleteByQuiz(quiz);

        // 4. Finally delete the quiz itself
        quizRepository.delete(quiz);
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private User resolveUser(Principal principal) {
        return userRepository.findByUsername(principal.getName())
                .orElseThrow(() -> new EntityNotFoundException("User not found: " + principal.getName()));
    }
}