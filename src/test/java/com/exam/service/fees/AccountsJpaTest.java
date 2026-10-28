package com.exam.service.fees;

import com.exam.model.Residency;
import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.exam.Department;
import com.exam.model.exam.Program;
import com.exam.model.fees.CashUp;
import com.exam.model.fees.FeeArrears;
import com.exam.model.fees.FinanceDuty;
import com.exam.repository.*;
import com.exam.service.SystemSettingService;
import com.exam.service.academic.AcademicSessionService;
import com.exam.service.boarding.BoardingSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Year;
import java.util.List;
import java.util.Map;

import static com.exam.service.fees.FinanceJpaTest.spread;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The accounts office, Phase C, on H2: concessions (money paid beyond the new fee becomes credit),
 * refunds of credit only, closing a session into arrears (paid first; reopening only while unpaid and
 * current), opening balances, write-offs, results holds counting arrears, cash-ups and who may cancel.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.datasource.url=jdbc:h2:mem:accounts;MODE=MySQL;NON_KEYWORDS=USER,LEVEL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.show-sql=false",
})
@Import({FeeDues.class, FeeLedger.class, ConcessionService.class, YearEndService.class, RefundService.class, CashBookService.class,
        ReceiptService.class, BoardingSettings.class, com.exam.service.academic.DocumentVerificationService.class,
        com.exam.service.features.FeatureService.class, com.exam.service.academic.InstitutionService.class,
        com.exam.service.academic.ThemeService.class, com.exam.service.admin.MaintenanceService.class, FeeScheduleService.class,
        FeePaymentService.class, ResultsHoldService.class, AcademicSessionService.class, SystemSettingService.class,
        com.exam.service.comms.CurrentUserService.class})
class AccountsJpaTest {

    @MockBean PaystackClient paystack;

    @Autowired FeeScheduleService schedules;
    @Autowired FeePaymentService payments;
    @Autowired FeeDues dues;
    @Autowired ConcessionService concessions;
    @Autowired YearEndService yearEnd;
    @Autowired RefundService refunds;
    @Autowired CashBookService cashBook;
    @Autowired ResultsHoldService holds;
    @Autowired AcademicSessionService sessions;
    @Autowired SystemSettingService settings;
    @Autowired UserRepository users;
    @Autowired DepartmentRepository departments;
    @Autowired ProgramRepository programs;

    private Program cs;
    private User ama, kofi, esi, bursar, cashier, sa;
    private int next = 1;

    @BeforeEach
    void setUp() {
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
        bursar = user("bur", Role.ACCOUNTANT, FinanceDuty.BURSAR);
        cashier = user("cash", Role.ACCOUNTANT, FinanceDuty.CASHIER);
        sa = user("sa", Role.SUPER_ADMIN, null);
        settings.updateSetting(SystemSettingService.FEES_VISIBLE_STUDENT, "true");
    }

    private User student(Residency r) {
        User u = user("3000" + next++, Role.NORMAL, null);
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
        return ((Number) schedules.save(new FeeScheduleService.ScheduleRequest(cs.getId(), 200, null, false, new BigDecimal(amount),
                null, null, null, null, term), "Bursar").get(0).get("id")).longValue();
    }

    private Map<String, Object> pay(User s, String amount, String method, User by) {
        return payments.recordManual(new FeePaymentService.ManualPaymentRequest(s.getId(), new BigDecimal(amount), method, null, null, null), by);
    }

    private BigDecimal credit(User s) { return dues.account(s).credit(); }

    // ── Concessions and refunds ──────────────────────────────────────────────

    @Test
    void aConcessionLowersTheFeeAndWhatWasPaidBeyondItCanBeRefunded() {
        schedules.save(new FeeScheduleService.ScheduleRequest(cs.getId(), 200, null, true, null, List.of(
                new FeeScheduleService.ComponentRequest("Tuition", new BigDecimal("1000")),
                new FeeScheduleService.ComponentRequest("Library", new BigDecimal("200"))), null, null, null, 1), "Bursar");
        Map<String, Object> paid = pay(ama, "1200", "CASH", cashier);

        assertThatThrownBy(() -> concessions.grant(cashier, new ConcessionService.ConcessionRequest(ama.getId(), null, "STAFF_CHILD", null,
                "PERCENT", new BigDecimal("50"), List.of("Tuition"), null, "Mother teaches Maths"))).isInstanceOf(AccessDeniedException.class);
        Map<String, Object> c = concessions.grant(bursar, new ConcessionService.ConcessionRequest(ama.getId(), null, "STAFF_CHILD", null,
                "PERCENT", new BigDecimal("50"), List.of("Tuition"), null, "Mother teaches Maths"));
        FeeDues.Due due = dues.dues(ama).get(0);
        assertThat(due.gross()).isEqualByComparingTo("1200");
        assertThat(due.amount()).isEqualByComparingTo("700");                        // half the tuition off
        assertThat(due.components()).extracting(x -> x.getAmount().toPlainString()).containsExactly("500.00", "200.00");
        assertThat(due.paid()).isEqualByComparingTo("700");                          // the rest went back …
        assertThat(credit(ama)).isEqualByComparingTo("500");                         // … to credit

        assertThatThrownBy(() -> refunds.refund(bursar, new RefundService.RefundRequest(ama.getId(), new BigDecimal("600"), "CASH", null, "Overpaid")))
                .hasMessageContaining("Only GHS 500.00");
        Map<String, Object> r = refunds.refund(bursar, new RefundService.RefundRequest(ama.getId(), new BigDecimal("500"), "CASH", "Mother", "Staff child discount"));
        assertThat(r.get("number")).isEqualTo("RF-" + Year.now().getValue() + "-000001");
        assertThat(credit(ama)).isEqualByComparingTo("0");

        Long paymentId = ((Number) paid.get("id")).longValue();
        assertThatThrownBy(() -> payments.voidManual(paymentId, "Wrong", bursar)).hasMessageContaining("Cancel the refund first");
        refunds.voidRefund(bursar, ((Number) r.get("id")).longValue(), "Paid by mistake");
        assertThat(credit(ama)).isEqualByComparingTo("500");

        concessions.revoke(bursar, ((Number) c.get("id")).longValue(), new ConcessionService.RevokeRequest("Mother left the school"));
        assertThat(dues.dues(ama).get(0).paid()).isEqualByComparingTo("1200");     // credit back on the full fee
        assertThat(credit(ama)).isEqualByComparingTo("0");
    }

