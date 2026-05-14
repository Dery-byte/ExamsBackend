package com.exam.DTO;

import com.exam.model.QuizStatus;
import com.exam.model.QuizType;
import com.exam.model.exam.Quiz;
import com.exam.model.exam.ViolationAction;

import java.time.LocalDate;
import java.time.LocalTime;

public class QuizDTO {

    private Long qId;
    private String title;
    private String description;
    private Double maxMarks;
    private String quizTime;
    private Integer numberOfQuestions;
    private boolean active;
    private boolean attempted;
    private QuizStatus status;
    private QuizType quizType;
    private LocalTime startTime;
    private LocalDate quizDate;
    private CategoryDTO category;



    // ADD THESE NEW FIELDS
    private ViolationAction violationAction = ViolationAction.NONE;
    private Integer autoSubmitCountdownSeconds;
    private Boolean proctoringEnabled;
    private Integer maxViolations;
    private Integer delaySeconds;
    private Boolean delayIncrementOnRepeat;
    private Double delayMultiplier;
    private Integer maxDelaySeconds;
    private Boolean enableWatermark;
    private Boolean enableFullscreenLock;
    private Boolean enableScreenshotBlocking;
    private Boolean enableDevToolsBlocking;


    // Constructors
    public QuizDTO() {
    }

    public QuizDTO(Quiz quiz) {
        this.qId = quiz.getqId();
        this.title = quiz.getTitle();
        this.description = quiz.getDescription();
        this.maxMarks = quiz.getMaxMarks();
        this.quizTime = quiz.getQuizTime();
        this.numberOfQuestions = quiz.getNumberOfQuestions();
        this.active = quiz.isActive();
        this.attempted = quiz.isAttempted();
        this.status = quiz.getStatus();
        this.quizType = quiz.getQuizType();
        this.startTime = quiz.getStartTime();
        this.quizDate = quiz.getQuizDate();

        this.violationAction = quiz.getViolationAction();
        this.autoSubmitCountdownSeconds = quiz.getAutoSubmitCountdownSeconds();
        this.proctoringEnabled = quiz.getProctoringEnabled();
        this.maxViolations = quiz.getMaxViolations();
        this.delaySeconds = quiz.getDelaySeconds();
        this.delayIncrementOnRepeat = quiz.getDelayIncrementOnRepeat();
        this.delayMultiplier = quiz.getDelayMultiplier();
        this.maxDelaySeconds = quiz.getMaxDelaySeconds();
        this.enableWatermark = quiz.getEnableWatermark();
        this.enableFullscreenLock = quiz.getEnableFullscreenLock();
        this.enableScreenshotBlocking = quiz.getEnableScreenshotBlocking();
        this.enableDevToolsBlocking = quiz.getEnableDevToolsBlocking();

        // Convert category to DTO (avoid sending user info)
        if (quiz.getCategory() != null) {
            this.category = new CategoryDTO(quiz.getCategory());
        }
    }

    // Getters and Setters
    public Long getqId() {
        return qId;
    }

    public void setqId(Long qId) {
        this.qId = qId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Double getMaxMarks() {
        return maxMarks;
    }

    public void setMaxMarks(Double maxMarks) {
        this.maxMarks = maxMarks;
    }

    public String getQuizTime() {
        return quizTime;
    }

    public void setQuizTime(String quizTime) {
        this.quizTime = quizTime;
    }

    public Integer getNumberOfQuestions() {
        return numberOfQuestions;
    }

    public void setNumberOfQuestions(Integer numberOfQuestions) {
        this.numberOfQuestions = numberOfQuestions;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public boolean isAttempted() {
        return attempted;
    }

    public void setAttempted(boolean attempted) {
        this.attempted = attempted;
    }

    public QuizStatus getStatus() {
        return status;
    }

    public void setStatus(QuizStatus status) {
        this.status = status;
    }

    public QuizType getQuizType() {
        return quizType;
    }

    public void setQuizType(QuizType quizType) {
        this.quizType = quizType;
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalTime startTime) {
        this.startTime = startTime;
    }

    public LocalDate getQuizDate() {
        return quizDate;
    }

    public void setQuizDate(LocalDate quizDate) {
        this.quizDate = quizDate;
    }

    public CategoryDTO getCategory() {
        return category;
    }

    public void setCategory(CategoryDTO category) {
        this.category = category;
    }


    public ViolationAction getViolationAction() {
        return violationAction;
    }

    public void setViolationAction(ViolationAction violationAction) {
        this.violationAction = violationAction;
    }

    public Integer getAutoSubmitCountdownSeconds() {
        return autoSubmitCountdownSeconds;
    }

    public void setAutoSubmitCountdownSeconds(Integer autoSubmitCountdownSeconds) {
        this.autoSubmitCountdownSeconds = autoSubmitCountdownSeconds;
    }

    public Boolean getProctoringEnabled() {
        return proctoringEnabled;
    }

    public void setProctoringEnabled(Boolean proctoringEnabled) {
        this.proctoringEnabled = proctoringEnabled;
    }

    public Integer getMaxViolations() {
        return maxViolations;
    }

    public void setMaxViolations(Integer maxViolations) {
        this.maxViolations = maxViolations;
    }

    public Integer getDelaySeconds() {
        return delaySeconds;
    }

    public void setDelaySeconds(Integer delaySeconds) {
        this.delaySeconds = delaySeconds;
    }

    public Boolean getDelayIncrementOnRepeat() {
        return delayIncrementOnRepeat;
    }

    public void setDelayIncrementOnRepeat(Boolean delayIncrementOnRepeat) {
        this.delayIncrementOnRepeat = delayIncrementOnRepeat;
    }

    public Double getDelayMultiplier() {
        return delayMultiplier;
    }

    public void setDelayMultiplier(Double delayMultiplier) {
        this.delayMultiplier = delayMultiplier;
    }

    public Integer getMaxDelaySeconds() {
        return maxDelaySeconds;
    }

    public void setMaxDelaySeconds(Integer maxDelaySeconds) {
        this.maxDelaySeconds = maxDelaySeconds;
    }

    public Boolean getEnableWatermark() {
        return enableWatermark;
    }

    public void setEnableWatermark(Boolean enableWatermark) {
        this.enableWatermark = enableWatermark;
    }

    public Boolean getEnableFullscreenLock() {
        return enableFullscreenLock;
    }

    public void setEnableFullscreenLock(Boolean enableFullscreenLock) {
        this.enableFullscreenLock = enableFullscreenLock;
    }

    public Boolean getEnableScreenshotBlocking() {
        return enableScreenshotBlocking;
    }

    public void setEnableScreenshotBlocking(Boolean enableScreenshotBlocking) {
        this.enableScreenshotBlocking = enableScreenshotBlocking;
    }

    public Boolean getEnableDevToolsBlocking() {
        return enableDevToolsBlocking;
    }

    public void setEnableDevToolsBlocking(Boolean enableDevToolsBlocking) {
        this.enableDevToolsBlocking = enableDevToolsBlocking;
    }
}