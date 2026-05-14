package com.exam.DTO;
import lombok.Data;

/**
 * Student-facing DTO for Section B — Theory (long-answer) questions.
 *
 * Only contains fields actually consumed by the frontend template.
 * Any internal fields on the TheoryQuestions entity (e.g. model answers,
 * grading notes) are intentionally excluded for security and payload efficiency.
 *
 * Frontend field mapping:
 *  question.quesNo      → question number label (th-qno div)
 *  question.question    → question body text (th-qtext div)
 *  question.marks       → marks badge (*ngIf="question.marks")
 *  question.givenAnswer → textarea [(ngModel)] — plain String, not a List
 *  question.prefix      → drives prefixes[] array, chip selector, prev/next nav
 *  question.compulsory  → drives COMPULSORY alert banner + chip styling
 */
@Data
public class TheoryQuestionResponseDTO {

    /** Unique identifier */
    private Long quesId;

    /** Question number shown in the UI e.g. "1", "1a", "Q2" */
    private String quesNo;

    /** The question body text */
    private String question;

    /**
     * Marks allocated to this question.
     * Nullable — template guards with *ngIf="question.marks"
     * so null means the badge is simply hidden.
     */
    // In TheoryQuestionResponseDTO
    private String marks; // "3 marks" sent as-is, frontend already appends nothing extra

    /**
     * Student's typed answer.
     * Plain String — bound directly to <textarea> via [(ngModel)].
     * Null = unanswered (textarea will show placeholder).
     */
    private String givenAnswer;

    /**
     * Section group prefix e.g. "A", "B", "1", "2".
     * Used to build the prefixes[] array in the component,
     * drive Prev/Next page navigation, and render prefix chips.
     */
    private String prefix;
    private String evaluationCriteria;


    /**
     * Whether this question's group is compulsory.
     * Drives:
     *  - isGroupCompulsory(prefix) → COMPULSORY alert banner
     *  - [class.comp] on prefix chips
     *  - prevents toggling via (click) guard: !isGroupCompulsory(prefix)
     */
    private boolean compulsory;
}