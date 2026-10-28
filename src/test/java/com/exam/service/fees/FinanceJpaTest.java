package com.exam.service.fees;

import com.exam.model.Residency;
import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.boarding.ResidencyMode;
import com.exam.model.exam.Department;
import com.exam.model.exam.Program;
import com.exam.model.fees.FeePayment;
import com.exam.model.fees.FinanceDuty;
import com.exam.repository.*;
import com.exam.service.SystemSettingService;
import com.exam.service.academic.AcademicSessionService;
import com.exam.service.boarding.BoardingSettings;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Year;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * The accounts office (Phase A) on H2: fees per term owed oldest first, whole-year and term fees never
 * mixed, items for boarders / day students, numbered receipts with verification codes (revoked on void),
 * results holds across terms, the bursar / cashier split, accountant logins and the dashboard.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.datasource.url=jdbc:h2:mem:finance;MODE=MySQL;NON_KEYWORDS=USER,LEVEL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.show-sql=false",
})
@Import({FeeDues.class, ReceiptService.class, BoardingSettings.class, com.exam.service.academic.DocumentVerificationService.class,
        com.exam.service.features.FeatureService.class, com.exam.service.academic.InstitutionService.class, com.exam.service.academic.ThemeService.class,
        com.exam.service.admin.MaintenanceService.class, FeeScheduleService.class, FeePaymentService.class, FeeLedger.class, CashBookService.class, ResultsHoldService.class,
        FinanceDashboardService.class, FinanceStaffService.class, AcademicSessionService.class, SystemSettingService.class,
        com.exam.service.comms.CurrentUserService.class})
class FinanceJpaTest {

    @MockBean PaystackClient paystack;
    @MockBean PasswordEncoder passwordEncoder;
    @MockBean com.exam.service.admin.CredentialEmailService credentialEmails;

    @Autowired FeeScheduleService schedules;
    @Autowired FeePaymentService payments;
    @Autowired FeeDues dues;
    @Autowired ResultsHoldService holds;
    @Autowired FinanceDashboardService dashboard;
    @Autowired FinanceStaffService staff;
    @Autowired BoardingSettings boarding;
    @Autowired SystemSettingService settings;
    @Autowired UserRepository users;
    @Autowired DepartmentRepository departments;
    @Autowired ProgramRepository programs;
    @Autowired FeePaymentRepository paymentRepo;
    @Autowired DocumentVerificationRepository verifications;

    private final ObjectMapper mapper = new ObjectMapper();
    private Program cs;
    private User ama, kofi, esi, sa;
    int next = 1;

