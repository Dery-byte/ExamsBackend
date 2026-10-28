package com.exam.service.fees;

import com.exam.model.Residency;
import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.exam.Department;
import com.exam.model.exam.Program;
import com.exam.model.features.Feature;
import com.exam.model.fees.FeePayment;
import com.exam.model.fees.FinanceDuty;
import com.exam.repository.*;
import com.exam.service.SystemSettingService;
import com.exam.service.academic.AcademicSessionService;
import com.exam.service.boarding.BoardingSettings;
import com.exam.service.comms.NotificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * Student wallets on H2: deposits stay in the wallet (never credit, never swept onto fees), the student
 * spends them on a chosen fee or bill or on everything oldest first, and getting money back goes
 * bursar → Super Admin → accounts office, each a different person, with the amount held meanwhile.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.datasource.url=jdbc:h2:mem:wallet;MODE=MySQL;NON_KEYWORDS=USER,LEVEL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.show-sql=false",
})
@Import({WalletService.class, BillingService.class, FeeDues.class, FeeLedger.class, ConcessionService.class, RefundService.class, CashBookService.class,
        ReceiptService.class, BoardingSettings.class, com.exam.service.academic.DocumentVerificationService.class,
        com.exam.service.features.FeatureService.class, com.exam.service.academic.InstitutionService.class,
        com.exam.service.academic.ThemeService.class, com.exam.service.admin.MaintenanceService.class, FeeScheduleService.class,
        FeePaymentService.class, ResultsHoldService.class, AcademicSessionService.class, SystemSettingService.class,
        com.exam.service.comms.CurrentUserService.class})
class WalletJpaTest {

    @MockBean PaystackClient paystack;
    @MockBean NotificationService notifications;
    @MockBean com.exam.service.boarding.BoardingService boardingService;

    @Autowired WalletService wallets;
    @Autowired BillingService billing;
    @Autowired FeeScheduleService schedules;
    @Autowired FeePaymentService payments;
    @Autowired FeeDues dues;
    @Autowired FeeLedger ledger;
    @Autowired ConcessionService concessions;
    @Autowired RefundService refunds;
    @Autowired SystemSettingService settings;
    @Autowired UserRepository users;
    @Autowired DepartmentRepository departments;
    @Autowired ProgramRepository programs;
    @Autowired FeePaymentRepository paymentRepo;

