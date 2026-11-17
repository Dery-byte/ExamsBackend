package com.exam.controller;

import com.exam.exception.ErrorMessage;
import com.exam.model.User;
import com.exam.service.comms.CurrentUserService;
import com.exam.service.materials.MaterialCalendar;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Optional;

/**
 * Term dates for the current academic session (Academic Settings): when each semester / term starts
 * and how many weeks it has. Staff read them (course materials show real week dates); the Super
 * Admin sets them.
 */
@RestController
@RequestMapping("/api/academic/terms")
public class TermCalendarController {

    @Autowired private CurrentUserService currentUserService;
    @Autowired private MaterialCalendar calendar;

    public record TermRequest(LocalDate startDate, Integer weeks) {}

    @GetMapping
    public ResponseEntity<?> list() {
        return ResponseEntity.ok(calendar.termDates());
    }

    @PutMapping("/{semester}")
    public ResponseEntity<?> save(@PathVariable int semester, @RequestBody TermRequest req) {
        Optional<User> u = currentUserService.current();
        if (u.isEmpty()) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorMessage("Please sign in."));
        try {
            return ResponseEntity.ok(calendar.saveTerm(u.get(), semester, req == null ? null : req.startDate(), req == null ? null : req.weeks()));
        } catch (AccessDeniedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ErrorMessage(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ErrorMessage(e.getMessage()));
        }
    }
}
