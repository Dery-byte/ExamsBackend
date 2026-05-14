//package com.exam.controller;
//
//import com.exam.DTO.*;
//import com.exam.model.User;
//import com.exam.model.exam.Questions;
//import com.exam.model.exam.Quiz;
//import com.exam.model.exam.Report;
//import com.exam.model.exam.StudentAnswer;
//import com.exam.repository.QuestionsRepository;
//import com.exam.repository.QuizRepository;
//import com.exam.repository.ReportRepository;
//import com.exam.repository.StudentAnswerRepository;
//import com.exam.service.BulkQuestionUploadService;
//import com.exam.service.Impl.QuizEvaluationService;
//import com.exam.service.QuestionsService;
//import com.exam.service.QuizService;
//import com.exam.service.ReportService;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.context.annotation.Lazy;
//import org.springframework.http.HttpStatus;
//import org.springframework.http.ResponseEntity;
//import org.springframework.security.core.userdetails.UserDetailsService;
//import org.springframework.transaction.annotation.Transactional;
//import org.springframework.web.bind.annotation.*;
//
//import java.math.BigDecimal;
//import java.security.Principal;
//import java.util.*;
//
//@RestController
////@RequestMapping("/questions")
////@CrossOrigin(origins="https://assessmentapp-e1d04.web.app")
//@CrossOrigin(origins = "*")
//
//@RequestMapping("/api/v1/auth")
//public class QuestionsController {
//    @Autowired
//    @Lazy
//    private QuestionsService questionsService;
//    @Autowired
//    @Lazy
//    private ReportRepository reportRepository;
//    @Autowired
//    @Lazy
//    private ReportService reportService;
//
//    private final BulkQuestionUploadService bulkQuestionUploadService;
//
//
//    @Autowired
//    @Lazy
//    private QuizRepository quizRepository;
//    @Autowired
//    private QuizService quizService;
//
//    @Autowired
//    private QuizEvaluationService quizEvaluationService;
//
//    @Autowired
//    private StudentAnswerRepository studentAnswerRepository;
//
//    @Autowired
//    private QuestionsRepository questionsRepository;
//
//
//    @Autowired
//    private final UserDetailsService userDetailsService;
//    public QuestionsController(BulkQuestionUploadService bulkQuestionUploadService, UserDetailsService userDetailsService) {
//        this.bulkQuestionUploadService = bulkQuestionUploadService;
//        this.userDetailsService = userDetailsService;
//    }
//
//
//
//
//    // get questions of any quiz (Student)
//    @GetMapping("questionSSS/quiz/all/{qid}")
//    public  ResponseEntity<?> getQuestionsOfQuizAdmin(@PathVariable("qid") Long qid){
//        Quiz quiz = new Quiz();
//        quiz.setqId(qid);
//        Set<Questions> questionsOfQuiz =this.questionsService.getQuestionsOfQuiz(quiz);
//        List<Questions> list = new ArrayList<>(questionsOfQuiz);
//Collections.shuffle(list);
//        return ResponseEntity.ok(list);
//    }
//
//
//
//
////    THIS CODE BELOW RETURNS DTOs FOR THE STUDENT TO ANSWER THE QUESSTIONS
//    @GetMapping("/question/quiz/all/{qid}")
//    public ResponseEntity<List<QuestionResponseDTO>> getQuestionsForStudent(
//            @PathVariable("qid") Long qid) {
//
//        List<QuestionResponseDTO> questions = questionsService.getShuffledQuestionsForStudent(qid);
//        return ResponseEntity.ok(questions);
//    }
//
//    // FETCH QUESTIONS FOR A LECTURER
//    @GetMapping("/questions/quiz/all/{quizId}")
//    public List<Questions> getMyQuizQuestions(
//            @PathVariable Long quizId,
//            Principal principal) {
//        List<Questions> questionsOfQuiz = this.questionsService.getQuestionsForMyQuiz(quizId, principal);
////        List<Questions> list = new ArrayList<>(questionsOfQuiz);
//        Collections.shuffle(questionsOfQuiz);
//        return ResponseEntity.ok(questionsOfQuiz).getBody();
//    }
//
//
//
//
//
//
//    //GET RANDOM QUESTIONS AND LIMITED
//    @GetMapping("/random-records")
//    public ResponseEntity<List<Questions>> getRandomRecords() {
//        return this.questionsService.getRandomRecords();
//    }
//
////}
//    @GetMapping("question/{quesId}")
//    public  Questions getSpecificQuestionsOfQuizAdmin(@PathVariable("quesId") Long quesId ){
//       return this.questionsService.getQuestions(quesId);
////        return ResponseEntity.ok(list);
//    }
//
//    //add question
//    @PostMapping("question/add")
//    public ResponseEntity<Questions> add(@RequestBody Questions questions){
//        return ResponseEntity.ok(this.questionsService.addQuestions(questions));
//    }
//
//
//
//
//
//    //Delete Question
//    @DeleteMapping("/question/{quesId}")
//    public void deleteQuestion(@PathVariable("quesId") Long quesId){
//        this.questionsService.deleteQuestion(quesId);
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
////    //update Questions
////    @PutMapping("/question/updateQuestions")
////    public Questions updateQuestion(@RequestBody Questions questions){
////        return  this.questionsService.updateQuestions(questions);
////    }
//
//
//    @PutMapping("/question/updateQuestions")
//    public ResponseEntity<QuestionDTO> updateQuestion(
//            @RequestBody UpdateQuestionDTO dto) {
//        return ResponseEntity.ok(
//                questionsService.updateQuestion(dto)
//        );
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
//
//
//    @PostMapping("question/eval-quiz/{qid}")
//@Transactional
//public ResponseEntity<?> evalQuiz2(@RequestBody List<QuestionEvalRequest> questions,
//                                   Principal principal,
//                                   @PathVariable("qid") Long qid) {
//    if (principal == null || qid == null) {
//        return ResponseEntity.badRequest().body("Principal or quiz ID is null"); }
//    User user = (User) this.userDetailsService.loadUserByUsername(principal.getName());
//    Quiz quiz = this.quizService.getQuiz(qid);
//    if (user == null || quiz == null) {
//        return ResponseEntity.badRequest().body("User or quiz not found");
//    }
//    double marksGot = 0.0;
//    int correctAnswers = 0;
//    int attempted = 0;
//    double maxMarks = quiz.getMaxMarks();
//    List<Map<String, Object>> resultList = new ArrayList<>();
//    for (QuestionEvalRequest q : questions) {
//        if (q == null) continue;
//        Questions question = this.questionsService.get(q.getQuesId());
//        if (question == null) continue;
//        // Correct and given answers
//        List<String> correctAnswersList = question.getcorrect_answer() != null
//                ? Arrays.asList(question.getcorrect_answer())
//                : new ArrayList<>();
//        List<String> givenAnswersList = q.getGivenAnswer() != null
//                ? Arrays.asList(q.getGivenAnswer())
//                : new ArrayList<>();
//        // Check correctness
//        boolean isCorrect = false;
//        if (!givenAnswersList.isEmpty()) {
//            attempted++;
//            Collections.sort(correctAnswersList);
//            Collections.sort(givenAnswersList);
//            isCorrect = correctAnswersList.equals(givenAnswersList);
//            if (isCorrect) {
//                correctAnswers++;
//                marksGot += maxMarks / questions.size();
//            }
//        }
//
//        // Save student answer
//        StudentAnswer studentAnswer = new StudentAnswer();
//        studentAnswer.setUser(user); // map User -> Student
//        studentAnswer.setQuestion(question);
//        studentAnswer.setSelectedOptions(q.getGivenAnswer()); // null if skipped
//        studentAnswerRepository.save(studentAnswer);
//
//        // Prepare response entry
//        Map<String, Object> questionMap = new HashMap<>();
//        questionMap.put("quesId", question.getQuesId());
//        questionMap.put("content", question.getContent());
//        questionMap.put("image", question.getImage());
//        questionMap.put("option1", question.getOption1());
//        questionMap.put("option2", question.getOption2());
//        questionMap.put("option3", question.getOption3());
//        questionMap.put("option4", question.getOption4());
//        questionMap.put("correct_answer", question.getcorrect_answer());
//        questionMap.put("selectedAnswers", q.getGivenAnswer()); // student answer
//        questionMap.put("isCorrect", isCorrect);
//        questionMap.put("quiz", quiz); // optional, can be serialized as is
//        resultList.add(questionMap);
//    }
//    // Save report
//    Report report = new Report();
//    report.setQuiz(quiz);
//    report.setUser(user);
//    report.setProgress("Completed");
//    report.setMarks(BigDecimal.valueOf(marksGot));
//    report.setMarksB(BigDecimal.valueOf(0));
//    reportService.addReport(report);
//    // Final JSON response
//    Map<String, Object> response = new HashMap<>();
//    response.put("studentId", user.getId());
//    response.put("quizId", quiz.getqId());
//    response.put("marksGot", marksGot);
//    response.put("correctAnswers", correctAnswers);
//    response.put("attempted", attempted);
//    response.put("maxMarks", maxMarks);
//    response.put("results", resultList);
//
//    return ResponseEntity.ok(response);
//}
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
//    private boolean isGivenAnswerAttempted(List<String> givenAnswersList) {
//        if (givenAnswersList != null && !givenAnswersList.isEmpty()) {
//            for (String answer : givenAnswersList) {
//                if (answer != null && !answer.trim().isEmpty()) {
//                    return true;
//                }
//            }
//        }
//        return false;
//    }
//
//
////  UPLOADING QUESTIONS
//@PostMapping("/upload/{quizId}")
//public ResponseEntity<String> uploadQuestions(
//        @PathVariable Long quizId,
//        @RequestBody List<Questions> questions) {
//    // Get the quiz entity from the database using the quizId
//    Optional<Quiz> optionalQuiz = quizRepository.findById(quizId);
//    if (optionalQuiz.isPresent()) {
//        Quiz quiz = optionalQuiz.get();
//        int numberOfQuestions = quiz.getNumberOfQuestions();
//        // Set the quiz for each question
//        questions.forEach(question -> question.setQuiz(quiz));
//        if(questions.size()<= numberOfQuestions)
//        {
//            // Save questions
//            List<Questions> savedQuestions = questionsService.saveAllQuestions(questions);
//            return new ResponseEntity<>("Uploaded " + savedQuestions.size() + " questions to the quiz with ID: " + quizId, HttpStatus.CREATED);
//        }
//        else{
//            return ResponseEntity.status( HttpStatus.BAD_REQUEST).body("Number of questions should be " + numberOfQuestions+ " but you provided " + questions.size());
//        }
//   } else {
//        return new ResponseEntity<>("Quiz with ID " + quizId +  " not found.", HttpStatus.NOT_FOUND);
//    }
//}
//
////    UPLOADING QUESTIONS
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
//
//
//
//
//
//
//
//
//
//// THIS FUNCTION SET THE PROGRESS TO UNFINISHED AFTER CONFIRMING A CORRECT PASSWORD
////    @PostMapping("question/add-quizUserId/{qid}")
////    public  String addUserIdAndQuizId(Principal principal, @PathVariable("qid") Long qid) {
//////        System.out.println(questions);
////        User user = (User) this.userDetailsService.loadUserByUsername(principal.getName());
////        Quiz quiz = this.quizService.getQuiz(qid);
////        double marksGot = 0.0;
//////        int correctAnswers = 0;
//////        int attempted = 0;
////////        double maxMarks =0;
//////        double maxMarks = 0;
//////        for (Questions q : questions) {
////////            System.out.println(questions1.getGivenAnswer());
//////            maxMarks =  Double.parseDouble(questions.get(0).getQuiz().getMaxMarks());
//////            //Single question
//////            Questions question = this.questionsService.get(q.getQuesId());
//////            if (question.getAnswer().equals(q.getGivenAnswer())) {
//////                //correct
//////                correctAnswers++;
//////                double marksSingle =  (Double.parseDouble(questions.get(0).getQuiz().getMaxMarks()) / (double) questions.size());
//////                //this.questions[0].quiz.maxMarks/this.questions.length;
//////                marksGot += marksSingle;
//////            }
//////            if (q.getGivenAnswer() != "") {
//////                attempted++;
//////            }
//////        };
////        Report report = new Report();
////        report.setQuiz(quiz);
////        report.setUser(user);
////        report.setProgress("Unfinished");
////        report.setMarks(BigDecimal.valueOf(Double.parseDouble(String.valueOf(((marksGot))))));
//////        report.setMarks(marksGot);
//////        report.setMarks(Double.parseDouble(String.valueOf(marksGot)));
////        reportService.addReport(report);
//////        Map<String, Object> map = Map.of("marksGot", marksGot, "correctAnswers", correctAnswers, "attempted", attempted, "maxMarks", maxMarks);
////        return "User ID and Quiz ID saved";
////    }
//
//
//
//
//
//
//
//
////    @PostMapping("/addReports/{qid}")
////    public ResponseEntity<Report> addReports(@RequestBody Report report, Principal principal, @PathVariable("qid") Long qid, @PathVariable("ques_id") Long ques_id){
////        User user = (User) this.userDetailsService.loadUserByUsername(principal.getName());
//////        List<Questions> questions = (List<Questions>) this.questionsService.getQuestions(ques_id);
////        Quiz quiz = this.quizService.getQuiz(qid);
////        //        report.setMarks(90);
////        report.setQuiz(quiz);
////        report.setUser(user);
//////         report.setMarks(user.getId());
////        return ResponseEntity.ok(this.reportService.AddReport(report));
////    }
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
////
////    @Autowired
////    private QuizService quizService;
////
////    //add question
////    @PostMapping("/add")
////    public ResponseEntity<Questions> add(@RequestBody Questions questions){
////        return ResponseEntity.ok(this.questionsService.addQuestions(questions));
////    }
////
////    // update question
////    @PutMapping("/")
////    public ResponseEntity<Questions> update(@RequestBody Questions questions){
////        return ResponseEntity.ok(this.questionsService.updateQuestions(questions));
////    }
////
////    // get questions of any quiz
////    @GetMapping("/quiz/{qid}")
////    public  ResponseEntity<?> getQuestionsOfQuiz(@PathVariable("qid") Long qid){
//////
//////        Quiz quiz = new Quiz();
//////        quiz.setqId(qid);
//////        Set<Questions> questionsOfQuiz =this.questionsService.getQuestionsOfQuiz(quiz);
//////        return ResponseEntity.ok(questionsOfQuiz);
////
////Quiz quiz = this.quizService.getQuiz(qid);
////Set<Questions> questions = quiz.getQuestions();
////List<Questions> list = new ArrayList<>(questions);
////
////if(list.size()>Integer.parseInt(quiz.getNumberOfQuestions())){
////    list = list.subList(0, Integer.parseInt(quiz.getNumberOfQuestions()+1));
////}
////
////
////list.forEach((q)->{
////    q.setAnswer("");
////});
////        Collections.shuffle(list);
////return ResponseEntity.ok(list);
////    }
////
////    //get suingle question
////    @GetMapping("/{quesId}")
////    public Questions get(@PathVariable("quesId") Long quesId )
////    {
////        return this.questionsService.getQuestions(quesId);
////    }
////
////    @DeleteMapping("/{quesId}")
////    public void delete(@PathVariable("quesId") Long quesId){
////        this.questionsService.deleteQuestion(quesId);
////    }
////
////    //evaluate Quiz
////    @PostMapping("/eval-quiz")
////    public  ResponseEntity<?> evalQuiz(@RequestBody List<Questions> questions){
////        System.out.println(questions);
////        return ResponseEntity.ok("Got questions with answers!");
////
////    }
//
//
//@PostMapping("/eval-quiz/{qid}")
//public ResponseEntity<?> evalQuiz(
//        @RequestBody List<Questions> questions,
//        Principal principal,
//        @PathVariable Long qid
//) {
//
//    if (principal == null || qid == null) {
//        return ResponseEntity.badRequest()
//                .body("Principal or quiz ID is null");
//    }
//
//    QuizEvaluationResult result =
//            quizEvaluationService.evaluateQuiz(
//                    questions,
//                    principal.getName(),
//                    qid
//            );
//
//    return ResponseEntity.ok(result);
//}
//
//
//
//}






