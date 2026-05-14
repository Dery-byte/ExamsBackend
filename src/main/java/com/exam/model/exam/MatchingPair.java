package com.exam.model.exam;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

@Entity
@Table(name = "matching_pair")
public class MatchingPair {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    @Column(nullable = false, length = 1000)
    private String prompt;      // Left side  e.g. "HTTP 404"

    @Column(nullable = false, length = 1000)
    private String answer;      // Correct right side  e.g. "Not Found"

    @Column(nullable = false)
    private Integer pairOrder = 0;   // Controls display order

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "question_id", nullable = false)
    @JsonIgnore
    private Questions question;

    public MatchingPair() {}

    public MatchingPair(String prompt, String answer, Integer pairOrder, Questions question) {
        this.prompt = prompt;
        this.answer = answer;
        this.pairOrder = pairOrder;
        this.question = question;
    }

    // ── Getters & Setters ──────────────────────────────────────────────────────

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }

    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }

    public Integer getPairOrder() { return pairOrder; }
    public void setPairOrder(Integer pairOrder) { this.pairOrder = pairOrder; }

    public Questions getQuestion() { return question; }
    public void setQuestion(Questions question) { this.question = question; }
}