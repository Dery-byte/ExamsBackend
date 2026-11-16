package com.exam.service.admin;

import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.exam.AttemptStatus;
import com.exam.model.exam.Department;
import com.exam.model.exam.Program;
import com.exam.repository.QuizAttemptRepository;
import com.exam.repository.TokenRepository;
import com.exam.repository.UserRepository;
import com.exam.token.Token;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BulkPasswordServiceTest {

    private UserRepository users;
    private TokenRepository tokens;
    private QuizAttemptRepository attempts;
    private CredentialEmailService mailer;
    private BulkPasswordService service;

    private Department cs, maths;
    private Program bcs;
    private User superAdmin, hodCs, csStudent, mathsStudent, csLecturer, otherHod, deactivated, writing;
    private final Map<Long, User> byId = new HashMap<>();

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        tokens = mock(TokenRepository.class);
        attempts = mock(QuizAttemptRepository.class);
        mailer = mock(CredentialEmailService.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(encoder.encode(any())).thenAnswer(i -> "hashed:" + i.getArgument(0));
        when(mailer.queue(any(), any())).thenAnswer(i -> ((List<?>) i.getArgument(0)).size());
        when(tokens.findAllValidTokenByUser(anyInt())).thenReturn(List.of());

        service = new BulkPasswordService();
        ReflectionTestUtils.setField(service, "userRepository", users);
        ReflectionTestUtils.setField(service, "tokenRepository", tokens);
        ReflectionTestUtils.setField(service, "attemptRepository", attempts);
        ReflectionTestUtils.setField(service, "passwordEncoder", encoder);
        ReflectionTestUtils.setField(service, "credentialEmailService", mailer);

        cs = dept(1L); maths = dept(2L);
        bcs = new Program(); bcs.setId(10L); bcs.setName("BSc Computer Science"); bcs.setDepartment(cs);
        Program bma = new Program(); bma.setId(11L); bma.setName("BSc Mathematics"); bma.setDepartment(maths);

        superAdmin = user(1L, Role.SUPER_ADMIN, null, null);
        hodCs = user(2L, Role.ADMIN, cs, null);
        otherHod = user(3L, Role.ADMIN, maths, null);
        csStudent = user(10L, Role.NORMAL, null, bcs); csStudent.setEmail("ama@uni.edu"); csStudent.setMustChangePassword(true);
        mathsStudent = user(11L, Role.NORMAL, null, bma); mathsStudent.setEmail("kofi@uni.edu");
        csLecturer = user(20L, Role.LECTURER, cs, null);
        deactivated = user(12L, Role.NORMAL, null, bcs); deactivated.setEnabled(false);
        writing = user(13L, Role.NORMAL, null, bcs);

        when(users.findByRole(Role.NORMAL)).thenReturn(List.of(csStudent, mathsStudent, deactivated, writing));
        when(users.findByRole(Role.LECTURER)).thenReturn(List.of(csLecturer));
        when(users.findById(anyLong())).thenAnswer(i -> Optional.ofNullable(byId.get((Long) i.getArgument(0))));
        when(attempts.existsByUser_IdAndStatusAndStartedAtAfter(eq(13L), eq(AttemptStatus.IN_PROGRESS), any())).thenReturn(true);
    }

    private static Department dept(Long id) {
        Department d = new Department(); d.setId(id); d.setName("Dept " + id); return d;
    }

    private User user(Long id, Role role, Department d, Program p) {
        User u = new User(); u.setId(id); u.setRole(role); u.setDepartment(d); u.setProgram(p);
        u.setUsername("U" + id); u.setFirstname("First" + id); u.setLastname("Last"); u.setEnabled(true); u.setPassword("old");
        byId.put(id, u);
        return u;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> out, String key) {
        return (List<Map<String, Object>>) out.get(key);
    }

    @Test
    void superAdminSeesEveryActiveStudentAndCanFilterByProgram() {
        var all = service.accounts(superAdmin, "students", null, null, null);
        assertThat(all).extracting(m -> m.get("id")).containsExactlyInAnyOrder(10L, 11L, 13L);   // not the deactivated one
        var onlyBcs = service.accounts(superAdmin, "students", 10L, null, null);
        assertThat(onlyBcs).extracting(m -> m.get("id")).containsExactlyInAnyOrder(10L, 13L);
        assertThat(all.stream().filter(m -> m.get("id").equals(10L)).findFirst().orElseThrow().get("temporaryPassword")).isEqualTo(true);
    }

    @Test
    void hodSeesOnlyTheirOwnDepartment() {
        assertThat(service.accounts(hodCs, "students", null, null, null)).extracting(m -> m.get("id")).containsExactlyInAnyOrder(10L, 13L);
        assertThat(service.accounts(hodCs, "lecturers", null, null, null)).extracting(m -> m.get("id")).containsExactly(20L);
        assertThat(service.accounts(otherHod, "lecturers", null, null, null)).isEmpty();
    }

    @Test
    void resetGivesNewTemporaryPasswordsAndForcesAChange() {
        csStudent.setMustChangePassword(false);
        var out = service.reset(hodCs, List.of(10L, 20L), false);

        var creds = list(out, "credentials");
        assertThat(creds).hasSize(2);
        String pw = (String) creds.get(0).get("password");
        assertThat(pw).hasSize(10);
        assertThat(csStudent.getPassword()).isEqualTo("hashed:" + pw);
        assertThat(csStudent.getMustChangePassword()).isTrue();
        assertThat(creds.get(0).get("emailed")).isEqualTo(false);
        assertThat(creds.get(0).get("password")).isNotEqualTo(creds.get(1).get("password"));
        verify(mailer, never()).queue(any(), any());
    }

    @Test
    void hodCannotResetSomeoneOutsideTheirDepartment() {
        assertThatThrownBy(() -> service.reset(hodCs, List.of(11L), false)).isInstanceOf(AccessDeniedException.class);
        assertThat(mathsStudent.getPassword()).isEqualTo("old");
    }

    @Test
    void adminsAndSuperAdminsAreNeverResetHere() {
        var out = service.reset(superAdmin, List.of(3L, 1L), false);
        assertThat(list(out, "credentials")).isEmpty();
        assertThat(list(out, "skipped")).hasSize(2);
        assertThat(otherHod.getPassword()).isEqualTo("old");
    }

    @Test
    void skipsDeactivatedAccountsAndStudentsWritingAnExam() {
        var out = service.reset(superAdmin, List.of(12L, 13L, 10L, 999L), false);
        assertThat(list(out, "credentials")).extracting(m -> m.get("id")).containsExactly(10L);
        assertThat(list(out, "skipped")).extracting(m -> m.get("id")).containsExactly(12L, 13L, 999L);
        assertThat(writing.getPassword()).isEqualTo("old");
    }

    @Test
    void emailsOnlyThoseWithAnAddressWhenAsked() {
        var out = service.reset(superAdmin, List.of(10L, 20L), true);   // the lecturer has no email
        assertThat(out.get("emailed")).isEqualTo(1);
        assertThat(list(out, "credentials")).extracting(m -> m.get("emailed")).containsExactly(true, false);
    }

    @Test
    void signsOutOpenSessions() {
        Token t = Token.builder().token("abc").revoked(false).expired(false).build();
        when(tokens.findAllValidTokenByUser(10)).thenReturn(List.of(t));
        service.reset(superAdmin, List.of(10L), false);
        assertThat(t.isRevoked()).isTrue();
        assertThat(t.isExpired()).isTrue();
    }

    @Test
    void refusesOtherRolesAndOversizedRequests() {
        assertThatThrownBy(() -> service.reset(csLecturer, List.of(10L), false)).isInstanceOf(AccessDeniedException.class);
        List<Long> many = new ArrayList<>();
        for (long i = 0; i <= BulkPasswordService.MAX_PER_REQUEST; i++) many.add(1000 + i);
        assertThatThrownBy(() -> service.reset(superAdmin, many, false)).isInstanceOf(IllegalArgumentException.class);
    }
}
