//package com.exam.model.exam;
//
//
//import jakarta.persistence.*;
//
//@Entity
//@Table(name = "questions")
//public class Questions {
//    @Id
//    @GeneratedValue(strategy = GenerationType.AUTO)
//    private Long quesId;
//    @Column(length = 5000)
//    private String content;
//    private String image;
//    private String option1;
//    private String option2;
//    private String option3;
//    private String option4;
//
////    private String answer;
//
//    @Convert(converter = StringArrayConverter.class)
//    private String[] correct_answer;
//    @Transient
//    private String[] givenAnswer;
//
//    public String[] getGivenAnswer() {
//        return givenAnswer;
//    }
//
//    public Questions(String[] givenAnswer) {
//        this.givenAnswer = givenAnswer;
//    }
//
//    public void setGivenAnswer(String[] givenAnswer) {
//        this.givenAnswer = givenAnswer;
//    }
//
//    @ManyToOne(cascade = CascadeType.MERGE)
////    @JoinColumn(name = "quiz_id")
//    private Quiz quiz;
//
//    public Questions() {
//    }
//
////    public Questions(Long quesId, String content, String image,
////                     String option1, String option2, String option3,
////                     String option4, String answer, Quiz quiz) {
////        this.quesId = quesId;
////        this.content = content;
////        this.image = image;
////        this.option1 = option1;
////        this.option2 = option2;
////        this.option3 = option3;
////        this.option4 = option4;
//////        this.answer = answer;
////        this.quiz = quiz;
////    }
//
//
//    public Questions(Long quesId, String content, String image,
//                     String option1, String option2,
//                     String option3, String option4,
//                     String[] correct_answer, String[] givenAnswer, Quiz quiz) {
//        this.quesId = quesId;
//        this.content = content;
//        this.image = image;
//        this.option1 = option1;
//        this.option2 = option2;
//        this.option3 = option3;
//        this.option4 = option4;
//        this.correct_answer = correct_answer;
//        this.givenAnswer = givenAnswer;
//        this.quiz = quiz;
//    }
//
//    public void setcorrect_answer(String[] correct_answer) {
//        this.correct_answer = correct_answer;
//    }
//
//    public String[] getcorrect_answer() {
//        return correct_answer;
//    }
//
//    // Method to set answer from a comma-separated string
//    public void setAnswer(String correct_answer) {
//        if (correct_answer.contains(",")) {
//            this.correct_answer = correct_answer.split("\\s*,\\s*"); // Split by comma and trim spaces
//        } else {
//            this.correct_answer = new String[]{correct_answer};
//        }
//    }
//
//    public Long getQuesId() {
//        return quesId;
//    }
//
//    public void setQuesId(Long quesId) {
//        this.quesId = quesId;
//    }
//
//    public String getContent() {
//        return content;
//    }
//
//    public void setContent(String content) {
//        this.content = content;
//    }
//
//    public String getImage() {
//        return image;
//    }
//
//    public void setImage(String image) {
//        this.image = image;
//    }
//
//    public String getOption1() {
//        return option1;
//    }
//
//    public void setOption1(String option1) {
//        this.option1 = option1;
//    }
//
//    public String getOption2() {
//        return option2;
//    }
//
//    public void setOption2(String option2) {
//        this.option2 = option2;
//    }
//
//    public String getOption3() {
//        return option3;
//    }
//
//    public void setOption3(String option3) {
//        this.option3 = option3;
//    }
//
//    public String getOption4() {
//        return option4;
//    }
//
//    public void setOption4(String option4) {
//        this.option4 = option4;
//    }
//
////    public String getAnswer() {
////        return answer;
////    }
////    public void setAnswer(String answer) {
////        this.answer = answer;
////    }
//
//    public Quiz getQuiz() {
//        return quiz;
//    }
//
//    public void setQuiz(Quiz quiz) {
//        this.quiz = quiz;
//    }
//}








package com.exam.model.exam;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "questions")
public class Questions {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long quesId;

    @Column(length = 5000)
    private String content;

    private String image;

    // ── MCQ / TRUE_FALSE options (null for MATCHING) ───────────────────────────
    private String option1;
    private String option2;
    private String option3;
    private String option4;

