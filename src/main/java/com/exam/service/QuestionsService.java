//package com.exam.service;
//
//import com.exam.DTO.QuestionDTO;
//import com.exam.DTO.QuestionResponseDTO;
//import com.exam.DTO.UpdateQuestionDTO;
//import com.exam.model.Role;
//import com.exam.model.User;
//import com.exam.model.exam.Questions;
//import com.exam.model.exam.Quiz;
//import com.exam.repository.QuestionsRepository;
//import com.exam.repository.ReportRepository;
//import jakarta.persistence.EntityNotFoundException;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.context.annotation.Lazy;
//import org.springframework.data.domain.Page;
//import org.springframework.data.domain.PageRequest;
//import org.springframework.http.ResponseEntity;
//import org.springframework.stereotype.Service;
//import org.springframework.transaction.annotation.Transactional;
//
//import java.security.Principal;
//import java.util.*;
//
//@Service
//public class QuestionsService {
//
//    @Autowired
//    private QuestionsRepository questionsRepository;
//    @Autowired
//    @Lazy
//    private ReportRepository reportRepository;
//
//    public Questions addQuestions(Questions questions){
//        return this.questionsRepository.save(questions);
//    }
////    public Questions updateQuestions(Questions questions){
////        return this.questionsRepository.save(questions);
////    }
//
//    @Transactional
//    public QuestionDTO updateQuestion(UpdateQuestionDTO dto) {
//        Questions question = questionsRepository.findById(dto.getQuesId())
//                .orElseThrow(() -> new RuntimeException("Question not found"));
//        question.setContent(dto.getContent());
//        question.setImage(dto.getImage());
//        question.setOption1(dto.getOption1());
//        question.setOption2(dto.getOption2());
//        question.setOption3(dto.getOption3());
//        question.setOption4(dto.getOption4());
//        question.setcorrect_answer(dto.getCorrect_answer());
//        Questions updated = questionsRepository.save(question);
//        return toDTO(updated); // RETURN DTO
//    }
//
//
//
//    private QuestionDTO toDTO(Questions question) {
//        QuestionDTO dto = new QuestionDTO();
//        dto.setQuesId(question.getQuesId());
//        dto.setContent(question.getContent());
//        dto.setImage(question.getImage());
//        dto.setOption1(question.getOption1());
//        dto.setOption2(question.getOption2());
//        dto.setOption3(question.getOption3());
//        dto.setOption4(question.getOption4());
//
//        dto.setCorrect_answer(question.getcorrect_answer());
//
////        if (question.getQuiz() != null) {
////            dto.setQuizId(question.getQuiz().getqId());
////        }
//
//        return dto;
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
//    public Set<Questions> getQuestions(){
//        return new HashSet<>(this.questionsRepository.findAll());
//    }
//
//    public Questions getQuestions(Long quesId){
//        return this.questionsRepository.findById(quesId).get();
//    }
//
//
//    public Questions UpdateQuestion(Questions questions){
//        return  this.questionsRepository.save(questions);
//    }
////    public void deleteQuestions(Long quesId){
////        Questions questions = new Questions();
////        questions.setQuesId(quesId);
////        this.questionsRepository.delete(quesId);
////
////    }
//
//
//    public Set<Questions> getQuestionsOfQuiz(Quiz quiz) {
//        return this.questionsRepository.findByQuiz(quiz);
//    }
//
//    public List<QuestionResponseDTO> getShuffledQuestionsForStudent(Long qid) {
//        Quiz quiz = new Quiz();
//        quiz.setqId(qid);
//        // 1. Fetch
//        List<Questions> list = new ArrayList<>(this.questionsRepository.findByQuiz(quiz));
//        // 2. Shuffle
//        Collections.shuffle(list);
//        // 3 & 4. Assign count and map to DTO
//        List<QuestionResponseDTO> result = new ArrayList<>();
//        for (int i = 0; i < list.size(); i++) {
//            result.add(toResponseDTO(list.get(i), i + 1));
//        }
//        return result;
//    }
//
//
//    private QuestionResponseDTO toResponseDTO(Questions question, int count) {
//        QuestionResponseDTO dto = new QuestionResponseDTO();
//        dto.setQuesId(question.getQuesId());
//        dto.setCount(count);
//        dto.setContent(question.getContent());
//        dto.setImage(question.getImage());
//        dto.setOption1(question.getOption1());
//        dto.setOption2(question.getOption2());
//        dto.setOption3(question.getOption3());
//        dto.setOption4(question.getOption4());
//
//        // Safely handle null — unanswered questions have no givenAnswer yet
//        String[] given = question.getGivenAnswer();
//        dto.setGivenAnswer(given != null ? Arrays.asList(given) : new ArrayList<>());
//
//        return dto;
//    }
//
//    public List<Questions> getQuestionsForMyQuiz(Long quizId, Principal principal) {
//        String username = principal.getName();
//        return questionsRepository.findByQuiz_qIdAndQuiz_Category_User_Username(quizId, username);
//    }
//
//
//
//
//
//
//
//    //limited Questions
//public Page<Questions> getLimitedRecords(int page, int size) {
//        PageRequest pageRequest = PageRequest.of(page, size);
//        return this.questionsRepository.findAll(pageRequest);
//    }
//
//    public void deleteQuestion(Long quesId){
//        Questions questions = new Questions();
//        questions.setQuesId(quesId);
//        this.questionsRepository.delete(questions);
//    }
//
//
////    public  Questions get (Long questionId){
////        return this.questionsRepository.getOne(questionId);
////    }
//
////    public  Questions get (Long questionId){
////        return this.questionsRepository.getReferenceById(questionId);
////    }
//
//    public Questions get(Long questionId) {
//        if (questionId == null) {
//            throw new IllegalArgumentException("questionId must not be null");
//        }
//
//        return questionsRepository.findById(questionId)
//                .orElseThrow(() ->
//                        new EntityNotFoundException("Question not found with id " + questionId)
//                );
//    }
//
//
//
//
//
//
//////    uploading the questions
////public List<Questions> saveAllQuestions(List<Questions> questions) {return questionsRepository.saveAll(questions);
////}
//////    uploading the questions
//
//
//    //    uploading the questions
//    public List<Questions> saveAllQuestions(List<Questions> questions) {return questionsRepository.saveAll(questions);
//    }
////    uploading the questions
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
//    // Get specified questions
//    public ResponseEntity<List<Questions>> getRandomRecords() {
//        List<Questions> allRecords = questionsRepository.findAll();
//        // Shuffle the records randomly
//        Collections.shuffle(allRecords);
//        // Get the first 15 records
//        List<Questions> randomRecords = allRecords.subList(0, Math.min(2, allRecords.size()));
//        return ResponseEntity.ok(randomRecords);
//    }
//
//
//
//
////    public Questions get(Long quesId) {
////        return questionsRepository.findById(quesId).orElse(null);
////    }
//
//    // 🔴 ADD THIS METHOD
//    public List<Questions> getQuestionsByQuizId(Long quizId) {
//        List<Questions> list = questionsRepository.findByQuiz_qId(quizId);
//        System.out.println("Questions found for quiz " + quizId + " = " + list.size());
//        return list;
//    }
//
//
//
////    public Report AddReport(Report report){
////        return reportRepository.save(report);
////    }
//
////    public Report getReportByQuid(Long qid){
////        return reportRepository.findByQuizId(qid);
////    }
//
//
//
//    // Get specified questions
////    public ResponseEntity<List<Questions>> getRandomRecords() {
////        List<Questions> allRecords = questionsRepository.findAll();
////
////        // Shuffle the records randomly
////        Collections.shuffle(allRecords);
////
////        // Get the first 15 records
////        List<Questions> randomRecords = allRecords.subList(0, Math.min(2, allRecords.size()));
////
////        return ResponseEntity.ok(randomRecords);
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
//}