package com.exam.controller;

import com.exam.DTO.*;
import com.exam.model.User;
import com.exam.model.exam.*;
import com.exam.repository.*;
import com.exam.service.BulkQuestionUploadService;
import com.exam.service.Impl.QuizEvaluationService;
import com.exam.service.QuestionsService;
import com.exam.service.QuizService;
import com.exam.service.ReportService;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.security.Principal;
import java.util.*;

@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/api/v1/auth")
public class QuestionsController {

    @Autowired @Lazy private QuestionsService       questionsService;
    @Autowired @Lazy private ReportRepository       reportRepository;
    @Autowired @Lazy private ReportService          reportService;
    @Autowired @Lazy private QuizRepository         quizRepository;
    @Autowired           private QuizService         quizService;
    @Autowired           private QuizEvaluationService quizEvaluationService;
    @Autowired           private StudentAnswerRepository studentAnswerRepository;
    @Autowired           private QuestionsRepository  questionsRepository;

    private final BulkQuestionUploadService bulkQuestionUploadService;
    private final UserDetailsService        userDetailsService;

    @Autowired
    public QuestionsController(BulkQuestionUploadService bulkQuestionUploadService,
                               UserDetailsService userDetailsService) {
        this.bulkQuestionUploadService = bulkQuestionUploadService;
        this.userDetailsService        = userDetailsService;
    }

