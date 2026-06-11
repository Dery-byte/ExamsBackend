package com.exam.model.exam;

/**
 * Supported LLM providers for subjective answer evaluation.
 * Admin/Lecturer selects one per quiz.
 */
public enum LlmProvider {

    /** OpenAI GPT (default) */
    GPT,

    /** Google Gemini */
    GEMINI,

    /** DeepSeek (OpenAI-compatible API) */
    DEEPSEEK,

    /** Anthropic Claude (requires separate key) */
    CLAUDE
}
