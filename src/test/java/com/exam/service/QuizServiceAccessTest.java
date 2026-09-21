package com.exam.service;

import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.exam.Category;
import com.exam.model.exam.Program;
import com.exam.model.exam.Quiz;
import com.exam.repository.Registered_coursesRepository;
import com.exam.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * A student may only access a quiz if (a) their program is one the quiz is open to, and
 * (b) they are registered for the course (category) the quiz belongs to. Covers the direct
 * /quiz/:qid link path, which bypasses the "my registered courses" picker the normal UI uses.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QuizServiceAccessTest {

    @Mock UserRepository               userRepository;
    @Mock Registered_coursesRepository registeredCoursesRepository;

    @InjectMocks QuizService service;

    User student;
    Category course;
    Quiz quiz;

    @BeforeEach
    void setUp() {
        student = new User();
        student.setId(1L);
        student.setRole(Role.NORMAL);
        student.setUsername("ada");

        course = new Category();
        course.setCid(50L);

        quiz = new Quiz();
        quiz.setqId(10L);
        quiz.setCategory(course);
    }

    @Test
    void unenrolledStudentIsDenied() {
        when(registeredCoursesRepository.countByCategoryAndUser(course, student)).thenReturn(0L);

        assertThatThrownBy(() -> service.assertStudentMayAccess(quiz, student))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(e.getReason()).isEqualTo("You are not enrolled in the course this quiz belongs to");
                });
    }

    @Test
    void enrolledStudentIsAllowed() {
        when(registeredCoursesRepository.countByCategoryAndUser(course, student)).thenReturn(1L);
        service.assertStudentMayAccess(quiz, student);   // does not throw
    }

    @Test
    void quizWithNoCourseIsOpenRegardlessOfEnrollment() {
        quiz.setCategory(null);
        service.assertStudentMayAccess(quiz, student);   // nothing to be enrolled in — does not throw
    }

    @Test
    void programRestrictionIsCheckedBeforeEnrollmentSoItsMessageWins() {
        Program allowed = Program.builder().id(99L).name("Other Program").code("OTH").durationYears(4)
                .department(null).build();
        quiz.setPrograms(Set.of(allowed));
        student.setProgram(null);   // student has no program at all

        assertThatThrownBy(() -> service.assertStudentMayAccess(quiz, student))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getReason()).isEqualTo("This quiz is not available for your program"));
    }

    @Test
    void staffAndAnonymousCallersAreNeverBlocked() {
        // no mocks stubbed — if these called through to the repositories they would NPE / return defaults incorrectly
        User lecturer = new User();
        lecturer.setRole(Role.LECTURER);
        service.assertStudentMayAccess(quiz, lecturer);
        service.assertStudentMayAccess(quiz, null);
    }

    @Test
    void filterForCallerHidesQuizzesForCoursesTheStudentIsNotRegisteredFor() {
        Category otherCourse = new Category();
        otherCourse.setCid(51L);
        Quiz notEnrolled = new Quiz();
        notEnrolled.setqId(11L);
        notEnrolled.setCategory(otherCourse);

        when(userRepository.findByUsername("ada")).thenReturn(Optional.of(student));
        when(registeredCoursesRepository.countByCategoryAndUser(course, student)).thenReturn(1L);
        when(registeredCoursesRepository.countByCategoryAndUser(otherCourse, student)).thenReturn(0L);

        Principal principal = () -> "ada";
        List<Quiz> visible = service.filterForCaller(List.of(quiz, notEnrolled), principal);

        assertThat(visible).containsExactly(quiz);
    }
}