    // =========================================================================
    // READ — Questions
    // =========================================================================

    /** Student: shuffled questions as DTOs (no correct answers exposed) */
    @GetMapping("/question/quiz/all/{qid}")
    public ResponseEntity<List<QuestionResponseDTO>> getQuestionsForStudent(
            @PathVariable Long qid) {
        return ResponseEntity.ok(questionsService.getShuffledQuestionsForStudent(qid));
    }





    /** Lecturer: questions for their own quiz correct Answers Exposed*/
    @GetMapping("/questions/quiz/all/{quizId}")
    public ResponseEntity<List<Questions>> getMyQuizQuestions(
            @PathVariable Long quizId,
            Principal principal) {
        List<Questions> questions = questionsService.getQuestionsForMyQuiz(quizId, principal);
        Collections.shuffle(questions);
        return ResponseEntity.ok(questions);
    }



    @GetMapping("/questionAdmin/quiz/all/{qid}")
    public ResponseEntity<List<QuestionResponseAdminDTO>> getQuestions(
            @PathVariable Long qid) {
        return ResponseEntity.ok(questionsService.getNoneQuestionsForAdmin(qid));
    }




    /** Admin: all questions for any quiz (raw, shuffled) */
    @GetMapping("/questionSSS/quiz/all/{qid}")
    public ResponseEntity<List<Questions>> getQuestionsOfQuizAdmin(
            @PathVariable Long qid) {
        Quiz quiz = new Quiz();
        quiz.setqId(qid);
        List<Questions> list = new ArrayList<>(questionsService.getQuestionsOfQuiz(quiz));
        Collections.shuffle(list);
        return ResponseEntity.ok(list);
    }





