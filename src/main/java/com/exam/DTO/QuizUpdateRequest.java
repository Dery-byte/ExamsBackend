package com.exam.DTO;
import com.exam.model.QuizStatus;
import com.exam.model.QuizType;
import com.exam.model.exam.ViolationAction;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

import java.time.LocalDate;
import java.time.LocalTime;

public class QuizUpdateRequest {
    private Long qId;
    private String title;
    private String description;
    private Double maxMarks;
    private String quizTime;
    private Integer numberOfQuestions;
    private boolean active;
    private String quizpassword;
    private QuizStatus status;
    private QuizType quizType;
    private LocalTime startTime;
    private LocalDate quizDate;
    private Long categoryId; // Only send category ID, not the entire object

    @Enumerated(EnumType.STRING)
    private ViolationAction violationAction = ViolationAction.NONE;
    private Integer autoSubmitCountdownSeconds = 10;

    private Boolean proctoringEnabled = false;

    private Integer maxViolations = 3;

    private Integer delaySeconds = 30;

    private Boolean delayIncrementOnRepeat = true;

    private Double delayMultiplier = 1.5;

    private Integer maxDelaySeconds = 120;

    private Boolean enableWatermark = true;

    private Boolean enableFullscreenLock = true;

    private Boolean enableScreenshotBlocking = true;

    private Boolean enableDevToolsBlocking = true;




    // Constructors
    public QuizUpdateRequest() {
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

    public String getQuizpassword() {
        return quizpassword;
    }

    public void setQuizpassword(String quizpassword) {
        this.quizpassword = quizpassword;
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

    public Long getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(Long categoryId) {
        this.categoryId = categoryId;
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

    public boolean setDelayIncrementOnRepeat(Boolean delayIncrementOnRepeat) {
        this.delayIncrementOnRepeat = delayIncrementOnRepeat;
        return true;
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