package com.exam.service.monitoring;

import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.monitoring.DeveloperEmail;
import com.exam.repository.DeveloperEmailRepository;
import com.exam.repository.UserRepository;
import com.exam.service.AuthenticationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Developers are whoever is in the developer_email table; they sign in with emailed codes. */
class DeveloperAuthServiceTest {

    private DeveloperAuthService service;
    private AuthenticationService auth;
    private final List<User> users = new ArrayList<>();
    private final List<DeveloperEmail> table = new ArrayList<>();
    private long nextId = 1;

    @BeforeEach
    void setUp() {
        service = new DeveloperAuthService();
        UserRepository userRepo = mock(UserRepository.class);
        DeveloperEmailRepository devRepo = mock(DeveloperEmailRepository.class);
        auth = mock(AuthenticationService.class);
        ReflectionTestUtils.setField(service, "userRepository", userRepo);
        ReflectionTestUtils.setField(service, "developerEmailRepository", devRepo);
        ReflectionTestUtils.setField(service, "authenticationService", auth);
        ReflectionTestUtils.setField(service, "passwordEncoder", new BCryptPasswordEncoder());

        when(devRepo.findAll()).thenAnswer(a -> new ArrayList<>(table));
        when(userRepo.findByEmail(anyString())).thenAnswer(a -> users.stream().filter(u -> a.getArgument(0).equals(u.getEmail())).findFirst());
        when(userRepo.findByUsername(anyString())).thenAnswer(a -> users.stream().filter(u -> a.getArgument(0).equals(u.getUsername())).findFirst());
        when(userRepo.save(any(User.class))).thenAnswer(a -> {
            User u = a.getArgument(0);
            if (u.getId() == null) { u.setId(nextId++); users.add(u); }
            return u;
        });
        when(auth.startSession(any(User.class))).thenReturn("jwt-token");
    }

    private void addRow(String email, String name) {
        DeveloperEmail d = new DeveloperEmail();
        d.setEmail(email);
        d.setName(name);
        table.add(d);
    }

    @Test
    void aListedEmailSignsInAndGetsItsAccountOnFirstSignIn() {
        addRow(" Dev@Example.com ", "Ama Mensah");
        assertThat(service.developerEmails()).containsExactly("dev@example.com");

        String code = service.issue("dev@example.com");
        assertThat(service.verify("DEV@example.com", code)).isEqualTo("jwt-token");
        assertThat(users).singleElement().satisfies(u -> {
            assertThat(u.getRole()).isEqualTo(Role.DEVELOPER);
            assertThat(u.getEmail()).isEqualTo("dev@example.com");
            assertThat(u.getFirstname()).isEqualTo("Ama Mensah");
        });
        // one use only; a second sign-in reuses the same account
        assertThatThrownBy(() -> service.verify("dev@example.com", code)).isInstanceOf(IllegalArgumentException.class);
        service.verify("dev@example.com", service.issue("dev@example.com"));
        assertThat(users).hasSize(1);
    }

    @Test
    void wrongCodesRunOutAfterFiveTries() {
        addRow("dev@example.com", null);
        String code = service.issue("dev@example.com");
        String wrong = code.equals("000000") ? "111111" : "000000";
        for (int i = 0; i < 5; i++)
            assertThatThrownBy(() -> service.verify("dev@example.com", wrong)).hasMessageContaining("wrong or has expired");
        assertThatThrownBy(() -> service.verify("dev@example.com", code)).isInstanceOf(IllegalArgumentException.class);
        verify(auth, never()).startSession(any());
    }

    @Test
    void emailsNotInTheTableNeverGetIn() {
        service.requestCode("someone@else.com");   // silently ignored
        assertThatThrownBy(() -> service.verify("someone@else.com", "123456")).isInstanceOf(IllegalArgumentException.class);
        verify(auth, never()).startSession(any());
    }

    @Test
    void removingTheRowStopsSignIn() {
        addRow("dev@example.com", null);
        String code = service.issue("dev@example.com");
        table.clear();
        assertThat(service.isDeveloper("dev@example.com")).isFalse();
        assertThatThrownBy(() -> service.verify("dev@example.com", code)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anEmailOwnedByAnotherRoleCantSignInAsDeveloper() {
        User student = new User();
        student.setEmail("stu@example.com");
        student.setRole(Role.NORMAL);
        users.add(student);
        addRow("stu@example.com", null);
        String code = service.issue("stu@example.com");
        assertThatThrownBy(() -> service.verify("stu@example.com", code)).hasMessageContaining("can't be used as a developer");
        verify(auth, never()).startSession(any());
    }
}
