package com.exam.controller;

import com.exam.DTO.TheoryQuestionDTO;
import com.exam.DTO.TheoryQuestionResponseDTO;
import com.exam.DTO.TheoryUpdateRequest;
import com.exam.model.exam.Questions;
import com.exam.model.exam.Quiz;
import com.exam.model.exam.TheoryQuestions;
import com.exam.repository.QuizRepository;
import com.exam.service.TheoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.*;


@RestController
//@RequestMapping("/questions")
//@CrossOrigin(origins="https://assessmentapp-e1d04.web.app")
@CrossOrigin(origins = "*")
@RequestMapping("/api/v1/auth")
public class TheoryController {

    @Autowired
    private TheoryService theoryService;

    @Autowired
    QuizRepository quizRepository;


    @PostMapping("theoryquestion/add")
    public ResponseEntity<?> add(@RequestBody TheoryQuestions theoryQuestions) {
        // The frontend sends { quiz: { qId: X }, ... }
        // Jackson creates a transient Quiz with only qId set — that detached object
        // must be replaced with the managed entity from the DB before we can save.
        if (theoryQuestions.getQuiz() == null || theoryQuestions.getQuiz().getqId() == null) {
            return ResponseEntity.badRequest().body("quiz.qId is required");
        }
        Long quizId = theoryQuestions.getQuiz().getqId();
        Quiz managedQuiz = quizRepository.findById(quizId)
                .orElseThrow(() -> new RuntimeException("Quiz not found: " + quizId));
        theoryQuestions.setQuiz(managedQuiz);
        return ResponseEntity.ok(this.theoryService.addQuestions(theoryQuestions));
    }

    @GetMapping("theoryquestion/{quesId}")
    public TheoryQuestions getSpecificQuestionsOfQuizAdmin(@PathVariable("quesId") Long quesId) {
        return this.theoryService.getQuestions(quesId);
//        return ResponseEntity.ok(list);
    }

    //Delete Question
    @DeleteMapping("/theoryquestion/{quesId}")
    public void deleteQuestion(@PathVariable("quesId") Long quesId) {
        this.theoryService.deleteQuestion(quesId);
    }


//    //update Questions

    @PutMapping("/theoryquestion/updateQuestions")
    public TheoryQuestionDTO updateQuestion(@RequestBody TheoryUpdateRequest request) {
        return this.theoryService.updateQuestions(request);
    }

//    @GetMapping("theoryquestion/quiz/all/{qid}")
//    public ResponseEntity<?> getQuestionsOfQuizAdmin(@PathVariable("qid") Long qid) {
//        Quiz quiz = new Quiz();
//        quiz.setqId(qid);
//        Set<TheoryQuestions> questionsOfQuiz = this.theoryService.getQuestionsForSpecificQuiz(quiz);
//        List<TheoryQuestions> list = new ArrayList<>(questionsOfQuiz);
//        return ResponseEntity.ok(list);
//    }




    /**
     * STUDENT endpoint — used by the quiz-taking UI (Section B).
     *
     * Returns DTO list with:
     *  ✓ Only fields the frontend template needs
     *  ✓ Model answers / internal fields excluded
     *  ✓ givenAnswer null initially (textarea handles placeholder state)
     *
     * GET /api/theoryquestion/quiz/{qid}
     */
    @GetMapping("/theoryquestion/quiz/all/{qid}")
    public ResponseEntity<List<TheoryQuestionResponseDTO>> getTheoryQuestionsForStudent(
            @PathVariable("qid") Long qid) {
        List<TheoryQuestionResponseDTO> questions = theoryService.getTheoryQuestionsForStudent(qid);
        return ResponseEntity.ok(questions);
    }

    /**
     * ADMIN endpoint — returns full entity including any internal fields.
     * Should be secured with admin/teacher role.
     *
     * GET /api/theoryquestion/quiz/all/{qid}
     */
    @GetMapping("/theoryquestions/quiz/all/{qid}")
    // @PreAuthorize("hasRole('ADMIN')") // ← uncomment when Spring Security roles are configured
    public ResponseEntity<List<TheoryQuestions>> getQuestionsOfQuizAdmin(
            @PathVariable("qid") Long qid) {

        Quiz quiz = new Quiz();
        quiz.setqId(qid);

        List<TheoryQuestions> list = new ArrayList<>(theoryService.getQuestionsForSpecificQuiz(quiz));
        return ResponseEntity.ok(list);
    }



    @PostMapping("/theoryupload/{quizId}")
    public ResponseEntity<String> uploadQuestions(
            @PathVariable Long quizId,
            @RequestBody List<TheoryQuestions> theoryQuestions) {
        // Get the quiz entity from the database using the quizId
        Optional<Quiz> optionalQuiz = quizRepository.findById(quizId);
        if (optionalQuiz.isPresent()) {
            Quiz quiz = optionalQuiz.get();
//            int numberOfQuestions = Integer.parseInt(quiz.getNumberOfQuestions());
            // Set the quiz for each question
            theoryQuestions.forEach(question -> question.setQuiz(quiz));


//            if(theoryQuestions.size()<= numberOfQuestions)
//            {
            // Save questions
            List<TheoryQuestions> savedQuestions = theoryService.saveAllQuestions(theoryQuestions);
//                return new ResponseEntity<>("Uploaded " + savedQuestions.size() + " questions to the quiz with ID: " + quizId, HttpStatus.CREATED);
//            }
//            else{
//                return ResponseEntity.status( HttpStatus.BAD_REQUEST).body("Number of questions should be " + numberOfQuestions+ " but you provided " + theoryQuestions.size());
//            }
//        } else {
//            return new ResponseEntity<>("Quiz with ID " + quizId +  " not found.", HttpStatus.NOT_FOUND);
//        }
        }
        return ResponseEntity.status(HttpStatus.CREATED).body("Questions saved successfully");
    }
























//SETTING COMPULSORY CONTROLLER
    // In your TheoryQuestionsController
    @PutMapping("/update-compulsory/{quizId}/{prefix}")
    public ResponseEntity<?> updateCompulsoryByPrefix(
            @PathVariable Long quizId,
            @PathVariable String prefix,
            @RequestParam Boolean isCompulsory) {
        try {
            theoryService.updateCompulsoryStatusByPrefix(quizId, prefix, isCompulsory);
            return ResponseEntity.ok("Compulsory status updated successfully");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Error updating compulsory status");
        }
    }
}
