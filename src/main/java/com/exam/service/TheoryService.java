package com.exam.service;


import com.exam.DTO.TheoryQuestionDTO;
import com.exam.DTO.TheoryQuestionResponseDTO;
import com.exam.DTO.TheoryUpdateRequest;
import com.exam.model.exam.Questions;
import com.exam.model.exam.Quiz;
import com.exam.model.exam.TheoryQuestions;
import com.exam.repository.ReportRepository;
import com.exam.repository.TheoryQuestionsRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class TheoryService {

    @Autowired
    private TheoryQuestionsRepository theoryQuestionsRepository;
    @Autowired
    @Lazy
    private ReportRepository reportRepository;


    public TheoryQuestions addQuestions(TheoryQuestions theoryQuestions){
        return this.theoryQuestionsRepository.save(theoryQuestions);
    }













//    public TheoryQuestions updateQuestions(TheoryQuestions theoryQuestions){
//        return this.theoryQuestionsRepository.save(theoryQuestions);
//    }
//


    public TheoryQuestionDTO updateQuestions(TheoryUpdateRequest request){
        // Fetch existing entity
        TheoryQuestions tq = theoryQuestionsRepository.findById(request.getTqId())
                .orElseThrow(() -> new RuntimeException("Question not found"));
        // Update fields if provided
        if (request.getQuesNo() != null) tq.setQuesNo(request.getQuesNo());
        if (request.getQuestion() != null) tq.setQuestion(request.getQuestion());
        if (request.getMarks() != null) tq.setMarks(request.getMarks());
        if(request.getEvaluationCriteria() != null) tq.setEvaluationCriteria(request.getEvaluationCriteria());
        if (request.getQuizId() != null) {
//            Quiz quiz = new Quiz();
//            quiz.setqId(request.getQuizId());
//            tq.setQuiz(quiz);
        }

        TheoryQuestions updated = this.theoryQuestionsRepository.save(tq);
        return toDTO(updated);  // convert to DTO for response
    }
    public TheoryQuestionDTO toDTO(TheoryQuestions tq) {
        TheoryQuestionDTO dto = new TheoryQuestionDTO();
        dto.setTqId(tq.getTqId());
        dto.setQuesNo(tq.getQuesNo());
        dto.setQuestion(tq.getQuestion());
        dto.setMarks(String.valueOf(tq.getMarks()));
        dto.setEvaluationCriteria(tq.getEvaluationCriteria());
        if (tq.getQuiz() != null) {
            dto.setQuizId(tq.getQuiz().getqId());
        }
        return dto;
    }















    public Set<TheoryQuestions> getAllQuestions(){
        return new HashSet<>(this.theoryQuestionsRepository.findAll());
    }


    public TheoryQuestions getQuestions(Long quesId){
        return this.theoryQuestionsRepository.findById(quesId).get();
    }

    public Set<TheoryQuestions> getQuestionsForSpecificQuiz(Quiz quiz){
        return this.theoryQuestionsRepository.findByQuiz(quiz);
    }





    /**
     * Returns raw entity set — used internally (e.g. grading, admin views).
     */
//    public Set<TheoryQuestions> getQuestionsForSpecificQuiz(Quiz quiz) {
//        return this.theoryQuestionsRepository.findByQuiz(quiz);
//    }

    /**
     * Returns student-safe DTOs for a given quiz ID.
     *
     * Benefits over returning the raw entity:
     *  - Strips any internal fields (model answers, grading notes, etc.)
     *  - Sends only what the frontend template actually needs
     *  - Keeps the API contract stable if the entity changes internally
     */
    public List<TheoryQuestionResponseDTO> getTheoryQuestionsForStudent(Long qid) {
        Quiz quiz = new Quiz();
        quiz.setqId(qid);

        return this.theoryQuestionsRepository.findByQuiz(quiz)
                .stream()
                .map(this::toResponseDTO)
                .collect(Collectors.toList());
    }

    /**
     * Maps a TheoryQuestions entity to a student-safe DTO.
     * Adjust field names below to match your actual entity getters.
     */
    private TheoryQuestionResponseDTO toResponseDTO(TheoryQuestions q) {
        TheoryQuestionResponseDTO dto = new TheoryQuestionResponseDTO();
        dto.setQuesId(q.getTqId());
        dto.setQuesNo(q.getQuesNo());
        dto.setQuestion(q.getQuestion());
        dto.setMarks((q.getMarks()));
        dto.setPrefix(q.getQuesNo());
        dto.setCompulsory(q.getIsCompulsory());
        dto.setEvaluationCriteria(q.getEvaluationCriteria());

        // givenAnswer starts null for unanswered questions —
        // the textarea placeholder handles the empty state in the UI
        dto.setGivenAnswer(q.getAnswer());

        // ✗ model answers / marking schemes intentionally not mapped

        return dto;
    }




    public void deleteQuestion(Long TqId){
        TheoryQuestions theoryQuestions = new TheoryQuestions();
        theoryQuestions.setTqId(TqId);
        this.theoryQuestionsRepository.delete(theoryQuestions);
    }

    //    uploading the questions
    public List<TheoryQuestions> saveAllQuestions(List<TheoryQuestions> theoryQuestions) {
        return theoryQuestionsRepository.saveAll(theoryQuestions);
    }






    // SETTING QUIZ AS COMPULSORY SERVICE
    public void updateCompulsoryStatusByPrefix(Long quizId, String prefix, Boolean isCompulsory) {
        List<TheoryQuestions> questions = theoryQuestionsRepository
                .findByQuiz_qIdAndQuesNoStartingWith(quizId, prefix);
        questions.forEach(question -> {
            question.setIsCompulsory(isCompulsory);
        });
        theoryQuestionsRepository.saveAll(questions);
    }

}