package com.exam.service;

import com.exam.DTO.*;
import com.exam.model.exam.MatchingPair;
import com.exam.model.exam.QuestionType;
import com.exam.model.exam.Questions;
import com.exam.model.exam.Quiz;
import com.exam.repository.QuestionsRepository;
import com.exam.repository.ReportRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.Principal;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class QuestionsService {

    @Autowired
    private QuestionsRepository questionsRepository;

    @Autowired
    @Lazy
    private ReportRepository reportRepository;

    // ── Add ───────────────────────────────────────────────────────────────────

    public Questions addQuestions(Questions questions) {
        return this.questionsRepository.save(questions);
    }

    // ── Update ────────────────────────────────────────────────────────────────

    @Transactional
    public QuestionDTO updateQuestion(UpdateQuestionDTO dto) {
        Questions question = questionsRepository.findById(dto.getQuesId())
                .orElseThrow(() -> new RuntimeException("Question not found"));

        question.setContent(dto.getContent());
        question.setImage(dto.getImage());
        question.setQuestionType(dto.getQuestionType() != null
                ? dto.getQuestionType()
                : QuestionType.MCQ);                          // default to MCQ

        if (question.getQuestionType() == QuestionType.MATCHING) {
            // Replace existing pairs entirely (orphanRemoval handles deletes)
            question.getMatchingPairs().clear();
            if (dto.getMatchingPairs() != null) {
                for (MatchingPair pair : dto.getMatchingPairs()) {
                    pair.setQuestion(question);               // set FK
                    question.getMatchingPairs().add(pair);
                }
            }
            // Clear MCQ fields — not needed for MATCHING
            question.setOption1(null);
            question.setOption2(null);
            question.setOption3(null);
            question.setOption4(null);
            question.setcorrect_answer(null);

        } else {
            // MCQ or TRUE_FALSE
            question.setOption1(dto.getOption1());
            question.setOption2(dto.getOption2());
            question.setOption3(dto.getOption3());
            question.setOption4(dto.getOption4());
            question.setcorrect_answer(dto.getCorrect_answer());
            question.getMatchingPairs().clear();              // clear pairs if switching type
        }

        Questions updated = questionsRepository.save(question);
        return toDTO(updated);
    }

    // ── toDTO (admin / internal use — includes correct answers) ──────────────

    private QuestionDTO toDTO(Questions question) {
        QuestionDTO dto = new QuestionDTO();
        dto.setQuesId(question.getQuesId());
        dto.setContent(question.getContent());
        dto.setImage(question.getImage());
        dto.setQuestionType(question.getQuestionType());

        if (question.getQuestionType() == QuestionType.MATCHING) {
            dto.setMatchingPairs(question.getMatchingPairs());
        } else {
            dto.setOption1(question.getOption1());
            dto.setOption2(question.getOption2());
            dto.setOption3(question.getOption3());
            dto.setOption4(question.getOption4());
            dto.setCorrect_answer(question.getcorrect_answer());
        }
        return dto;
    }

    // ── toResponseDTO (student-facing — shuffles matching answer pool) ────────


//
//    private QuestionResponseDTO toResponseDTO(Questions question, int count) {
//        QuestionResponseDTO dto = new QuestionResponseDTO();
//        dto.setQuesId(question.getQuesId());
//        dto.setCount(count);
//        dto.setContent(question.getContent());
//        dto.setImage(question.getImage());
//        dto.setQuestionType(question.getQuestionType());
//
//        String[] given = question.getGivenAnswer();
//        dto.setGivenAnswer(given != null ? Arrays.asList(given) : new ArrayList<>());
//
//        if (question.getQuestionType() == QuestionType.MATCHING) {
//            // Send pairs ordered by pairOrder — frontend shuffles the answer pool
//            List<MatchingPair> pairs = new ArrayList<>(question.getMatchingPairs());
//            pairs.sort(Comparator.comparingInt(MatchingPair::getPairOrder));
//            dto.setMatchingPairs(pairs);
//
//        } else {
//            dto.setOption1(question.getOption1());
//            dto.setOption2(question.getOption2());
//            dto.setOption3(question.getOption3());
//            dto.setOption4(question.getOption4());
//        }
//        return dto;
//    }




    private QuestionResponseDTO toResponseDTO(Questions question, int count) {
        QuestionResponseDTO dto = new QuestionResponseDTO();
        dto.setQuesId(question.getQuesId());
        dto.setCount(count);
        dto.setContent(question.getContent());
        dto.setImage(question.getImage());
        dto.setQuestionType(question.getQuestionType());

        String[] given = question.getGivenAnswer();
        dto.setGivenAnswer(given != null ? Arrays.asList(given) : new ArrayList<>());

        if (question.getQuestionType() == QuestionType.MATCHING) {
            List<QuestionResponseDTO.MatchingPairDTO> pairs = question.getMatchingPairs()
                    .stream()
                    .sorted(Comparator.comparingInt(MatchingPair::getPairOrder))
                    .map(p -> {
                        QuestionResponseDTO.MatchingPairDTO pairDTO = new QuestionResponseDTO.MatchingPairDTO();
                        pairDTO.setId(p.getId());
                        pairDTO.setPrompt(p.getPrompt());
                        pairDTO.setAnswer(p.getAnswer());
                        pairDTO.setPairOrder(p.getPairOrder());
                        return pairDTO;
                    })
                    .collect(Collectors.toList());
            dto.setMatchingPairs(pairs);

        } else {
            dto.setOption1(question.getOption1());
            dto.setOption2(question.getOption2());
            dto.setOption3(question.getOption3());
            dto.setOption4(question.getOption4());
        }

        return dto;
    }





    private QuestionResponseAdminDTO toResponseAdminDTO(Questions question, int count) {
        QuestionResponseAdminDTO dto = new QuestionResponseAdminDTO();
        dto.setQuesId(question.getQuesId());
        dto.setCount(count);
        dto.setContent(question.getContent());
        dto.setImage(question.getImage());
        dto.setQuestionType(question.getQuestionType());

        String[] given = question.getGivenAnswer();
        dto.setGivenAnswer(given != null ? Arrays.asList(given) : new ArrayList<>());

        // Map correct_answer String[] → List<String>
        String[] correct = question.getcorrect_answer();
        dto.setCorrectAnswer(correct != null ? Arrays.asList(correct) : null);

        if (question.getQuestionType() == QuestionType.MATCHING) {
            // Send pairs ordered by pairOrder — frontend shuffles the answer pool
            List<MatchingPair> pairs = new ArrayList<>(question.getMatchingPairs());
            pairs.sort(Comparator.comparingInt(MatchingPair::getPairOrder));
            dto.setMatchingPairs(pairs);

        } else {
            dto.setOption1(question.getOption1());
            dto.setOption2(question.getOption2());
            dto.setOption3(question.getOption3());
            dto.setOption4(question.getOption4());
        }
        return dto;
    }

    // ── Reads ─────────────────────────────────────────────────────────────────

    public Set<Questions> getQuestions() {
        return new HashSet<>(this.questionsRepository.findAll());
    }

    public Questions getQuestions(Long quesId) {
        return this.questionsRepository.findById(quesId).get();
    }






//    public SingleQuestionSummaryDTO getQuestionSummary(Long quesId) {
//        Questions question = questionsRepository.findById(quesId)
//                .orElseThrow(() -> new RuntimeException("Question not found: " + quesId));
//
//        SingleQuestionSummaryDTO dto = new SingleQuestionSummaryDTO();
//        dto.setQuesId(question.getQuesId());
//        dto.setContent(question.getContent());
//        dto.setImage(question.getImage());
//        dto.setOption1(question.getOption1());
//        dto.setOption2(question.getOption2());
//        dto.setOption3(question.getOption3());
//        dto.setOption4(question.getOption4());
//        dto.setQuestionType(question.getQuestionType());
//
//        String[] correct = question.getcorrect_answer();
//        dto.setCorrectAnswer(correct != null ? Arrays.asList(correct) : null);
//
//        String[] given = question.getGivenAnswer();
//        dto.setGivenAnswer(given != null ? Arrays.asList(given) : null);
//
//        return dto;
//    }



    public SingleQuestionSummaryDTO getQuestionSummary(Long quesId) {
        Questions question = questionsRepository.findById(quesId)
                .orElseThrow(() -> new RuntimeException("Question not found: " + quesId));

        SingleQuestionSummaryDTO dto = new SingleQuestionSummaryDTO();
        dto.setQuesId(question.getQuesId());
        dto.setContent(question.getContent());
        dto.setImage(question.getImage());
        dto.setQuestionType(question.getQuestionType());

        String[] correct = question.getcorrect_answer();
        dto.setCorrectAnswer(correct != null ? Arrays.asList(correct) : null);

        String[] given = question.getGivenAnswer();
        dto.setGivenAnswer(given != null ? Arrays.asList(given) : null);

        if (question.getQuestionType() == QuestionType.MATCHING) {
            List<SingleQuestionSummaryDTO.MatchingPairDTO> pairs = question.getMatchingPairs()
                    .stream()
                    .sorted(Comparator.comparingInt(MatchingPair::getPairOrder))
                    .map(p -> {
                        SingleQuestionSummaryDTO.MatchingPairDTO pairDTO = new SingleQuestionSummaryDTO.MatchingPairDTO();
                        pairDTO.setId(p.getId());
                        pairDTO.setPrompt(p.getPrompt());
                        pairDTO.setAnswer(p.getAnswer());
                        pairDTO.setPairOrder(p.getPairOrder());
                        return pairDTO;
                    })
                    .collect(Collectors.toList());
            dto.setMatchingPairs(pairs);
        } else {
            dto.setOption1(question.getOption1());
            dto.setOption2(question.getOption2());
            dto.setOption3(question.getOption3());
            dto.setOption4(question.getOption4());
        }

        return dto;
    }

    public Questions UpdateQuestion(Questions questions) {
        return this.questionsRepository.save(questions);
    }

    public Set<Questions> getQuestionsOfQuiz(Quiz quiz) {
        return this.questionsRepository.findByQuiz(quiz);
    }

    public List<QuestionResponseDTO> getShuffledQuestionsForStudent(Long qid) {
        Quiz quiz = new Quiz();
        quiz.setqId(qid);
        List<Questions> list = new ArrayList<>(this.questionsRepository.findByQuiz(quiz));
        Collections.shuffle(list);
        List<QuestionResponseDTO> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            result.add(toResponseDTO(list.get(i), i + 1));
        }
        return result;
    }


    public List<QuestionResponseAdminDTO> getNoneQuestionsForAdmin(Long qid) {
        Quiz quiz = new Quiz();
        quiz.setqId(qid);
        List<Questions> list = new ArrayList<>(this.questionsRepository.findByQuiz(quiz));
        List<QuestionResponseAdminDTO> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            result.add(toResponseAdminDTO(list.get(i), i + 1));
        }
        return result;
    }



    public List<Questions> getQuestionsForMyQuiz(Long quizId, Principal principal) {
        String username = principal.getName();
        return questionsRepository.findByQuiz_qIdAndQuiz_Category_User_Username(quizId, username);
    }

    public List<Questions> getQuestionsByQuizId(Long quizId) {
        List<Questions> list = questionsRepository.findByQuiz_qId(quizId);
        System.out.println("Questions found for quiz " + quizId + " = " + list.size());
        return list;
    }

    public Questions get(Long questionId) {
        if (questionId == null) {
            throw new IllegalArgumentException("questionId must not be null");
        }
        return questionsRepository.findById(questionId)
                .orElseThrow(() -> new EntityNotFoundException("Question not found with id " + questionId));
    }

    // ── Pagination ────────────────────────────────────────────────────────────

    public Page<Questions> getLimitedRecords(int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, size);
        return this.questionsRepository.findAll(pageRequest);
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    public void deleteQuestion(Long quesId) {
        Questions questions = new Questions();
        questions.setQuesId(quesId);
        this.questionsRepository.delete(questions);
    }

    // ── Bulk save ─────────────────────────────────────────────────────────────

    public List<Questions> saveAllQuestions(List<Questions> questions) {
        return questionsRepository.saveAll(questions);
    }

    // ── Random sample ─────────────────────────────────────────────────────────

    public ResponseEntity<List<Questions>> getRandomRecords() {
        List<Questions> allRecords = questionsRepository.findAll();
        Collections.shuffle(allRecords);
        List<Questions> randomRecords = allRecords.subList(0, Math.min(2, allRecords.size()));
        return ResponseEntity.ok(randomRecords);
    }
}