    @BeforeEach
    void setUp() throws Exception {
        Department d = new Department();
        d.setName("Computing");
        d.setCode("CMP");
        d = departments.save(d);
        cs = new Program();
        cs.setName("Computer Science");
        cs.setCode("CS");
        cs.setDurationYears(4);
        cs.setDepartment(d);
        cs.setEnabled(true);
        cs = programs.save(cs);
        ama = student(Residency.BOARDING);
        kofi = student(Residency.DAY);
        esi = student(null);
        sa = user("sa", Role.SUPER_ADMIN, null);
        settings.updateSetting(SystemSettingService.FEES_VISIBLE_STUDENT, "true");
        when(paystack.isConfigured()).thenReturn(true);
        when(paystack.initialize(anyString(), anyLong(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(mapper.readTree("{\"authorization_url\":\"https://checkout.paystack.com/abc\"}"));
        when(paystack.validSignature(any(), any())).thenReturn(true);
        when(passwordEncoder.encode(anyString())).thenReturn("hash");
    }

    private User student(Residency r) {
        int n = next++;
        User u = user("1000" + n, Role.NORMAL, null);
        u.setProgram(cs);
        u.setCurrentLevel(200);
        u.setCurrentSemester(1);
        u.setResidency(r);
        return users.save(u);
    }

    private User user(String username, Role role, FinanceDuty duty) {
        User u = new User();
        u.setUsername(username);
        u.setEmail(username + "@example.com");
        u.setFirstname(username);
        u.setLastname("Test");
        u.setPassword("x");
        u.setRole(role);
        u.setEnabled(true);
        u.setFinanceDuty(duty);
        return users.save(u);
    }

    private Long term(int term, String amount) {
        Map<String, Object> s = schedules.save(new FeeScheduleService.ScheduleRequest(cs.getId(), 200, null, false, new BigDecimal(amount),
                null, null, null, null, term), "Bursar").get(0);
        return ((Number) s.get("id")).longValue();
    }

    private Map<String, Object> cash(User s, String amount, Long scheduleId) {
        return payments.recordManual(new FeePaymentService.ManualPaymentRequest(s.getId(), new BigDecimal(amount), "CASH", null, null, null, scheduleId), "Cashier One");
    }

    // ── Terms ────────────────────────────────────────────────────────────────

    @Test
    void termFeesAreOwedOldestFirstAndLaterTermsAreComingUp() {
        Long t1 = term(1, "1200"), t2 = term(2, "900");
        assertThat(dues.dues(ama)).extracting(d -> d.schedule().getId()).containsExactly(t1);
        assertThat(dues.upcoming(ama)).extracting(d -> d.schedule().getId()).containsExactly(t2);

        ama.setCurrentSemester(2);
        users.save(ama);
        assertThat(dues.dues(ama)).extracting(d -> d.schedule().getId()).containsExactly(t1, t2);   // term 1 unpaid is still owed in term 2

        assertThat(spread(cash(ama, "1000", null))).containsExactly("FEE " + t1 + " 1000.00");     // no fee chosen: the oldest first
        assertThat(spread(cash(ama, "300", null))).containsExactly("FEE " + t1 + " 200.00", "FEE " + t2 + " 100.00");

        Map<String, Object> statement = payments.statement(ama);
        assertThat((BigDecimal) statement.get("totalFees")).isEqualByComparingTo("2100");
        assertThat((BigDecimal) statement.get("totalBalance")).isEqualByComparingTo("800");
        assertThat((List<?>) statement.get("dues")).hasSize(2);
    }

    @Test
    void aClassHasAWholeYearFeeOrTermFeesNeverBoth() {
        schedules.save(new FeeScheduleService.ScheduleRequest(cs.getId(), 200, null, false, new BigDecimal("3000"), null, null, null, null, null), "Bursar");
        assertThatThrownBy(() -> term(1, "1000")).hasMessageContaining("already has a whole-year fee");
        schedules.save(new FeeScheduleService.ScheduleRequest(cs.getId(), 300, null, false, new BigDecimal("1000"), null, null, null, null, 1), "Bursar");
        assertThatThrownBy(() -> schedules.save(new FeeScheduleService.ScheduleRequest(cs.getId(), 300, null, false, new BigDecimal("3000"),
                null, null, null, null, null), "Bursar")).hasMessageContaining("already has fees per term");
        assertThatThrownBy(() -> schedules.save(new FeeScheduleService.ScheduleRequest(cs.getId(), 300, null, false, new BigDecimal("3000"),
                null, null, null, null, 3), "Bursar")).hasMessageContaining("has 2 semesters a year");
        // The overview shows one period at a time
        Map<String, Object> year = schedules.overview(null, null), first = schedules.overview(null, 1);
        assertThat(levelRow(year, 200).get("schedule")).isNotNull();
        assertThat(levelRow(first, 200).get("schedule")).isNull();
        assertThat(levelRow(first, 300).get("schedule")).isNotNull();
        assertThat(levelRow(first, 300).get("setAs")).isEqualTo("TERM");
    }

    /** "FEE 12 200.00", "BILL 3 50.00", "CREDIT 10.00": where a payment went. */
    @SuppressWarnings("unchecked")
    static List<String> spread(Map<String, Object> payment) {
        return ((List<Map<String, Object>>) payment.get("allocations")).stream().map(a -> a.get("kind")
                + (a.containsKey("scheduleId") ? " " + a.get("scheduleId") : a.containsKey("billId") ? " " + a.get("billId") : "")
                + " " + ((BigDecimal) a.get("amount")).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> levelRow(Map<String, Object> overview, int level) {
        List<Map<String, Object>> levels = (List<Map<String, Object>>) ((List<Map<String, Object>>) overview.get("programs")).get(0).get("levels");
        return levels.stream().filter(l -> l.get("level").equals(level)).findFirst().orElseThrow();
    }

    // ── Boarders and day students ────────────────────────────────────────────

    @Test
    void boardersAndDayStudentsPayOnlyTheirItems() {
        boarding.setMode(ResidencyMode.BOTH);          // fills unrecorded students with "day" …
        esi.setResidency(null);                         // … so record Esi as not known again
        esi = users.save(esi);
        schedules.save(new FeeScheduleService.ScheduleRequest(cs.getId(), 200, null, true, null, List.of(
                new FeeScheduleService.ComponentRequest("Tuition", new BigDecimal("1000"), "EVERYONE"),
                new FeeScheduleService.ComponentRequest("Boarding and feeding", new BigDecimal("800"), "BOARDERS"),
                new FeeScheduleService.ComponentRequest("School bus", new BigDecimal("200"), "DAY")), null, null, null, 1), "Bursar");

        assertThat(dues.dues(ama).get(0).amount()).isEqualByComparingTo("1800");     // boarder
        assertThat(dues.dues(kofi).get(0).amount()).isEqualByComparingTo("1200");    // day student
        assertThat(dues.dues(esi).get(0).amount()).isEqualByComparingTo("1000");     // not recorded: everyone items only
        assertThat(dues.dues(kofi).get(0).components()).extracting(c -> c.getName()).containsExactly("Tuition", "School bus");

        Map<String, Object> row = levelRow(schedules.overview(null, 1), 200);
        assertThat((BigDecimal) row.get("billed")).isEqualByComparingTo("4000");
        @SuppressWarnings("unchecked") Map<String, Object> fee = (Map<String, Object>) row.get("schedule");
        assertThat((BigDecimal) fee.get("boarderAmount")).isEqualByComparingTo("1800");
        assertThat((BigDecimal) fee.get("dayAmount")).isEqualByComparingTo("1200");

        // A day student can't pay for the boarders' item
        assertThatThrownBy(() -> payments.recordManual(new FeePaymentService.ManualPaymentRequest(kofi.getId(), null, "CASH", null, null,
                List.of("Boarding and feeding"), null), "Cashier")).hasMessageContaining("is not part of this fee");
    }

    // ── Receipts ─────────────────────────────────────────────────────────────

    @Test
    void receiptsAreNumberedInOrderAndVoidingRevokesTheCode() {
        term(1, "1000");
        Map<String, Object> a = cash(ama, "100", null), b = cash(kofi, "200", null);
        int year = Year.now().getValue();
        assertThat(a.get("receiptNo")).isEqualTo("RC-" + year + "-000001");
        assertThat(b.get("receiptNo")).isEqualTo("RC-" + year + "-000002");
        String code = (String) a.get("verificationCode");
        assertThat(code).isNotNull();
        assertThat(verifications.findByCode(code).orElseThrow().getSummary()).contains("RC-" + year + "-000001").contains("Cash");

        payments.voidManual(((Number) a.get("id")).longValue(), "Entered twice", "Bursar");
        assertThat(verifications.findByCode(code).orElseThrow().isRevoked()).isTrue();
        Map<String, Object> c = cash(esi, "50", null);
        assertThat(c.get("receiptNo")).isEqualTo("RC-" + year + "-000003");          // numbers are never reused

        // An online payment gets its number when Paystack confirms it
        String ref = (String) payments.startPayment(ama, new FeePaymentService.PayRequest(new BigDecimal("300"), null)).get("reference");
        assertThat(paymentRepo.findByReference(ref).orElseThrow().getReceiptNo()).isNull();
        payments.handleWebhook(("{\"event\":\"charge.success\",\"data\":{\"id\":7,\"status\":\"success\",\"reference\":\"" + ref
                + "\",\"amount\":30000,\"currency\":\"GHS\",\"channel\":\"card\",\"paid_at\":\"2026-10-06T09:15:02.000Z\"}}").getBytes(StandardCharsets.UTF_8), "sig");
        FeePayment online = paymentRepo.findByReference(ref).orElseThrow();
        assertThat(online.getStatus()).isEqualTo(FeePayment.Status.SUCCESS);
        assertThat(online.getReceiptNo()).startsWith("RC-2026-");
    }

    // ── Results holds across terms ───────────────────────────────────────────

    @Test
    void resultsStayHeldUntilEveryTermOwedSoFarIsPaid() {
        settings.updateSetting(SystemSettingService.FEES_RESULTS_HOLD, "true");
        holds.save(new ResultsHoldService.HoldRequest(cs.getId(), "FULL", null, null, true, true, null), "Bursar");
        term(1, "1000");
        term(2, "500");
        ama.setCurrentSemester(2);
        users.save(ama);
        cash(ama, "1000", null);                                                     // term 1 paid, term 2 not
        Map<String, Object> hold = holds.check(ama, java.util.EnumSet.allOf(ResultsHoldService.Document.class));
        assertThat(hold).isNotNull();
        assertThat((BigDecimal) hold.get("amountToRelease")).isEqualByComparingTo("500");
        cash(ama, "500", null);
        assertThat(holds.isHeld(ama)).isFalse();
    }

    // ── Bursar, cashier, accountant logins, dashboard ────────────────────────

    @Test
    void bursarsAndCashiersHaveDifferentRights() {
        User bursar = user("bur", Role.ACCOUNTANT, FinanceDuty.BURSAR), cashier = user("cash", Role.ACCOUNTANT, FinanceDuty.CASHIER);
        assertThat(FinanceAccess.isBursar(bursar)).isTrue();
        assertThat(FinanceAccess.isBursar(sa)).isTrue();
        assertThat(FinanceAccess.isBursar(cashier)).isFalse();
        assertThat(FinanceAccess.isFinance(cashier)).isTrue();
        assertThatThrownBy(() -> FinanceAccess.requireBursar(cashier)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> FinanceAccess.requireFinance(ama)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void theSuperAdminCreatesAccountantLogins() {
        Map<String, Object> made = staff.create(new FinanceStaffService.StaffRequest("Abena", "Owusu", "ACC01", "abena@school.edu.gh", null, "BURSAR", false));
        assertThat(made.get("password")).asString().hasSize(10);
        User u = users.findByUsername("ACC01").orElseThrow();
        assertThat(u.getRole()).isEqualTo(Role.ACCOUNTANT);
        assertThat(u.getFinanceDuty()).isEqualTo(FinanceDuty.BURSAR);
        assertThat(u.requiresPasswordChange()).isTrue();
        assertThatThrownBy(() -> staff.create(new FinanceStaffService.StaffRequest("X", "Y", "ACC01", "x@y.com", null, "CASHIER", false)))
                .hasMessageContaining("already has an account");
        staff.update(u.getId(), new FinanceStaffService.StaffRequest(null, null, null, null, null, "CASHIER", null));
        assertThat(users.findById(u.getId()).orElseThrow().getFinanceDuty()).isEqualTo(FinanceDuty.CASHIER);
        assertThat(staff.list()).hasSize(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void theDashboardAddsUpTodayAndTheSession() {
        term(1, "1000");
        User cashier = user("cash", Role.ACCOUNTANT, FinanceDuty.CASHIER);
        cash(ama, "400", null);
        payments.recordManual(new FeePaymentService.ManualPaymentRequest(kofi.getId(), new BigDecimal("100"), "BANK_TRANSFER", null, null, null, null), "cash Test");
        Map<String, Object> d = dashboard.dashboard(cashier);
        assertThat((BigDecimal) ((Map<String, Object>) d.get("today")).get("total")).isEqualByComparingTo("500");
        assertThat((BigDecimal) ((Map<String, Object>) d.get("today")).get("mine")).isEqualByComparingTo("100");
        Map<String, Object> s = (Map<String, Object>) d.get("session");
        assertThat((BigDecimal) s.get("owed")).isEqualByComparingTo("3000");
        assertThat((BigDecimal) s.get("outstanding")).isEqualByComparingTo("2500");
        assertThat(s.get("debtors")).isEqualTo(3L);
        assertThatThrownBy(() -> dashboard.dashboard(ama)).isInstanceOf(AccessDeniedException.class);
    }
}