    private final ObjectMapper mapper = new ObjectMapper();
    private Program cs;
    private User ama, bursar, cashier, sa;
    private int next = 1;

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
        ama = student();
        bursar = user("bur", Role.ACCOUNTANT, FinanceDuty.BURSAR);
        cashier = user("cash", Role.ACCOUNTANT, FinanceDuty.CASHIER);
        sa = user("sa", Role.SUPER_ADMIN, null);
        settings.updateSetting(SystemSettingService.FEES_VISIBLE_STUDENT, "true");
        settings.updateSetting(Feature.STUDENT_WALLET.settingKey(), "true");
        when(paystack.isConfigured()).thenReturn(true);
        when(paystack.initialize(anyString(), anyLong(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(mapper.readTree("{\"authorization_url\":\"https://checkout.paystack.com/abc\"}"));
        when(paystack.validSignature(any(), any())).thenReturn(true);
    }

    private User student() {
        User u = user("4000" + next++, Role.NORMAL, null);
        u.setProgram(cs);
        u.setCurrentLevel(200);
        u.setCurrentSemester(1);
        u.setResidency(Residency.DAY);
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

    private Map<String, Object> deposit(User s, String amount) {
        return wallets.deposit(cashier, s.getId(), new WalletService.DepositRequest(new BigDecimal(amount), "CASH", null, null));
    }

    private BigDecimal money(Map<String, Object> view, String key) { return (BigDecimal) view.get(key); }

    private Map<String, Object> wallet(User s) { return wallets.mine(s); }

    private Long withdraw(User s, String amount) {
        Map<String, Object> v = wallets.requestWithdrawal(s, new WalletService.WithdrawalRequest(new BigDecimal(amount), "Leaving the school", "MOBILE_MONEY", "024 000 0000, Ama Test"));
        @SuppressWarnings("unchecked") List<Map<String, Object>> list = (List<Map<String, Object>>) v.get("withdrawals");
        return ((Number) list.get(0).get("id")).longValue();
    }

    @Test
    void depositsStayInTheWalletAndAreNeverSweptOntoFees() {
        term(1, "1000");
        Map<String, Object> p = deposit(ama, "600");
        assertThat(p.get("walletDeposit")).isEqualTo(true);
        assertThat(p.get("receiptNo")).asString().startsWith("RC-");

        ledger.sweep();
        ledger.applyCredit(ama);
        assertThat(dues.account(ama).credit()).isEqualByComparingTo("0");          // not credit …
        assertThat(dues.account(ama).feesBalance()).isEqualByComparingTo("1000");  // … and nothing paid yet
        Map<String, Object> v = wallet(ama);
        assertThat(money(v, "balance")).isEqualByComparingTo("600");
        assertThat(money(v, "available")).isEqualByComparingTo("600");
        assertThat((List<?>) v.get("history")).hasSize(1);
    }

    @Test
    void theWalletIsOffUntilTheSuperAdminSwitchesItOn() {
        settings.updateSetting(Feature.STUDENT_WALLET.settingKey(), "false");
        assertThatThrownBy(() -> deposit(ama, "100")).hasMessageContaining("switched off");
        assertThatThrownBy(() -> wallets.startDeposit(ama, new WalletService.AmountRequest(new BigDecimal("100")))).isInstanceOf(AccessDeniedException.class);
        assertThat(wallet(ama).get("enabled")).isEqualTo(false);
    }

    @Test
    void aPaystackDepositLandsInTheWallet() {
        String ref = (String) wallets.startDeposit(ama, new WalletService.AmountRequest(new BigDecimal("250"))).get("reference");
        assertThat(ref).startsWith("WAL-");
        // A pending deposit is never reopened as a fee checkout for the same amount
        term(1, "250");
        assertThat(payments.startPayment(ama, new FeePaymentService.PayRequest(null, null)).get("reference")).isNotEqualTo(ref);

        payments.handleWebhook(("{\"event\":\"charge.success\",\"data\":{\"id\":7,\"status\":\"success\",\"reference\":\"" + ref
                + "\",\"amount\":25000,\"currency\":\"GHS\",\"channel\":\"mobile_money\"}}").getBytes(StandardCharsets.UTF_8), "sig");
        FeePayment p = paymentRepo.findByReference(ref).orElseThrow();
        assertThat(p.getStatus()).isEqualTo(FeePayment.Status.SUCCESS);
        assertThat(p.getAllocations()).singleElement().satisfies(a -> assertThat(a.isWallet()).isTrue());
        assertThat(money(wallet(ama), "balance")).isEqualByComparingTo("250");
        assertThat(dues.account(ama).feesBalance()).isEqualByComparingTo("250");
    }

    @Test
    void theStudentSpendsOnAChosenFeeOrOnEverythingOldestFirst() {
        Long t1 = term(1, "300");
        deposit(ama, "500");

        assertThatThrownBy(() -> wallets.spend(ama, new WalletService.SpendRequest(new BigDecimal("400"), t1, null)))
                .hasMessageContaining("more than");
        wallets.spend(ama, new WalletService.SpendRequest(new BigDecimal("100"), t1, null));
        assertThat(dues.account(ama).feesBalance()).isEqualByComparingTo("200");

        // Nothing given: as much as the wallet and the debt allow
        Map<String, Object> v = wallets.spend(ama, new WalletService.SpendRequest(null, null, null));
        assertThat(dues.account(ama).feesBalance()).isEqualByComparingTo("0");
        assertThat(money(v, "balance")).isEqualByComparingTo("200");
        assertThatThrownBy(() -> wallets.spend(ama, new WalletService.SpendRequest(null, null, null))).hasMessageContaining("Nothing is owed");

        // A spent deposit can't be cancelled
        Long depositId = paymentRepo.findForStudent(ama.getId()).get(0).getId();
        assertThatThrownBy(() -> payments.voidManual(depositId, "Mistake", bursar)).hasMessageContaining("already used");
    }

    @Test
    void moneyReleasedFromAFeeTheWalletPaidGoesBackToTheWallet() {
        Long t1 = term(1, "300");
        deposit(ama, "300");
        wallets.spend(ama, new WalletService.SpendRequest(null, t1, null));
        assertThat(money(wallet(ama), "balance")).isEqualByComparingTo("0");

        concessions.grant(bursar, new ConcessionService.ConcessionRequest(ama.getId(), null, "WAIVER", "Hardship", "AMOUNT",
                new BigDecimal("100"), null, null, "Approved by the board"));
        ledger.applyCredit(ama);
        assertThat(dues.account(ama).credit()).isEqualByComparingTo("0");
        assertThat(money(wallet(ama), "balance")).isEqualByComparingTo("100");
    }

    @Test
    void aWithdrawalGoesBursarThenSuperAdminThenTheOfficeEachADifferentPerson() {
        deposit(ama, "500");
        Long id = withdraw(ama, "300");
        Map<String, Object> v = wallet(ama);
        assertThat(money(v, "held")).isEqualByComparingTo("300");
        assertThat(money(v, "available")).isEqualByComparingTo("200");
        assertThatThrownBy(() -> withdraw(ama, "50")).hasMessageContaining("already have a withdrawal request");

        assertThatThrownBy(() -> wallets.approve(sa, id, null)).hasMessageContaining("bursar's review");
        assertThatThrownBy(() -> wallets.review(cashier, id, null)).isInstanceOf(AccessDeniedException.class);
        wallets.review(bursar, id, new WalletService.NoteRequest("Balance checked"));
        assertThatThrownBy(() -> wallets.approve(bursar, id, null)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> wallets.payOut(cashier, id, null)).hasMessageContaining("approval");
        wallets.approve(sa, id, null);

        assertThatThrownBy(() -> wallets.payOut(sa, id, null)).hasMessageContaining("approved this request");
        assertThatThrownBy(() -> wallets.payOut(bursar, id, null)).hasMessageContaining("reviewed this request");
        Map<String, Object> paid = wallets.payOut(cashier, id, new WalletService.PayOutRequest("OTHER", null));
        assertThat(paid.get("status")).isEqualTo("PAID");
        assertThat(paid.get("voucher")).asString().startsWith("RF-");
        assertThat(money(wallet(ama), "balance")).isEqualByComparingTo("200");
        assertThat(money(wallet(ama), "held")).isEqualByComparingTo("0");

        // Cancelling the voucher puts the money back in the wallet
        @SuppressWarnings("unchecked") Long refundId = ((Number) ((List<Map<String, Object>>) refunds.forStudent(ama.getId())).get(0).get("id")).longValue();
        refunds.voidRefund(bursar, refundId, "Student did not collect it");
        assertThat(money(wallet(ama), "balance")).isEqualByComparingTo("500");
        @SuppressWarnings("unchecked") List<Map<String, Object>> list = (List<Map<String, Object>>) wallet(ama).get("withdrawals");
        assertThat(list.get(0).get("status")).isEqualTo("REVERSED");
    }

    @Test
    void heldMoneyCantBeSpentAndRejectingOrCancellingReleasesIt() {
        Long t1 = term(1, "1000");
        deposit(ama, "400");
        Long id = withdraw(ama, "300");
        assertThatThrownBy(() -> wallets.spend(ama, new WalletService.SpendRequest(new BigDecimal("200"), t1, null)))
                .hasMessageContaining("100");
        assertThatThrownBy(() -> wallets.reject(bursar, id, new WalletService.NoteRequest(" "))).hasMessageContaining("reason");
        wallets.reject(bursar, id, new WalletService.NoteRequest("Fees are still owed"));
        assertThat(money(wallet(ama), "available")).isEqualByComparingTo("400");

        Long again = withdraw(ama, "100");
        wallets.cancel(ama, again);
        assertThat(money(wallet(ama), "available")).isEqualByComparingTo("400");
        assertThatThrownBy(() -> wallets.cancel(ama, again)).hasMessageContaining("cancelled");
    }

    @Test
    void withNoBursarTheSuperAdminReviewsAndApprovesInOneStep() {
        bursar.setEnabled(false);
        users.save(bursar);
        deposit(ama, "100");
        Long id = withdraw(ama, "100");
        Map<String, Object> w = wallets.approve(sa, id, null);
        assertThat(w.get("status")).isEqualTo("APPROVED");
        assertThat(w.get("reviewedBy")).isEqualTo(w.get("approvedBy"));
        assertThat(wallets.payOut(cashier, id, null).get("status")).isEqualTo("PAID");
    }

    @Test
    void theBursarCanCapWhatAWalletHolds() {
        wallets.saveSettings(bursar, new WalletService.SettingsRequest(new BigDecimal("500")));
        deposit(ama, "400");
        assertThatThrownBy(() -> deposit(ama, "200")).hasMessageContaining("100");
        assertThatThrownBy(() -> wallets.saveSettings(cashier, new WalletService.SettingsRequest(null))).isInstanceOf(AccessDeniedException.class);
    }

    // ── The Super Admin / bursar paying from a student's wallet (developer switches) ──

    private Long bill(User s, String name, String price) {
        Long batchId = ((Number) billing.issue(bursar, new BillingService.BillRequest(null, "STUDENT", null, null, null, List.of(s.getId()),
                List.of(new BillingService.LineRequest(null, name, 1, new BigDecimal(price))), false, null, null, null)).get("batchId")).longValue();
        return dues.bills(s).stream().filter(b -> b.bill().getBatch().getId().equals(batchId)).findFirst().orElseThrow().bill().getId();
    }

    private WalletService.StaffSpendRequest onBill(Long billId, String amount) {
        return new WalletService.StaffSpendRequest(amount == null ? null : new BigDecimal(amount), null, billId, "Asked at the counter");
    }

    @Test
    void staffPayFromAWalletOnlyWhereTheDeveloperAllowsIt() {
        Long uniform = bill(ama, "School uniform", "150");
        deposit(ama, "400");

        // Both switches off (the default): nobody may
        assertThatThrownBy(() -> wallets.staffSpend(sa, ama.getId(), onBill(uniform, null))).isInstanceOf(AccessDeniedException.class)
                .hasMessage(WalletService.NOT_ALLOWED_TO_SPEND);
        assertThatThrownBy(() -> wallets.staffSpend(bursar, ama.getId(), onBill(uniform, null))).isInstanceOf(AccessDeniedException.class)
                .hasMessage(WalletService.NOT_ALLOWED_TO_SPEND);
        assertThat(wallets.canStaffSpend(sa)).isFalse();

        // Only the bursar's switch on
        settings.updateSetting(SystemSettingService.WALLET_STAFF_SPEND_BURSAR, "true");
        assertThatThrownBy(() -> wallets.staffSpend(sa, ama.getId(), onBill(uniform, null))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> wallets.staffSpend(cashier, ama.getId(), onBill(uniform, null))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> wallets.staffSpend(bursar, ama.getId(), new WalletService.StaffSpendRequest(null, null, uniform, " ")))
                .hasMessageContaining("reason");
        Map<String, Object> v = wallets.staffSpend(bursar, ama.getId(), onBill(uniform, "100"));
        assertThat(money(v, "balance")).isEqualByComparingTo("300");
        assertThat(dues.account(ama).billsBalance()).isEqualByComparingTo("50");
        @SuppressWarnings("unchecked") Map<String, Object> spent = ((List<Map<String, Object>>) wallet(ama).get("history")).get(0);
        assertThat(spent.get("type")).isEqualTo("SPEND");
        assertThat(spent.get("by")).isEqualTo(com.exam.service.comms.CurrentUserService.displayName(bursar));
        assertThat(spent.get("note")).isEqualTo("Asked at the counter");

        // Only the Super Admin's switch on
        settings.updateSetting(SystemSettingService.WALLET_STAFF_SPEND_BURSAR, "false");
        settings.updateSetting(SystemSettingService.WALLET_STAFF_SPEND_SUPER_ADMIN, "true");
        assertThatThrownBy(() -> wallets.staffSpend(bursar, ama.getId(), onBill(uniform, null))).isInstanceOf(AccessDeniedException.class);
        wallets.staffSpend(sa, ama.getId(), onBill(uniform, null));          // the rest of the bill
        assertThat(dues.account(ama).billsBalance()).isEqualByComparingTo("0");
        assertThat(money(wallet(ama), "balance")).isEqualByComparingTo("250");
    }

    @Test
    void staffCantSpendHeldMoneyOrWhileTheWalletIsOff() {
        Long t1 = term(1, "1000");
        settings.updateSetting(SystemSettingService.WALLET_STAFF_SPEND_SUPER_ADMIN, "true");
        deposit(ama, "300");
        withdraw(ama, "250");
        assertThatThrownBy(() -> wallets.staffSpend(sa, ama.getId(), new WalletService.StaffSpendRequest(new BigDecimal("100"), t1, null, "Term fees")))
                .hasMessageContaining("50");

        settings.updateSetting(Feature.STUDENT_WALLET.settingKey(), "false");
        assertThatThrownBy(() -> wallets.staffSpend(sa, ama.getId(), new WalletService.StaffSpendRequest(new BigDecimal("50"), t1, null, "Term fees")))
                .hasMessageContaining("switched off");
    }
}
