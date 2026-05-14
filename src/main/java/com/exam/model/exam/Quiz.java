package com.exam.model.exam;

import com.exam.helper.CustomLocalDateDeserializer;
import com.exam.model.QuizStatus;
import com.exam.model.QuizType;
import com.exam.model.User;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.google.api.client.util.DateTime;
import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(name = "quiz")
public class Quiz {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long qId;
    private String title;
    @Column(length =  100)
    private String description;
    private Double maxMarks;
    @Column(length =  50, nullable = false)
    private String quizTime;
    @Column(length =  50)
    private Integer numberOfQuestions;
    private  boolean active = false;
    private boolean attempted=false;


// ================= PROCTORING POLICY =================

    @Enumerated(EnumType.STRING)
    @Column(name = "violation_action")
    private ViolationAction violationAction = ViolationAction.NONE;

    @Column(name = "auto_submit_countdown_seconds")
    private Integer autoSubmitCountdownSeconds = 10;

    @Column(name = "proctoring_enabled")
    private Boolean proctoringEnabled = false;

    @Column(name = "max_violations")
    private Integer maxViolations = 3;

    @Column(name = "delay_seconds")
    private Integer delaySeconds = 30;

    @Column(name = "delay_increment_on_repeat")
    private Boolean delayIncrementOnRepeat = true;

    @Column(name = "delay_multiplier")
    private Double delayMultiplier = 1.5;

    @Column(name = "max_delay_seconds")
    private Integer maxDelaySeconds = 120;


    @Column(name = "enable_watermark")
    private Boolean enableWatermark = true;

    @Column(name = "enable_fullscreen_lock")
    private Boolean enableFullscreenLock = true;

    @Column(name = "enable_screenshot_blocking")
    private Boolean enableScreenshotBlocking = true;

    @Column(name = "enable_dev_tools_blocking")
    private Boolean enableDevToolsBlocking = true;

// =====================================================






    @Column(length =  50, nullable = false)
    private String quizpassword;
    //add ...



    //Trying to check for one quiz attempts

    @OneToMany(mappedBy = "quiz",cascade = CascadeType.ALL)
    @JsonIgnore
    private Set<Report> reports = new LinkedHashSet<>();
    @Enumerated(EnumType.STRING)

    private QuizStatus status = QuizStatus.OPEN;

    private QuizType quizType;

    @JsonFormat(pattern = "HH:mm")
    private LocalTime startTime;


    @JsonDeserialize(using = CustomLocalDateDeserializer.class)
    @JsonFormat(pattern = "yyyy-MM-dd") // Format for JSON serialization
    private LocalDate quizDate;




    @ManyToOne(fetch = FetchType.EAGER) // Each quiz belongs to one user
    @JoinColumn(name = "user_id")
    @JsonIgnore
    private User user;








    public QuizType getQuizType() {
        return quizType;
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalTime startTime) {
        this.startTime = startTime;
    }

    public void setQuizType(QuizType quizType) {
        this.quizType = quizType;
    }

    @ManyToOne(fetch = FetchType.EAGER, cascade = CascadeType.MERGE)
    private Category category;

     @OneToMany(mappedBy = "quiz", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
     @JsonIgnore
     private Set<Questions> questions = new HashSet<>();

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public String getQuizTime() {
        return quizTime;
    }

    public void setQuizTime(String quizTime) {
        this.quizTime = quizTime;
    }

    public Set<Report> getReports() {
        return reports;
    }

    public void setReports(Set<Report> reports) {
        this.reports = reports;
    }

    public LocalDate getQuizDate() {
        return quizDate;
    }

    public void setQuizDate(LocalDate quizDate) {
        this.quizDate = quizDate;
    }

    public Integer getAutoSubmitCountdownSeconds() {
        return autoSubmitCountdownSeconds;
    }

    public void setAutoSubmitCountdownSeconds(Integer autoSubmitCountdownSeconds) {
        this.autoSubmitCountdownSeconds = autoSubmitCountdownSeconds;
    }

    public Quiz(Long qId, String title, String description, Double maxMarks, Integer numberOfQuestions, boolean active, boolean attempted, String quizpassword, Set<Report> reports, Category category, Set<Questions> questions, QuizType quizType, LocalDate quizDate, LocalTime startTime) {
        this.qId = qId;
        this.title = title;
        this.description = description;
        this.maxMarks = maxMarks;
        this.numberOfQuestions = numberOfQuestions;
        this.active = active;
        this.attempted = attempted;
        this.quizpassword = quizpassword;
        this.reports = reports;
        this.category = category;
        this.questions = questions;
        this.quizType = quizType;
        this.quizDate = quizDate;
        this.startTime = startTime;
    }
    @JsonCreator
    public Quiz() {
    }


    public boolean isAttempted() {
        return attempted;
    }
    public void setAttempted(boolean attempted) {
        this.attempted = attempted;
    }
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

    public String getQuizpassword() {
        return quizpassword;
    }

    public void setQuizpassword(String quizpassword) {
        this.quizpassword = quizpassword;
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

    public Category getCategory() {
        return category;
    }

    public QuizStatus getStatus() {
        return status;
    }

    public void setStatus(QuizStatus status) {
        this.status = status;
    }

    public void setCategory(Category category) {
        this.category = category;
    }

    public Set<Questions> getQuestions() {
        return questions;
    }

    public void setQuestions(Set<Questions> questions) {
        this.questions = questions;
    }


    public ViolationAction getViolationAction() {
        return violationAction;
    }

    public void setViolationAction(ViolationAction violationAction) {
        this.violationAction = violationAction;
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

    public Boolean getProctoringEnabled() {
        return proctoringEnabled;
    }

    public void setProctoringEnabled(Boolean proctoringEnabled) {
        this.proctoringEnabled = proctoringEnabled;
    }

    public void setStartTimeFromAMPM(String ampmTime) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("hh:mm a");
        this.startTime = LocalTime.parse(ampmTime.toUpperCase(), formatter);
    }

    @JsonProperty("startTimeAMPM")
    public String getStartTimeAMPM() {
        if (startTime == null) return null;
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("hh:mm a");
        return startTime.format(formatter).toLowerCase();
    }

    @JsonProperty("startTime24H")
    public String getStartTime24H() {
        if (startTime == null) return null;
        return startTime.toString(); // "12:34:00"
    }
}