    @Test
    void aFixedAmountConcessionComesOffEachTermAndNeverBelowZero() {
        term(1, "300");
        term(2, "300");
        kofi.setCurrentSemester(2);
        users.save(kofi);
        concessions.grant(bursar, new ConcessionService.ConcessionRequest(kofi.getId(), null, "SIBLING", "Second child",
                "AMOUNT", new BigDecimal("100"), null, null, "Brother in Level 400"));
        concessions.grant(bursar, new ConcessionService.ConcessionRequest(kofi.getId(), null, "WAIVER", "Hardship",
                "AMOUNT", new BigDecimal("500"), null, 2, "Family hardship"));
        assertThat(dues.dues(kofi)).extracting(d -> d.amount().toPlainString()).containsExactly("200.00", "0.00");
        assertThat(dues.dues(kofi).get(1).concessions()).containsExactly("Second child", "Hardship");
    }

    // ── Closing a session ────────────────────────────────────────────────────

    @Test
    void closingASessionCarriesEveryTermUnpaidIntoArrearsWhichArePaidFirst() {
        term(1, "1000");
        term(2, "800");
        pay(ama, "300", "CASH", cashier);
        Long sessionId = sessions.current().getId();

        assertThatThrownBy(() -> yearEnd.close(cashier, sessionId)).isInstanceOf(AccessDeniedException.class);
        Map<String, Object> closed = yearEnd.close(bursar, sessionId);
        assertThat(closed.get("students")).isEqualTo(3);
        assertThat((BigDecimal) closed.get("total")).isEqualByComparingTo("5100");    // 1500 + 1800 + 1800: every term, due or not
        assertThat(dues.dues(ama)).isEmpty();
        assertThat(dues.account(ama).arrearsBalance()).isEqualByComparingTo("1500");

        // Reopening is possible while nothing is paid on the arrears and the session is current
        yearEnd.reopen(bursar, sessionId);
        assertThat(dues.account(ama).arrears()).isEmpty();
        assertThat(dues.dues(ama)).hasSize(1);
        yearEnd.close(bursar, sessionId);

        Map<String, Object> p = pay(ama, "200", "CASH", cashier);
        assertThat(spread(p).get(0)).startsWith("ARREARS");
        assertThatThrownBy(() -> yearEnd.reopen(bursar, sessionId)).hasMessageContaining("already paid towards these arrears");

        // Results held until earlier years are paid too
        settings.updateSetting(SystemSettingService.FEES_RESULTS_HOLD, "true");
        holds.save(new ResultsHoldService.HoldRequest(cs.getId(), "FULL", null, null, true, true, null), "Bursar");
        Map<String, Object> hold = holds.check(ama, java.util.EnumSet.allOf(ResultsHoldService.Document.class));
        assertThat((BigDecimal) hold.get("amountToRelease")).isEqualByComparingTo("1300");
        assertThat((String) hold.get("message")).contains("earlier years");

        // A newer session made current: the closed one stays closed
        Map<String, Object> next = sessions.create("2099/2100", LocalDate.of(2099, 8, 1), LocalDate.of(2100, 7, 31), true);
        assertThat(dues.account(ama).arrearsBalance()).isEqualByComparingTo("1300");
        assertThatThrownBy(() -> yearEnd.reopen(bursar, sessionId)).hasMessageContaining("no longer the current session");
        assertThat(next.get("current")).isEqualTo(true);
    }

    // ── Opening balances and write-offs ──────────────────────────────────────

