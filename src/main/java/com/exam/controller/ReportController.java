package com.exam.controller;

import com.exam.DTO.ReportDTO;
import com.exam.model.User;
import com.exam.model.exam.Quiz;
import com.exam.model.exam.Report;
import com.exam.repository.ReportRepository;
import com.exam.service.AuthenticationService;
import com.exam.service.QuizService;
import com.exam.service.ReportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/api/v1/auth")
public class ReportController {

@Autowired
@Lazy
private ReportService reportService;
    @Autowired
    @Lazy
    ReportRepository reportRepository;
    @Autowired
    private QuizService quizService;
    @Autowired
    private final UserDetailsService userDetailsService;
    @Autowired
    private AuthenticationService authenticationService;
    public ReportController(UserDetailsService userDetailsService) {
        this.userDetailsService = userDetailsService;
    }




    //get all reports
    @GetMapping("/getReport")
    public List<Report> getSpecificQuestionsOfQuizAdmin(){
       return this.reportService.getUserIdAndQuizId();
    }


//    get report by report id
    @GetMapping("/getReport/{rid}")
    public Report getUserQuizIds(@PathVariable("rid") Long rid){
        return this.reportService.userQuizIDs(rid);
    }


    //ADD THE SECTION MARKS METHODS
    @PutMapping("/addtheoryMark")
    public ResponseEntity<?> addmarks(@RequestBody Report report, Principal principal) {
        if (principal == null) {
            return ResponseEntity.badRequest().body("Principal is null");
        }

        User user = (User) this.userDetailsService.loadUserByUsername(principal.getName());
        Long quizId = report.getQuiz() != null ? report.getQuiz().getqId() : null;

        if (quizId == null) {
            return ResponseEntity.badRequest().body("Quiz ID is missing");
        }

        // Get a managed Quiz instance
        Quiz managedQuiz = quizService.getQuiz(quizId);
        if (managedQuiz == null) {
            return ResponseEntity.badRequest().body("Quiz not found");
        }

        Report existingReport = this.reportService.findByUserAndQuiz(Math.toIntExact(user.getId()), quizId);

        if (existingReport == null) {
            existingReport = new Report();
            existingReport.setUser(user);
            existingReport.setQuiz(managedQuiz);  // Using the managed quiz
            existingReport.setMarksB(report.getMarksB());
            existingReport.setProgress("Completed");
            existingReport.setMarks(BigDecimal.valueOf(0));
        } else {
            existingReport.setMarksB(report.getMarksB());
        }
        Report updatedReport = reportRepository.save(existingReport);
        return ResponseEntity.ok(updatedReport);
    }





//Report on quiz id
@GetMapping("/getReports/{quiz_Id}")
public ResponseEntity<List<Report>> getQuizIds(@PathVariable("quiz_Id") Long quiz_Id){
        Quiz quiz = quizService.getQuiz(quiz_Id);
        List<Report> reports = reportService.reportByQuiz_Id(quiz.getqId());
    return ResponseEntity.ok(reports);
}










// Reports by user ID
    @GetMapping("/getReportsByUser/{user_Id}")
    public ResponseEntity<List<Report>>  getReportUser_Id(@PathVariable("user_Id") Integer user_Id){
        User user = authenticationService.getUserById(user_Id);
        List<Report> reports = reportService.reportByUser_id(user);
        return ResponseEntity.ok(reports);
    }

    @GetMapping("/getReportByUidAndQid/{user_Id}/{quiz_Id}")
    public ResponseEntity<?> getQuizResultsByUserAndType( @PathVariable("user_Id") Integer user_Id, @PathVariable("quiz_Id") Long quiz_Id){
        Optional<Quiz> quiz = Optional.ofNullable(quizService.getQuiz(quiz_Id));
        Optional<User> user = Optional.ofNullable(authenticationService.getUserById(user_Id));
        Optional<User> users = Optional.of(Optional.ofNullable(authenticationService.getUserById(user_Id)).orElseThrow());
        if(quiz.isPresent() && user.isPresent()){
            List<Report> quizResults = reportService.getReportByUserAndType(user,quiz);
            return ResponseEntity.ok(quizResults);
        }
        else{
//            Make sure to handle the error here
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("User or Quiz not Found");
        }
}















    @GetMapping("/quiz/result/{quizId}")
    public ResponseEntity<?> getStudentQuizResult(@PathVariable("quizId") Long quizId,
                                                  Principal principal) {
        if (principal == null) {
            return ResponseEntity.badRequest().body("User not logged in");
        }
        User user = (User) userDetailsService.loadUserByUsername(principal.getName());
        if (user == null ) {
            return ResponseEntity.badRequest().body("Student not found");
        }
        Map<String, Object> result = reportService.getStudentQuizResult(quizId, user.getId());
        return ResponseEntity.ok(result);
    }





//GET THE QUIZ BY QUID_ID AND USER_ID
// Reports by user ID
@GetMapping("/my/quiz-titles/reports")
public ResponseEntity<List<Report>> getMyReports(Principal principal) {
    return ResponseEntity.ok(
            reportService.getReportsForCurrentUser(principal)
    );
}


    @GetMapping("/my-students-reports")
    public ResponseEntity<List<ReportDTO>> getMyStudentsReports(
            @AuthenticationPrincipal User loggedInLecturer) {
        List<ReportDTO> reports = reportService.getReportsForMyQuizzes(
                loggedInLecturer.getId()
        );
        return ResponseEntity.ok(reports);
    }









}