    /** Single question by ID */
//    @GetMapping("/question/{quesId}")
//    public ResponseEntity<Questions> getQuestion(@PathVariable Long quesId) {
//        return ResponseEntity.ok(questionsService.getQuestions(quesId));
//    }


    // Controller
    @GetMapping("/question/{quesId}")
    public ResponseEntity<SingleQuestionSummaryDTO> getQuestion(@PathVariable Long quesId) {
        return ResponseEntity.ok(questionsService.getQuestionSummary(quesId));
    }

    /** Random sample (2 records) */
    @GetMapping("/random-records")
    public ResponseEntity<List<Questions>> getRandomRecords() {
        return questionsService.getRandomRecords();
    }

    // =========================================================================
    // CREATE — Single question
    // =========================================================================

    /**
     * Add a single question.
     * For MATCHING questions the matchingPairs FK is linked here before save.
     */
    @PostMapping("/question/add")
    public ResponseEntity<Questions> addQuestion(@RequestBody Questions question) {
        if (question.getQuestionType() == null) {
            question.setQuestionType(QuestionType.MCQ);
        }
        if (question.getQuestionType() == QuestionType.MATCHING
                && question.getMatchingPairs() != null) {
            question.getMatchingPairs().forEach(pair -> pair.setQuestion(question));
        }
        return ResponseEntity.ok(questionsService.addQuestions(question));
    }

