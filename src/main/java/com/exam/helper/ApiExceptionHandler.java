package com.exam.helper;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Spring Boot hides exception messages by default, so a ResponseStatusException reached the
 * frontend without its reason. This returns it as {status, error, message} so the UI can show it.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handle(ResponseStatusException ex) {
        HttpStatusCode code = ex.getStatusCode();
        HttpStatus known = HttpStatus.resolve(code.value());
        return ResponseEntity.status(code).body(Map.of(
                "status", code.value(),
                "error", known != null ? known.getReasonPhrase() : "Error",
                "message", ex.getReason() != null ? ex.getReason() : ""
        ));
    }
}
