package com.exam.service.materials;

import com.exam.model.materials.StoredFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The text inside a stored file (PDF, Word, PowerPoint, plain text), taken out once and kept beside the
 * files ({dir}/text/{sha256}.txt): the same content always gives the same text, so copies share it.
 * Photos and scanned pages have no text; they give an empty string.
 */
@Component
public class SubmissionText {

    private static final Logger log = LoggerFactory.getLogger(SubmissionText.class);
    private static final int MAX_CHARS = 200_000;

    @Autowired private MaterialStorage storage;

    public String of(StoredFile f) {
        Path cache = storage.root().resolve("text").resolve(f.getSha256() + ".txt");
        try {
            if (Files.isRegularFile(cache)) return Files.readString(cache, StandardCharsets.UTF_8);
        } catch (Exception ignored) { }
        String text = "";
        try {
            String t = AssignmentAiMarker.extractText(new AssignmentAiMarker.WorkFile(f.getOriginalName(), f.getContentType(), storage.read(f)));
            if (t != null) text = t.length() > MAX_CHARS ? t.substring(0, MAX_CHARS) : t;
        } catch (Exception e) {
            log.debug("[Materials] No text from a .{} file: {}", MaterialStorage.extension(f.getOriginalName()), e.getMessage());
        }
        try {
            Files.createDirectories(cache.getParent());
            Files.writeString(cache, text, StandardCharsets.UTF_8);
        } catch (Exception ignored) { }
        return text;
    }
}