    // =========================================================================
    // CREATE — Bulk upload
    // =========================================================================

    /**
     * Bulk upload questions for a quiz.
     *
     * Accepts all 3 question types. Format:
     *
     * MCQ (single or multi):
     * {
     *   "questionType": "MCQ",
     *   "content": "...",
     *   "option1": "...", "option2": "...", "option3": "...", "option4": "...",
     *   "correct_answer": ["option text"]
     * }
     *
     * TRUE_FALSE:
     * {
     *   "questionType": "TRUE_FALSE",
     *   "content": "...",
     *   "option1": "True", "option2": "False",
     *   "correct_answer": ["True"]   // or ["False"]
     * }
     *
     * MATCHING:
     * {
     *   "questionType": "MATCHING",
     *   "content": "...",
     *   "matchingPairs": [
     *     { "prompt": "200",  "answer": "OK",        "pairOrder": 0 },
     *     { "prompt": "404",  "answer": "Not Found", "pairOrder": 1 }
     *   ]
     * }
     */
    @PostMapping("/upload/{quizId}")
    public ResponseEntity<String> uploadQuestions(
            @PathVariable Long quizId,
            @RequestBody List<Questions> questions) {

        Quiz quiz = quizRepository.findById(quizId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Quiz with ID " + quizId + " not found"));

        int allowed = quiz.getNumberOfQuestions();
        if (questions.size() > allowed) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                    "Quiz allows " + allowed + " questions but you provided " + questions.size());
        }

        // Link every question to the quiz
        questions.forEach(q -> q.setQuiz(quiz));

        // BulkQuestionUploadService handles type validation + matchingPairs FK linking
        List<Questions> saved = bulkQuestionUploadService.saveAllQuestions(questions);

