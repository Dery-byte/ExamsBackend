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

    /** Permission checks in services (e.g. "You don't manage this quiz", a feature switched off). */
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handle(org.springframework.security.access.AccessDeniedException ex) {
        return body(HttpStatus.FORBIDDEN, ex.getMessage());
    }

    /** Invalid input rejected by a service. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handle(IllegalArgumentException ex) {
        return body(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    /** A student's report cards / transcript are held for unpaid fees; the body says what to pay. */
    @ExceptionHandler(com.exam.service.fees.ResultsHoldService.ResultsHeldException.class)
    public ResponseEntity<Map<String, Object>> handle(com.exam.service.fees.ResultsHoldService.ResultsHeldException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ex.body());
    }

    @ExceptionHandler({ResourceNotFoundException.class, jakarta.persistence.EntityNotFoundException.class})
    public ResponseEntity<Map<String, Object>> notFound(RuntimeException ex) {
        return body(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    private static ResponseEntity<Map<String, Object>> body(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of(
                "status", status.value(),
                "error", status.getReasonPhrase(),
                "message", message != null ? message : ""));
    }

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
