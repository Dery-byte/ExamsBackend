package com.exam.controller;


import com.exam.DTO.QuizDTO;
import com.exam.DTO.QuizUpdateRequest;
import com.exam.DTO.UpdateQuizStatusRequest;
import com.exam.helper.ResourceNotFoundException;
import com.exam.model.QuizStatus;
import com.exam.model.User;
import com.exam.model.exam.Category;
import com.exam.model.exam.Questions;
import com.exam.model.exam.Quiz;
import com.exam.repository.QuizRepository;
import com.exam.repository.ReportRepository;
import com.exam.service.QuizService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.*;

@RestController
@CrossOrigin(origins = "*")
//@RequestMapping("/quiz")
@RequestMapping("/api/v1/auth")
public class QuizController {
    @Autowired
    private QuizService quizService;

    @Autowired
    private QuizRepository quizRepository;


    @Autowired
    private final UserDetailsService userDetailsService;

    @Autowired
    @Lazy
    ReportRepository reportRepository;

    public QuizController(UserDetailsService userDetailsService) {
        this.userDetailsService = userDetailsService;
    }
    @GetMapping("/getQuizzes")
    public ResponseEntity<?> quizzes(){
        return ResponseEntity.ok(this.quizService.getQuizzes());
    }



    @PostMapping("/addQuiz")
    public ResponseEntity<Quiz> add(@RequestBody Quiz quiz ){
        return ResponseEntity.ok(this.quizService.addQuiz(quiz));
    }

    @PostMapping("/lecturer/addQuiz")
    public ResponseEntity<Quiz> LecturerAdd(@RequestBody Quiz quiz) {
        return ResponseEntity.ok(this.quizService.lectureAddQuiz(quiz));
    }

    @DeleteMapping("delete/quiz/{qid}")
    public  void delete(@PathVariable("qid") Long qid){
        this.quizService.deleteQuiz(qid);
    }



    @PutMapping("/update")
    public ResponseEntity<QuizDTO> updateQuiz(@RequestBody QuizUpdateRequest request) {
        QuizDTO updated = quizService.updateQuiz(request);
        return ResponseEntity.ok(updated);
    }

    //get Single quiz
    @GetMapping("singleQuiz/{qid}")
    public Quiz quiz (@PathVariable("qid") Long qid){
        return this.quizService.getQuiz(qid);
    }

    // get questions of any quiz
    @GetMapping("question/quiz/{qid}")
    public  ResponseEntity<?> getQuestionsOfQuiz(@PathVariable("qid") Long qid){
        Quiz quiz = this.quizService.getQuiz(qid);
        Set<Questions> questions = quiz.getQuestions();
        List<Questions> list = new ArrayList<>(questions);
        if(list.size()>quiz.getNumberOfQuestions()){
            list = list.subList(0, quiz.getNumberOfQuestions()+1);        }
        list.forEach((q)->{
//            q.setAnswer(new String[0]);
            q.setcorrect_answer(new String[0]);
//            q.setAnswer("");
        });
        Collections.shuffle(list);

        return ResponseEntity.ok(list);
    }

    //get specific question
    @GetMapping("/quiz/category/{cid}")
    public List<Quiz> getQuizzesOfCategory(@PathVariable("cid")  Long cid){
        Category category = new Category();
        category.setCid(cid);
        return (List<Quiz>) this.quizService.getQuizzesOfCategory(category);
    }


    //get Active quizzes
    @GetMapping("/active/quizzes")
    public List<Quiz> activeQuizzes(){
        return this.quizService.getActiveQuizzes();
    }

    /// Active quizzes of category

    @GetMapping("/category/active/{cid}")
    public List<Quiz> activeQuizzesOfCategory(@PathVariable("cid") Long cid){
        Category category =new Category();
        category.setCid(cid);
        return this.quizService.getActiveQuizzesofCategory(category);
    }

//    QUIZZES TAKEN BY STUDENTS
    @GetMapping("/category/taken/{cid}")
    public List<Quiz> quizzesTakenByStudents(@PathVariable("cid")  Long cid) {
        Category category = new Category();
        category.setCid(cid);
        return quizService.getTakenQuizzesOfCategory(category);
    }

//    QUIZZES TAKEN BY SPECIFIC STUDENTS

    @GetMapping("/category/takenByUser/{cid}")
    public List<Quiz> getTakenQuizzesByUser(
            @PathVariable Long cid,
            Principal principal
    ) {
        User user = (User) userDetailsService.loadUserByUsername(principal.getName());
        Long userId = user.getId();
        Category category = new Category();
        category.setCid(cid);
        return quizService.getTakenQuizzesOfCategoryByUser(userId, category);
    }

    @GetMapping("/category/taken/{cid}/user/{uid}")
    public List<Quiz> getTakenQuizzesByUsersss(
            @PathVariable Long cid,
            @PathVariable Long uid
    ) {
        Category category = new Category();
        category.setCid(cid);
        return quizService.getTakenQuizzesOfCategoryByUser(uid, category);
    }

    @PostMapping("/user/addQuiz")
    public Quiz addQuizForUser(@RequestBody Quiz quiz, Principal principal) {
        return quizService.addQuizForLoggedInUser(quiz, principal);
    }

    // ✅ Fetch quizzes for logged-in user
    @GetMapping("/user/getQuiz")
    public List<Quiz> getQuizzesForLoggedInUser(Principal principal) {
        return quizService.getQuizzesForLoggedInUser(principal);
    }

    @PutMapping("/quiz/status/{quizId}")
    public ResponseEntity<Map<String, String>> updateQuizStatus(
            @PathVariable Long quizId,
            @RequestBody UpdateQuizStatusRequest request) {
        Quiz quiz = quizRepository.findById(quizId)
                .orElseThrow(() -> new ResourceNotFoundException("Quiz not found with ID: " + quizId));
        // Validate and convert the status string
        QuizStatus newStatus;
        try {
            newStatus = QuizStatus.valueOf(request.getStatus().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid status"));
        }
        quiz.setStatus(newStatus);
        quizRepository.save(quiz);
        return ResponseEntity.ok(Map.of("status", quiz.getStatus().name()));
    }


























//    @GetMapping("/category/active/{cid}")
//    public List<Quiz> activeQuizzesOfCategory(@PathVariable("cid") Long cid){
//        Category category =new Category();
//        category.setCid(cid);
//        return this.quizService.getQuizzesOfCategory(category);
//    }







}