        return ResponseEntity.status(HttpStatus.CREATED).body(
                "Uploaded " + saved.size() + " questions to quiz ID: " + quizId);
    }

    // =========================================================================
    // UPDATE
    // =========================================================================

    @PutMapping("/question/updateQuestions")
    public ResponseEntity<QuestionDTO> updateQuestion(@RequestBody UpdateQuestionDTO dto) {
        return ResponseEntity.ok(questionsService.updateQuestion(dto));
    }

    // =========================================================================
    // DELETE
    // =========================================================================

    @DeleteMapping("/question/{quesId}")
    public ResponseEntity<Void> deleteQuestion(@PathVariable Long quesId) {
        questionsService.deleteQuestion(quesId);
        return ResponseEntity.noContent().build();
    }

    // =========================================================================
    // EVALUATE — via QuizEvaluationService (clean path)
    // =========================================================================

    @PostMapping("/eval-quiz/{qid}")
    public ResponseEntity<?> evalQuiz(
            @RequestBody List<Questions> questions,
            Principal principal,
            @PathVariable Long qid) {

        if (principal == null || qid == null) {
            return ResponseEntity.badRequest().body("Principal or quiz ID is null");
        }
        QuizEvaluationResult result =
                quizEvaluationService.evaluateQuiz(questions, principal.getName(), qid);
        return ResponseEntity.ok(result);
    }

    // =========================================================================
    // EVALUATE — inline detailed path (returns per-question result breakdown)
    // Supports MCQ, TRUE_FALSE (all-or-nothing) and MATCHING (per-pair partial)
    // =========================================================================