    // ── Answer storage ─────────────────────────────────────────────────────────
    // MCQ / TRUE_FALSE  → stores selected option text e.g. ["True"] or ["option2"]
    // MATCHING          → stores correct answer values in pairOrder e.g. ["Not Found","OK","Forbidden"]
    @Convert(converter = StringArrayConverter.class)
    @Column(columnDefinition = "TEXT")
    private String[] correct_answer;

    @Transient
    private String[] givenAnswer;   // Student's submitted answer (not persisted)

    // ── Question type ──────────────────────────────────────────────────────────
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private QuestionType questionType = QuestionType.MCQ;

    // ── Matching pairs (only populated when questionType = MATCHING) ───────────
    @OneToMany(mappedBy = "question", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("pairOrder ASC")
    private List<MatchingPair> matchingPairs = new ArrayList<>();

    // ── Relationship ───────────────────────────────────────────────────────────
    @ManyToOne(cascade = CascadeType.MERGE)
    private Quiz quiz;

    // ── Constructors ───────────────────────────────────────────────────────────

    public Questions() {}

    public Questions(String[] givenAnswer) {
        this.givenAnswer = givenAnswer;
    }

    /** Full constructor for MCQ / TRUE_FALSE */
    public Questions(Long quesId, String content, String image,
                     String option1, String option2,
                     String option3, String option4,
                     String[] correct_answer, String[] givenAnswer,
                     QuestionType questionType, Quiz quiz) {
        this.quesId = quesId;
        this.content = content;
        this.image = image;
        this.option1 = option1;
        this.option2 = option2;
        this.option3 = option3;
        this.option4 = option4;
        this.correct_answer = correct_answer;
        this.givenAnswer = givenAnswer;
        this.questionType = questionType;
        this.quiz = quiz;
    }

    // ── Helper: build a TRUE_FALSE question easily ─────────────────────────────
    public static Questions trueFalse(String content, boolean correctIsTrue, Quiz quiz) {
        Questions q = new Questions();
        q.setContent(content);
        q.setOption1("True");
        q.setOption2("False");
        q.setcorrect_answer(new String[]{ correctIsTrue ? "True" : "False" });
        q.setQuestionType(QuestionType.TRUE_FALSE);
        q.setQuiz(quiz);
        return q;
    }

    // ── Answer helpers ─────────────────────────────────────────────────────────

    /** Accepts comma-separated string or plain value and splits into array */
    public void setAnswer(String correct_answer) {
        if (correct_answer != null && correct_answer.contains(",")) {
            this.correct_answer = correct_answer.split("\\s*,\\s*");
        } else {
            this.correct_answer = new String[]{ correct_answer };
        }
    }

    public void setcorrect_answer(String[] correct_answer) { this.correct_answer = correct_answer; }
    public String[] getcorrect_answer() { return correct_answer; }

    public String[] getGivenAnswer() { return givenAnswer; }
    public void setGivenAnswer(String[] givenAnswer) { this.givenAnswer = givenAnswer; }

    // ── Getters & Setters ──────────────────────────────────────────────────────

    public Long getQuesId() { return quesId; }
    public void setQuesId(Long quesId) { this.quesId = quesId; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getImage() { return image; }
    public void setImage(String image) { this.image = image; }

    public String getOption1() { return option1; }
    public void setOption1(String option1) { this.option1 = option1; }

    public String getOption2() { return option2; }
    public void setOption2(String option2) { this.option2 = option2; }

    public String getOption3() { return option3; }
    public void setOption3(String option3) { this.option3 = option3; }

    public String getOption4() { return option4; }
    public void setOption4(String option4) { this.option4 = option4; }

    public QuestionType getQuestionType() { return questionType; }
    public void setQuestionType(QuestionType questionType) { this.questionType = questionType; }

    public List<MatchingPair> getMatchingPairs() { return matchingPairs; }
    public void setMatchingPairs(List<MatchingPair> matchingPairs) { this.matchingPairs = matchingPairs; }

    public Quiz getQuiz() { return quiz; }
    public void setQuiz(Quiz quiz) { this.quiz = quiz; }
}