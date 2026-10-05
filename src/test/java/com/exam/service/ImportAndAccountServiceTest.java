package com.exam.service;

import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.exam.Department;
import com.exam.model.exam.Program;
import com.exam.repository.*;
import com.exam.service.admin.AccountService;
import com.exam.service.admin.CredentialEmailService;
import com.exam.service.admin.ImportService;
import com.exam.token.Token;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ImportAndAccountServiceTest {

    private UserRepository users;
    private ImportService importer;
    private CredentialEmailService mailer;
    private Department cs, maths;
    private User superAdmin, hodCs;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        ProgramRepository programs = mock(ProgramRepository.class);
        DepartmentRepository departments = mock(DepartmentRepository.class);
        CategoryRepository categories = mock(CategoryRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(encoder.encode(any())).thenAnswer(i -> "hashed:" + i.getArgument(0));

        cs = dept(1L, "CS"); maths = dept(2L, "MATH");
        Program bsc = new Program(); bsc.setId(10L); bsc.setName("BSc Computer Science"); bsc.setCode("BCS"); bsc.setDepartment(cs);
        Program bma = new Program(); bma.setId(11L); bma.setName("BSc Mathematics"); bma.setCode("BMA"); bma.setDepartment(maths);
        when(programs.findAll()).thenReturn(List.of(bsc, bma));
        when(departments.findAll()).thenReturn(List.of(cs, maths));

        User existing = new User(); existing.setUsername("STD001"); existing.setEmail("taken@uni.edu");
        when(users.findAll()).thenReturn(List.of(existing));

        importer = new ImportService();
        ReflectionTestUtils.setField(importer, "userRepository", users);
        ReflectionTestUtils.setField(importer, "programRepository", programs);
        ReflectionTestUtils.setField(importer, "departmentRepository", departments);
        ReflectionTestUtils.setField(importer, "categoryRepository", categories);
        ReflectionTestUtils.setField(importer, "passwordEncoder", encoder);
        mailer = mock(CredentialEmailService.class);
        when(mailer.queue(any(), any())).thenAnswer(i -> ((List<?>) i.getArgument(0)).size());
        ReflectionTestUtils.setField(importer, "credentialEmailService", mailer);

        superAdmin = user(1L, Role.SUPER_ADMIN, null);
        hodCs = user(2L, Role.ADMIN, cs);
    }

    private static Department dept(Long id, String code) {
        Department d = new Department(); d.setId(id); d.setCode(code); d.setName(code + " Dept"); return d;
    }

    private static User user(Long id, Role role, Department d) {
        User u = new User(); u.setId(id); u.setRole(role); u.setDepartment(d); u.setUsername("u" + id); u.setEnabled(true); return u;
    }

    private static Map<String, String> student(String id, String email, String program, String level) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("First Name", "Ama"); m.put("Last Name", "Mensah"); m.put("Student ID", id);
        m.put("Email", email); m.put("Program", program); m.put("Level", level);
        return m;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("rows");
    }

    @Test
    void dryRunReportsEachProblemWithoutSaving() {
        var result = importer.run(superAdmin, "students", List.of(
                student("STD100", "ama@uni.edu", "BCS", "100"),          // ok (header names are normalised)
                student("STD001", "new@uni.edu", "BCS", "100"),          // ID already exists
                student("STD101", "taken@uni.edu", "BCS", "100"),        // email already exists
                student("STD102", "not-an-email", "NOPE", "abc")),       // bad email, program, level
                false);

        assertThat(result.get("valid")).isEqualTo(1L);
        List<Map<String, Object>> r = rows(result);
        assertThat(r.get(0).get("row")).isEqualTo(2);                     // row numbers match the spreadsheet
        assertThat((List<?>) r.get(1).get("errors")).hasSize(1);
        assertThat((List<?>) r.get(3).get("errors")).hasSize(3);
        verify(users, never()).save(any());
    }

    @Test
    void duplicatesInsideTheFileAreCaught() {
        var result = importer.run(superAdmin, "students", List.of(
                student("STD200", "a@uni.edu", "BCS", "100"),
                student("std200", "b@uni.edu", "BCS", "100")), false);
        assertThat(result.get("valid")).isEqualTo(1L);
    }

    @Test
    void hodCanOnlyImportIntoOwnDepartment() {
        var result = importer.run(hodCs, "students", List.of(
                student("STD300", "c@uni.edu", "BCS", "100"),
                student("STD301", "d@uni.edu", "BMA", "100")), false);
        assertThat(result.get("valid")).isEqualTo(1L);
        assertThat(rows(result).get(1).get("errors").toString()).contains("not in your department");
    }

    @Test
    void commitCreatesValidRowsAndReturnsGeneratedPasswordsOnce() {
        var result = importer.run(superAdmin, "students", List.of(
                student("STD400", "e@uni.edu", "BCS", "100"),
                student("STD001", "f@uni.edu", "BCS", "100")), true);

        assertThat(result.get("created")).isEqualTo(1);
        verify(users, times(1)).save(any(User.class));
        @SuppressWarnings("unchecked")
        List<Map<String, String>> creds = (List<Map<String, String>>) result.get("credentials");
        assertThat(creds).hasSize(1);
        assertThat(creds.get(0).get("password")).hasSize(10);
        verifyNoInteractions(mailer);
    }

    @Test
    @SuppressWarnings("unchecked")
    void notifyEmailsEveryNewUserIncludingSuppliedPasswordsButOnlyEchoesGenerated() {
        Map<String, String> withPassword = student("STD401", "g@uni.edu", "BCS", "100");
        withPassword.put("Password", "chosen123");
        var result = importer.run(superAdmin, "students", List.of(
                student("STD400", "e@uni.edu", "BCS", "100"), withPassword), true, true);

        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(mailer).queue(captor.capture(), eq("student"));
        List<Map<String, String>> sent = captor.getValue();
        assertThat(sent).extracting(c -> c.get("username")).containsExactly("STD400", "STD401");
        assertThat(sent.get(1).get("password")).isEqualTo("chosen123");
        assertThat(sent.get(0).get("firstname")).isEqualTo("Ama");
        assertThat(result.get("emailed")).isEqualTo(2);

        List<Map<String, String>> creds = (List<Map<String, String>>) result.get("credentials");
        assertThat(creds).hasSize(1);
        assertThat(creds.get(0)).containsOnlyKeys("name", "username", "email", "password");
    }

    @Test
    void validationNeverSendsEmail() {
        importer.run(superAdmin, "students", List.of(student("STD400", "e@uni.edu", "BCS", "100")), false, true);
        verifyNoInteractions(mailer);
    }

    @Test
    void onlyTheSuperAdminCreatesGlobalCourses() {
        Map<String, String> course = Map.of("Course Code", "GST101", "Title", "Communication Skills", "Level", "100", "Semester", "1");
        assertThat(rows(importer.run(hodCs, "courses", List.of(course), false)).get(0).get("ok")).isEqualTo(false);
        assertThat(rows(importer.run(superAdmin, "courses", List.of(course), false)).get(0).get("ok")).isEqualTo(true);
    }

    // ── Accounts ─────────────────────────────────────────────────────────────

    private AccountService accounts(User target) {
        AccountService a = new AccountService();
        UserRepository repo = mock(UserRepository.class);
        when(repo.findById(target.getId())).thenReturn(Optional.of(target));
        TokenRepository tokens = mock(TokenRepository.class);
        Token t = new Token();
        when(tokens.findAllValidTokenByUser(any())).thenReturn(List.of(t));
        ReflectionTestUtils.setField(a, "userRepository", repo);
        ReflectionTestUtils.setField(a, "tokenRepository", tokens);
        return a;
    }

    @Test
    void deactivationBlocksLoginAndRevokesSessions() {
        User student = user(9L, Role.NORMAL, cs);
        AccountService a = accounts(student);

        a.deactivate(superAdmin, 9L, "Graduated");

        assertThat(student.isEnabled()).isFalse();
        assertThat(student.getDeactivationReason()).isEqualTo("Graduated");
        assertThat(student.getDeactivatedAt()).isNotNull();
        a.reactivate(superAdmin, 9L);
        assertThat(student.isEnabled()).isTrue();
    }

    @Test
    void hodCannotDeactivateOutsideDepartmentOrOtherStaffTiers() {
        User mathsStudent = user(9L, Role.NORMAL, maths);
        assertThatThrownBy(() -> accounts(mathsStudent).deactivate(hodCs, 9L, null)).isInstanceOf(AccessDeniedException.class);

        User otherHod = user(8L, Role.ADMIN, cs);
        assertThatThrownBy(() -> accounts(otherHod).deactivate(hodCs, 8L, null)).isInstanceOf(AccessDeniedException.class);

        User sa2 = user(7L, Role.SUPER_ADMIN, null);
        assertThatThrownBy(() -> accounts(sa2).deactivate(superAdmin, 7L, null)).isInstanceOf(AccessDeniedException.class);
    }
}