//
//
//    @PostMapping("/question/eval-quiz/{qid}")
//    @Transactional
//    public ResponseEntity<?> evalQuizDetailed(
//            @RequestBody List<QuestionEvalRequest> questions,
//            Principal principal,
//            @PathVariable Long qid) {
//
//        if (principal == null || qid == null) {
//            return ResponseEntity.badRequest().body("Principal or quiz ID is null");
//        }
//
//        User user = (User) userDetailsService.loadUserByUsername(principal.getName());
//        Quiz quiz = quizService.getQuiz(qid);
//
//        if (user == null || quiz == null) {
//            return ResponseEntity.badRequest().body("User or quiz not found");
//        }
//
//        double maxMarks      = quiz.getMaxMarks();
//        int    totalQs       = questions.size();
//        double markPerQuestion = maxMarks / totalQs;
//
//        double marksGot       = 0.0;
//        int    correctAnswers = 0;   // fully-correct question count
//        int    attempted      = 0;
//
//        List<Map<String, Object>> resultList = new ArrayList<>();
//
//        for (QuestionEvalRequest req : questions) {
//            if (req == null) continue;
//            Questions persisted = questionsService.get(req.getQuesId());
//            if (persisted == null) continue;
//            List<String> givenList = req.getGivenAnswer() != null
//                    ? Arrays.asList(req.getGivenAnswer())
//                    : new ArrayList<>();
//            if (isAttempted(givenList)) attempted++;
//            QuestionType type = persisted.getQuestionType() != null
//                    ? persisted.getQuestionType()
//                    : QuestionType.MCQ;
//            boolean fullyCorrect = false;
//            double  earnedMark   = 0.0;
//            int     pairsCorrect = 0;
//            int     pairsTotal   = 0;
//            if (type == QuestionType.MATCHING) {
//                // ── Per-pair partial scoring ──────────────────────────────────
//                List<MatchingPair> pairs = persisted.getMatchingPairs();
//                pairsTotal = pairs != null ? pairs.size() : 0;
//                if (pairsTotal > 0 && !givenList.isEmpty()) {
//                    String[] given = req.getGivenAnswer();
//                    for (int i = 0; i < pairs.size(); i++) {
//                        String expected = pairs.get(i).getAnswer();
//                        String student  = i < given.length ? given[i] : null;
//                        if (expected != null && expected.equals(student)) pairsCorrect++;
//                    }
//                    earnedMark   = ((double) pairsCorrect / pairsTotal) * markPerQuestion;
//                    fullyCorrect = pairsCorrect == pairsTotal;
//                }
//            } else {
//                // ── MCQ / TRUE_FALSE: all-or-nothing ──────────────────────────
//                List<String> correctList = persisted.getcorrect_answer() != null
//                        ? new ArrayList<>(Arrays.asList(persisted.getcorrect_answer()))
//                        : new ArrayList<>();
//                List<String> sortedGiven = new ArrayList<>(givenList);
//                Collections.sort(correctList);
//                Collections.sort(sortedGiven);
//
//                if (!givenList.isEmpty() && correctList.equals(sortedGiven)) {
//                    fullyCorrect = true;
//                    earnedMark   = markPerQuestion;
//                }
//            }
//
//            if (fullyCorrect) correctAnswers++;
//            marksGot += earnedMark;
//
//            // Save student answer
//            StudentAnswer studentAnswer = new StudentAnswer();
//            studentAnswer.setUser(user);
//            studentAnswer.setQuestion(persisted);
//            studentAnswer.setSelectedOptions(req.getGivenAnswer());
//            studentAnswerRepository.save(studentAnswer);
//
//            // Build per-question result entry
//            Map<String, Object> entry = new LinkedHashMap<>();
//            entry.put("quesId",          persisted.getQuesId());
//            entry.put("content",         persisted.getContent());
//            entry.put("image",           persisted.getImage());
//            entry.put("questionType",    type);
//            entry.put("selectedAnswers", req.getGivenAnswer());
//            entry.put("isCorrect",       fullyCorrect);
//            entry.put("earnedMark",      earnedMark);
//            entry.put("markAvailable",   markPerQuestion);
//            if (type == QuestionType.MATCHING) {
//                entry.put("pairsCorrect", pairsCorrect);
//                entry.put("pairsTotal",   pairsTotal);
//            } else {
//                entry.put("option1",         persisted.getOption1());
//                entry.put("option2",         persisted.getOption2());
//                entry.put("option3",         persisted.getOption3());
//                entry.put("option4",         persisted.getOption4());
//                entry.put("correct_answer",  persisted.getcorrect_answer());
//            }
//            resultList.add(entry);
//        }
//
//        // Save / update report
//        Report report = reportRepository.findByUserAndQuiz(user, quiz).orElse(new Report());
//        report.setQuiz(quiz);
//        report.setUser(user);
//        report.setProgress("Completed");
//        report.setMarks(BigDecimal.valueOf(marksGot));
//        report.setMarksB(BigDecimal.ZERO);
//        reportService.addReport(report);
//        // Build summary response
//        Map<String, Object> response = new LinkedHashMap<>();
//        response.put("studentId",      user.getId());
//        response.put("quizId",         quiz.getqId());
//        response.put("marksGot",       marksGot);
//        response.put("maxMarks",       maxMarks);
//        response.put("correctAnswers", correctAnswers);
//        response.put("attempted",      attempted);
//        response.put("results",        resultList);
//        return ResponseEntity.ok(response);
//    }
//




    @PostMapping("/question/eval-quiz/{qid}")
    @Transactional
    public ResponseEntity<?> evalQuizDetailed(
            @RequestBody List<QuestionEvalRequest> questions,
            Principal principal,
            @PathVariable Long qid) {

        if (principal == null || qid == null) {
            return ResponseEntity.badRequest().body("Principal or quiz ID is null");
        }

        User user = (User) userDetailsService.loadUserByUsername(principal.getName());
        Quiz quiz = quizService.getQuiz(qid);

        if (user == null || quiz == null) {
            return ResponseEntity.badRequest().body("User or quiz not found");
        }

        double maxMarks        = quiz.getMaxMarks();
        int    totalQs         = questions.size();
        double markPerQuestion = totalQs > 0 ? maxMarks / totalQs : 0;

        double marksGot       = 0.0;
        int    correctAnswers = 0;
        int    attempted      = 0;

        List<Map<String, Object>> resultList = new ArrayList<>();

        for (QuestionEvalRequest req : questions) {
            if (req == null) continue;

            Questions persisted = questionsService.get(req.getQuesId());
            if (persisted == null) continue;

            List<String> givenList = req.getGivenAnswer() != null
                    ? Arrays.asList(req.getGivenAnswer())
                    : new ArrayList<>();

            if (isAttempted(givenList)) attempted++;

            QuestionType type = persisted.getQuestionType() != null
                    ? persisted.getQuestionType()
                    : QuestionType.MCQ;

            boolean fullyCorrect = false;
            double  earnedMark   = 0.0;
            int     pairsCorrect = 0;
            int     pairsTotal   = 0;

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("quesId",        persisted.getQuesId());
            entry.put("content",       persisted.getContent());
            entry.put("image",         persisted.getImage());
            entry.put("questionType",  type);
            entry.put("markAvailable", markPerQuestion);   // always present for frontend pill

            // ── MATCHING ─────────────────────────────────────────────────────────
            if (type == QuestionType.MATCHING) {

                List<MatchingPair> pairs = persisted.getMatchingPairs();
                pairsTotal = pairs != null ? pairs.size() : 0;

                // Build per-pair detail array (used by frontend table)
                List<Map<String, Object>> pairResults = new ArrayList<>();

                if (pairsTotal > 0) {
                    String[] given = req.getGivenAnswer() != null ? req.getGivenAnswer() : new String[0];

                    for (int i = 0; i < pairs.size(); i++) {
                        MatchingPair pair     = pairs.get(i);
                        String       expected = pair.getAnswer();
                        String       student  = i < given.length ? given[i] : null;
                        boolean      correct  = expected != null && expected.equals(student);

                        if (correct) pairsCorrect++;

                        // Per-pair detail entry for frontend rendering
                        Map<String, Object> pairMap = new LinkedHashMap<>();
                        pairMap.put("pairOrder",     pair.getPairOrder());
                        pairMap.put("prompt",        pair.getPrompt());
                        pairMap.put("correctAnswer", expected);
                        pairMap.put("studentAnswer", student);
                        pairMap.put("correct",       correct);
                        pairResults.add(pairMap);
                    }

                    earnedMark   = ((double) pairsCorrect / pairsTotal) * markPerQuestion;
                    fullyCorrect = pairsCorrect == pairsTotal;
                }

                // Determine AnswerStatus for MATCHING
                String status;
                if (givenList.isEmpty()) {
                    status = "SKIPPED";
                } else if (fullyCorrect) {
                    status = "CORRECT";
                } else if (pairsCorrect > 0) {
                    status = "PARTIAL";
                } else {
                    status = "WRONG";
                }

                entry.put("matchingPairs",  pairResults);   // per-pair breakdown
                entry.put("pairsCorrect",   pairsCorrect);
                entry.put("pairsTotal",     pairsTotal);
                entry.put("selectedAnswers", req.getGivenAnswer());
                entry.put("earnedMark",     earnedMark);
                entry.put("isCorrect",      fullyCorrect);
                entry.put("status",         status);

                // ── MCQ / TRUE_FALSE ─────────────────────────────────────────────────
            } else {

                List<String> correctList = persisted.getcorrect_answer() != null
                        ? new ArrayList<>(Arrays.asList(persisted.getcorrect_answer()))
                        : new ArrayList<>();
                List<String> sortedGiven = new ArrayList<>(givenList);
                Collections.sort(correctList);
                Collections.sort(sortedGiven);

                if (!givenList.isEmpty() && correctList.equals(sortedGiven)) {
                    fullyCorrect = true;
                    earnedMark   = markPerQuestion;
                }
                // Determine AnswerStatus for MCQ / TRUE_FALSE
                String status;
                if (givenList.isEmpty()) {
                    status = "SKIPPED";
                } else if (fullyCorrect) {
                    status = "CORRECT";
                } else {
                    // Check if at least one selected answer is correct (partial selection)
                    Set<String> correctSet  = new HashSet<>(correctList);
                    Set<String> selectedSet = new HashSet<>(sortedGiven);
                    boolean hasAnyCorrect = selectedSet.stream().anyMatch(correctSet::contains);
                    boolean hasWrong      = selectedSet.stream().anyMatch(a -> !correctSet.contains(a));
                    status = (hasAnyCorrect && hasWrong) ? "PARTIAL" : "WRONG";
                }
                entry.put("option1",         persisted.getOption1());
                entry.put("option2",         persisted.getOption2());
                entry.put("option3",         persisted.getOption3());
                entry.put("option4",         persisted.getOption4());
                entry.put("correct_answer",  persisted.getcorrect_answer());
                entry.put("selectedAnswers", req.getGivenAnswer());
                entry.put("earnedMark",      earnedMark);
                entry.put("isCorrect",       fullyCorrect);
                entry.put("status",          status);
            }

            // Both types accumulate here — always runs
            marksGot += earnedMark;
            if (fullyCorrect) correctAnswers++;
            // Save student answer
            StudentAnswer studentAnswer = new StudentAnswer();
            studentAnswer.setUser(user);
            studentAnswer.setQuestion(persisted);
            studentAnswer.setSelectedOptions(req.getGivenAnswer());
            studentAnswerRepository.save(studentAnswer);
            resultList.add(entry);
        }

        // Save / update report
        Report report = reportRepository.findByUserAndQuiz(user, quiz).orElse(new Report());
        report.setQuiz(quiz);
        report.setUser(user);
        report.setProgress("Completed");
        report.setMarks(BigDecimal.valueOf(marksGot));
        report.setMarksB(BigDecimal.ZERO);
        reportService.addReport(report);
        // Build summary response
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("studentId",      user.getId());
        response.put("quizId",         quiz.getqId());
        response.put("marksGot",       marksGot);
        response.put("maxMarks",       maxMarks);
        response.put("correctAnswers", correctAnswers);
        response.put("attempted",      attempted);
        response.put("totalQuestions", totalQs);
        response.put("results",        resultList);
        return ResponseEntity.ok(response);
    }

    // =========================================================================
    // Helpers
    // =========================================================================
    private boolean isAttempted(List<String> answers) {
        if (answers == null || answers.isEmpty()) return false;
        return answers.stream().anyMatch(a -> a != null && !a.trim().isEmpty());
    }
}