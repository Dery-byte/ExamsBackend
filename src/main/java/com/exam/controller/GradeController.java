package com.exam.controller;

import com.exam.model.exam.GradeRequest;
import com.exam.model.exam.GradeResponse;
import com.exam.model.exam.Questions;
import com.exam.model.exam.TheoryQuestions;
import com.exam.service.Impl.OpenAIService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import com.exam.service.Impl.GeminiAIService;



@RestController
@CrossOrigin( origins = "*")
@RequestMapping("/api/v1/auth")
public class GradeController {

    @Autowired
    private OpenAIService openAIService;

    @Autowired
    private GeminiAIService geminiAIService;

    @PostMapping("gradeSubjective")
    public ResponseEntity<GradeResponse> gradeResponse(@Validated @RequestBody GradeRequest gradeRequest) {
        try {
            String evaluation = openAIService.gradeResponse(gradeRequest);
            return ResponseEntity.ok(new GradeResponse(evaluation));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(new GradeResponse("Error: " + e.getMessage()));
        }
    }


//    GRADE USING GEMINI AI
@Autowired
public void GeminiController(GeminiAIService geminiAIService) {
    this.geminiAIService = geminiAIService;
}

//    @PostMapping("/gemini-data")
//    public String getGeminiData() {
//        return geminiAIService.getSomeDataFromGemini();
//    }


    @PostMapping("/gemini-data")
    public ResponseEntity<String> generateText(@RequestBody TheoryQuestions theoryQuestions) {
        try {
            String generatedText = geminiAIService.getSomeDataFromGemini(theoryQuestions.getQuestion());
            return ResponseEntity.ok(generatedText);
        } catch (Exception e) {
            return ResponseEntity.status(500).body("Error generating text: " + e.getMessage());
        }


    }


}