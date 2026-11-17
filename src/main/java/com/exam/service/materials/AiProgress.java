package com.exam.service.materials;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the AI is doing for each submission right now, so the marking screen can show it step by step
 * (getting the work ready → turning slides into pages → sent to the AI, reading and marking → saving).
 * Kept in memory: it only matters while a request runs; the result itself is saved on the submission.
 */
@Component
public class AiProgress {

    public enum Stage { QUEUED, PREPARING, CONVERTING, READING, SAVING }

    private record State(Stage stage, String provider, String detail, long startedAt, long stageAt) {}

    private final Map<Long, State> running = new ConcurrentHashMap<>();

    public boolean running(Long submissionId) {
        return running.containsKey(submissionId);
    }

    public void start(Long submissionId, String provider) {
        long now = System.currentTimeMillis();
        running.put(submissionId, new State(Stage.QUEUED, provider, null, now, now));
    }

    public void stage(Long submissionId, Stage stage, String detail) {
        running.computeIfPresent(submissionId, (k, s) -> new State(stage, s.provider(), detail, s.startedAt(), System.currentTimeMillis()));
    }

    public void finish(Long submissionId) {
        running.remove(submissionId);
    }

    /** stage, provider, detail, seconds since it started and since this step began; null when nothing runs. */
    public Map<String, Object> view(Long submissionId) {
        State s = submissionId == null ? null : running.get(submissionId);
        if (s == null) return null;
        long now = System.currentTimeMillis();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stage", s.stage().name());
        m.put("provider", s.provider());
        m.put("detail", s.detail());
        m.put("seconds", (now - s.startedAt()) / 1000);
        m.put("stageSeconds", (now - s.stageAt()) / 1000);
        return m;
    }
}
