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
    private QuestionImageService questionImageService;

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
        // The type may change on edit, so check the answer fields against the new type before touching anything
        // (an old image file is deleted below, which a rollback would not undo).
        validateUpdate(dto);

        question.setContent(dto.getContent());
        String newImage = (dto.getImage() == null || dto.getImage().isBlank()) ? null : dto.getImage();
        if (!Objects.equals(question.getImage(), newImage)) {
            questionImageService.deleteByPath(question.getImage());
        }
        question.setImage(newImage);
        question.setQuestionType(dto.getQuestionType() != null
                ? dto.getQuestionType()
                : QuestionType.MCQ);                          // default to MCQ

        if (question.getQuestionType() == QuestionType.MATCHING) {
            // Replace the pairs with the edited list (orphanRemoval deletes the dropped ones)
            applyMatchingPairs(question, dto.getMatchingPairs());
            // Clear MCQ fields — not needed for MATCHING
            question.setOption1(null);
            question.setOption2(null);
            question.setOption3(null);
            question.setOption4(null);
            question.setcorrect_answer(null);

        } else if (AnswerMatcher.isTyped(question.getQuestionType())) {
            // FILL_BLANK / NUMERIC: no options, only accepted answers (+ tolerance for numbers)
            AnswerMatcher.validateTyped(question.getQuestionType(), dto.getCorrect_answer(), dto.getTolerance());
            question.setOption1(null);
            question.setOption2(null);
            question.setOption3(null);
            question.setOption4(null);
            question.setcorrect_answer(AnswerMatcher.cleanAccepted(dto.getCorrect_answer()));
            question.setTolerance(question.getQuestionType() == QuestionType.NUMERIC ? dto.getTolerance() : null);
            question.getMatchingPairs().clear();
        } else if (question.getQuestionType() == QuestionType.TRUE_FALSE) {
            question.setOption1("True");
            question.setOption2("False");
            question.setOption3(null);
            question.setOption4(null);
            question.setcorrect_answer(dto.getCorrect_answer());
            question.setTolerance(null);
            question.getMatchingPairs().clear();
        } else {
            // MCQ
            question.setOption1(dto.getOption1());
            question.setOption2(dto.getOption2());
            question.setOption3(dto.getOption3());
            question.setOption4(dto.getOption4());
            question.setcorrect_answer(dto.getCorrect_answer());
            question.setTolerance(null);
            question.getMatchingPairs().clear();              // clear pairs if switching type
        }

        Questions updated = questionsRepository.save(question);
        return toDTO(updated);
    }

    /**
     * Makes the question's pairs match the edited list, in its order. The client's objects are
     * copied, never attached: a pair whose id belongs to this question is updated in place; any other
     * entry — no id, an id sent twice, or an id from another question — becomes a new pair. Attaching
     * the client's objects directly failed when the same pair arrived twice ("Multiple representations
     * of the same entity").
     */
    static void applyMatchingPairs(Questions question, List<MatchingPair> edited) {
        Map<Long, MatchingPair> existing = new HashMap<>();
        for (MatchingPair p : question.getMatchingPairs()) {
            if (p.getId() != null) existing.put(p.getId(), p);
        }
        List<MatchingPair> result = new ArrayList<>();
        if (edited != null) {
            for (MatchingPair in : edited) {
                if (in == null) continue;
                MatchingPair pair = in.getId() == null ? null : existing.remove(in.getId());
                if (pair == null) {
                    pair = new MatchingPair();
                    pair.setQuestion(question);
                }
                pair.setPrompt(in.getPrompt());
                pair.setAnswer(in.getAnswer());
                pair.setPairOrder(in.getPairOrder() != null ? in.getPairOrder() : result.size());
                result.add(pair);
            }
        }
        question.getMatchingPairs().clear();
        question.getMatchingPairs().addAll(result);
    }

    /** The answer fields an edit must carry for its (possibly new) type. */
    static void validateUpdate(UpdateQuestionDTO dto) {
        if (dto.getContent() == null || dto.getContent().replaceAll("<[^>]*>", "").replace("&nbsp;", " ").isBlank())
            throw new IllegalArgumentException("Question text is required.");
        QuestionType type = dto.getQuestionType() != null ? dto.getQuestionType() : QuestionType.MCQ;
        String[] correct = dto.getCorrect_answer() == null ? new String[0] : dto.getCorrect_answer();
        switch (type) {
            case MATCHING -> {
                List<MatchingPair> pairs = dto.getMatchingPairs() == null ? List.of() : dto.getMatchingPairs();
                for (MatchingPair p : pairs)
                    if (p == null || isBlank(p.getPrompt()) || isBlank(p.getAnswer()))
                        throw new IllegalArgumentException("Every matching pair needs both a prompt and its match.");
                if (pairs.size() < 2) throw new IllegalArgumentException("A matching question needs at least 2 pairs.");
            }
            case FILL_BLANK, NUMERIC -> AnswerMatcher.validateTyped(type, dto.getCorrect_answer(), dto.getTolerance());
            case TRUE_FALSE -> {
                if (correct.length != 1 || !("True".equals(correct[0]) || "False".equals(correct[0])))
                    throw new IllegalArgumentException("Choose True or False as the correct answer.");
            }
            case MCQ -> {
                List<String> options = new ArrayList<>();
                for (String o : new String[]{dto.getOption1(), dto.getOption2(), dto.getOption3(), dto.getOption4()})
                    if (!isBlank(o)) options.add(o);
                if (isBlank(dto.getOption1()) || isBlank(dto.getOption2()))
                    throw new IllegalArgumentException("A multiple-choice question needs at least options A and B.");
                if (correct.length == 0) throw new IllegalArgumentException("Mark at least one option as correct.");
                for (String c : correct)
                    if (!options.contains(c))
                        throw new IllegalArgumentException("The correct answer \"" + c + "\" is not one of the options. Mark the correct option again.");
            }
        }
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

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
            dto.setTolerance(question.getTolerance());
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
        dto.setTolerance(question.getTolerance());

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
        dto.setTolerance(question.getTolerance());

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
        questionsRepository.findById(quesId)
                .ifPresent(existing -> questionImageService.deleteByPath(existing.getImage()));
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