    @Test
    void openingBalancesAreCheckedThenImportedAndCanBeWrittenOff() {
        List<Map<String, String>> sheet = List.of(
                Map.of("Student ID", ama.getUsername(), "Amount", "450", "Note", "From the 2024 ledger"),
                Map.of("Student ID", "nobody", "Amount", "10"),
                Map.of("Student ID", kofi.getUsername(), "Amount", "abc"),
                Map.of("Student ID", ama.getUsername(), "Amount", "5"));
        Map<String, Object> check = yearEnd.importOpening(bursar, sheet, false);
        assertThat(check.get("ok")).isEqualTo(1);
        assertThat(check.get("errors")).isEqualTo(3);
        assertThat(dues.account(ama).arrears()).isEmpty();                               // only checked

        yearEnd.importOpening(bursar, sheet, true);
        assertThat(dues.account(ama).arrearsBalance()).isEqualByComparingTo("450");
        Map<String, Object> again = yearEnd.importOpening(bursar, List.of(Map.of("Admission No.", ama.getUsername(), "Balance", "400")), true);
        assertThat(((List<Map<String, Object>>) again.get("rows")).get(0).get("status")).isEqualTo("REPLACE");
        assertThat(dues.account(ama).arrearsBalance()).isEqualByComparingTo("400");

        pay(ama, "100", "CASH", cashier);
        assertThatThrownBy(() -> yearEnd.setOpening(bursar, ama.getId(), new BigDecimal("50"), null)).hasMessageContaining("Already paying");

        Long id = dues.account(ama).arrears().get(0).arrears().getId();
        Map<String, Object> off = yearEnd.writeOff(bursar, id, new YearEndService.ReasonRequest("Family relocated, untraceable"));
        assertThat((BigDecimal) off.get("writtenOff")).isEqualByComparingTo("300");
        assertThat(dues.account(ama).arrearsBalance()).isEqualByComparingTo("0");
        assertThat(dues.account(ama).arrears().get(0).arrears().getStatus()).isEqualTo(FeeArrears.Status.WRITTEN_OFF);
        yearEnd.restore(bursar, id);
        assertThat(dues.account(ama).arrearsBalance()).isEqualByComparingTo("300");
    }

    // ── Cash-ups and who may cancel ──────────────────────────────────────────

    @Test
    void cashiersHandInWhatTheyTookAndCanCancelOnlyTheirOwnUntilThen() {
        term(1, "5000");
        Map<String, Object> cash = pay(ama, "500", "CASH", cashier);
        Map<String, Object> bank = pay(kofi, "200", "BANK_TRANSFER", cashier);
        Map<String, Object> others = pay(esi, "50", "CASH", bursar);

        assertThatThrownBy(() -> payments.voidManual(((Number) others.get("id")).longValue(), "x", cashier)).isInstanceOf(AccessDeniedException.class);
        payments.voidManual(((Number) bank.get("id")).longValue(), "Entered for the wrong student", cashier);   // own, not handed in

        Map<String, Object> mine = cashBook.mine(cashier);
        assertThat((BigDecimal) mine.get("expectedCash")).isEqualByComparingTo("500");
        assertThat(mine.get("payments")).isEqualTo(1);
        assertThatThrownBy(() -> cashBook.cashUp(cashier, new CashBookService.CashUpRequest(new BigDecimal("480"), null)))
                .hasMessageContaining("Add a note");
        Map<String, Object> up = cashBook.cashUp(cashier, new CashBookService.CashUpRequest(new BigDecimal("480"), "GHS 20 short, a torn note"));
        assertThat((BigDecimal) up.get("difference")).isEqualByComparingTo("-20");
        assertThatThrownBy(() -> cashBook.cashUp(cashier, new CashBookService.CashUpRequest(BigDecimal.ZERO, null))).hasMessageContaining("nothing to hand in");

        Long cashId = ((Number) cash.get("id")).longValue();
        assertThatThrownBy(() -> payments.voidManual(cashId, "x", cashier)).hasMessageContaining("already handed in");
        Long upId = ((Number) up.get("id")).longValue();
        assertThatThrownBy(() -> cashBook.confirm(cashier, upId, null)).isInstanceOf(AccessDeniedException.class);
        assertThat(cashBook.confirm(bursar, upId, null).get("status")).isEqualTo(CashUp.Status.CONFIRMED.name());

        // The bursar's own cash-up is confirmed by someone else
        Map<String, Object> mineToo = cashBook.cashUp(bursar, new CashBookService.CashUpRequest(new BigDecimal("50"), null));
        Long bursarUp = ((Number) mineToo.get("id")).longValue();
        assertThatThrownBy(() -> cashBook.confirm(bursar, bursarUp, null)).hasMessageContaining("your own cash-up");
        assertThat(cashBook.confirm(sa, bursarUp, null).get("status")).isEqualTo("CONFIRMED");

        payments.voidManual(cashId, "Cheque bounced", bursar);                             // the bursar still can
        Map<String, Object> day = cashBook.day(bursar, null);
        assertThat((BigDecimal) ((Map<String, Object>) day.get("totals")).get("received")).isEqualByComparingTo("50");
        assertThat((List<?>) day.get("voids")).hasSize(2);
    }
}
