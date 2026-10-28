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
import com.exam.service.boarding.BoardingService;
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

import static com.exam.service.fees.FinanceJpaTest.spread;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * Bills (Phase B) on H2: payments split oldest first over fees and bills with the rest as credit,
 * credit used on the next bill, a chosen bill paid first, cancelling and declining, who may bill whom,
 * and an online payment for a bill.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.datasource.url=jdbc:h2:mem:billing;MODE=MySQL;NON_KEYWORDS=USER,LEVEL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.show-sql=false",
})
@Import({FeeDues.class, FeeLedger.class, BillingService.class, ReceiptService.class, BoardingSettings.class,
        com.exam.service.academic.DocumentVerificationService.class, com.exam.service.features.FeatureService.class,
        com.exam.service.academic.InstitutionService.class, com.exam.service.academic.ThemeService.class,
        com.exam.service.admin.MaintenanceService.class, FeeScheduleService.class, FeePaymentService.class, ResultsHoldService.class,
        AcademicSessionService.class, SystemSettingService.class, com.exam.service.comms.CurrentUserService.class})
class BillingJpaTest {

    @MockBean PaystackClient paystack;
    @MockBean NotificationService notifications;
    @MockBean BoardingService boardingService;

    @Autowired BillingService billing;
    @Autowired FeeScheduleService schedules;
    @Autowired FeePaymentService payments;
    @Autowired FeeDues dues;
    @Autowired BoardingSettings boarding;
    @Autowired SystemSettingService settings;
    @Autowired UserRepository users;
    @Autowired DepartmentRepository departments;
    @Autowired ProgramRepository programs;
    @Autowired FeePaymentRepository paymentRepo;

    private final ObjectMapper mapper = new ObjectMapper();
    private Program cs;
    private User ama, kofi, esi, bursar, cashier;
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
        ama = student(Residency.BOARDING);
        kofi = student(Residency.DAY);
        esi = student(null);
        bursar = user("bur", Role.ACCOUNTANT, FinanceDuty.BURSAR);
        cashier = user("cash", Role.ACCOUNTANT, FinanceDuty.CASHIER);
        settings.updateSetting(SystemSettingService.FEES_VISIBLE_STUDENT, "true");
        when(paystack.isConfigured()).thenReturn(true);
        when(paystack.initialize(anyString(), anyLong(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(mapper.readTree("{\"authorization_url\":\"https://checkout.paystack.com/abc\"}"));
        when(paystack.validSignature(any(), any())).thenReturn(true);
    }

    private User student(Residency r) {
        User u = user("2000" + next++, Role.NORMAL, null);
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

    private static BillingService.LineRequest line(String name, String price) {
        return new BillingService.LineRequest(null, name, 1, new BigDecimal(price));
    }

    private static BillingService.BillRequest toStudent(User s, boolean optional, BillingService.LineRequest... lines) {
        return new BillingService.BillRequest(null, "STUDENT", null, null, null, List.of(s.getId()), List.of(lines), optional, null, null, null);
    }

    /** Issues to one student and returns that student's bill id. */
    private Long bill(User s, String name, String price, boolean optional) {
        Long batchId = ((Number) billing.issue(bursar, toStudent(s, optional, line(name, price))).get("batchId")).longValue();
        return dues.bills(s).stream().filter(b -> b.bill().getBatch().getId().equals(batchId)).findFirst().orElseThrow().bill().getId();
    }

    private Map<String, Object> cash(User s, String amount, Long scheduleId, Long billId) {
        return payments.recordManual(new FeePaymentService.ManualPaymentRequest(s.getId(), new BigDecimal(amount), "CASH", null, null,
                null, scheduleId, billId), "Cashier");
    }

    @Test
    void paymentsGoToFeesThenBillsOldestFirstAndTheRestIsCreditUsedOnTheNextBill() {
        Long t1 = term(1, "1000");
        Long coat = bill(ama, "Lab coat", "150", false);
        assertThat(spread(cash(ama, "1300", null, null))).containsExactly("FEE " + t1 + " 1000.00", "BILL " + coat + " 150.00", "CREDIT 150.00");

        Map<String, Object> st = payments.staffStatement(ama.getId());
        assertThat((BigDecimal) st.get("accountCredit")).isEqualByComparingTo("150");
        assertThat((BigDecimal) st.get("toPay")).isEqualByComparingTo("0");

        // A class bill: Ama's credit pays most of hers straight away
        Map<String, Object> made = billing.issue(bursar, new BillingService.BillRequest("Sweaters", "CLASS", cs.getId(), 200, null, null,
                List.of(new BillingService.LineRequest(null, "Sweater", 2, new BigDecimal("85"))), false, null, null, null));
        assertThat(made.get("students")).isEqualTo(3);
        assertThat((BigDecimal) made.get("amountEach")).isEqualByComparingTo("170");
        FeeDues.BillDue sweaters = dues.bills(ama).stream().filter(b -> b.bill().getTitle().equals("Sweaters")).findFirst().orElseThrow();
        assertThat(sweaters.paid()).isEqualByComparingTo("150");
        assertThat(sweaters.balance()).isEqualByComparingTo("20");
        assertThat(dues.account(ama).credit()).isEqualByComparingTo("0");

        // The same bill again leaves out those who have it
        assertThatThrownBy(() -> billing.issue(bursar, new BillingService.BillRequest("Sweaters", "CLASS", cs.getId(), 200, null, null,
                List.of(line("Sweater", "85")), false, null, null, null))).hasMessageContaining("already has this bill");
    }

    @Test
    void aChosenBillIsPaidFirstAndCancellingItMovesTheMoneyToTheFees() {
        Long t1 = term(1, "1000");
        Long trip = bill(ama, "Excursion to Cape Coast", "200", false);
        assertThat(spread(cash(ama, "300", null, trip))).containsExactly("BILL " + trip + " 200.00", "FEE " + t1 + " 100.00");

        Map<String, Object> out = billing.cancelBill(bursar, trip, new BillingService.CancelRequest("Trip called off"));
        assertThat((BigDecimal) out.get("creditReleased")).isEqualByComparingTo("200");
        assertThat(dues.dues(ama).get(0).paid()).isEqualByComparingTo("300");     // the 200 went to the fee
        assertThat(dues.account(ama).credit()).isEqualByComparingTo("0");
        assertThatThrownBy(() -> cash(ama, "10", null, trip)).hasMessageContaining("isn't open");
    }

    @Test
    void studentsMayDeclineOnlyOptionalUnpaidBills() {
        Long book = bill(ama, "Extra workbook", "60", true);
        Long levy = bill(ama, "Sports levy", "40", false);
        assertThatThrownBy(() -> billing.decline(ama, levy)).hasMessageContaining("isn't optional");
        assertThatThrownBy(() -> billing.decline(kofi, book)).hasMessageContaining("not found");
        assertThat(billing.decline(ama, book).get("status")).isEqualTo("DECLINED");
        assertThat(dues.account(ama).billsBalance()).isEqualByComparingTo("40");

        Long tour = bill(kofi, "Museum tour", "50", true);
        cash(kofi, "10", null, tour);
        assertThatThrownBy(() -> billing.decline(kofi, tour)).hasMessageContaining("already paid");
    }

    @Test
    void cashiersBillOneStudentFromThePriceList() {
        assertThatThrownBy(() -> billing.savePriceItem(cashier, null, new BillingService.PriceItemRequest("Tie", "UNIFORM", new BigDecimal("25"), null)))
                .isInstanceOf(AccessDeniedException.class);
        Long tie = ((Number) billing.savePriceItem(bursar, null, new BillingService.PriceItemRequest("Tie", "UNIFORM", new BigDecimal("25"), null)).get("id")).longValue();
        assertThatThrownBy(() -> billing.savePriceItem(bursar, null, new BillingService.PriceItemRequest("tie", "UNIFORM", new BigDecimal("30"), null)))
                .hasMessageContaining("already on the price list");

        Map<String, Object> sold = billing.issue(cashier, toStudent(kofi, false, new BillingService.LineRequest(tie, null, 2, null)));
        assertThat((BigDecimal) sold.get("amountEach")).isEqualByComparingTo("50");
        assertThatThrownBy(() -> billing.issue(cashier, toStudent(kofi, false, line("Discount pen", "3"))))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> billing.issue(cashier, new BillingService.BillRequest(null, "EVERYONE", null, null, null, null,
                List.of(new BillingService.LineRequest(tie, null, 1, null)), false, null, null, null))).isInstanceOf(AccessDeniedException.class);

        billing.setPriceItemActive(bursar, tie, false);                    // retired: not offered any more
        assertThatThrownBy(() -> billing.issue(cashier, toStudent(kofi, false, new BillingService.LineRequest(tie, null, 1, null))))
                .hasMessageContaining("no longer on the price list");
    }

    @Test
    void boardersAndDayStudentsCanBeBilledAsGroups() {
        boarding.setMode(ResidencyMode.BOTH);
        esi.setResidency(null);
        users.save(esi);
        Map<String, Object> p = billing.preview(bursar, new BillingService.BillRequest("Hall dues", "BOARDERS", null, null, null, null,
                List.of(line("Hall dues", "30")), false, null, null, null));
        assertThat(p.get("students")).isEqualTo(1);
        assertThat(p.get("residencyUnknown")).isEqualTo(1);
        assertThat(billing.preview(bursar, new BillingService.BillRequest("Bus", "DAY", null, null, null, null,
                List.of(line("Bus", "30")), false, null, null, null)).get("sample")).isEqualTo(List.of("20002 Test"));
    }

    @Test
    void anOnlinePaymentForABillGoesToThatBill() {
        term(1, "1000");
        Long coat = bill(ama, "Lab coat", "150", false);
        assertThatThrownBy(() -> payments.startPayment(ama, new FeePaymentService.PayRequest(new BigDecimal("151"), null, null, coat)))
                .hasMessageContaining("more than your balance");
        String ref = (String) payments.startPayment(ama, new FeePaymentService.PayRequest(null, null, null, coat)).get("reference");
        payments.handleWebhook(("{\"event\":\"charge.success\",\"data\":{\"id\":9,\"status\":\"success\",\"reference\":\"" + ref
                + "\",\"amount\":15000,\"currency\":\"GHS\",\"channel\":\"card\"}}").getBytes(StandardCharsets.UTF_8), "sig");
        FeePayment paid = paymentRepo.findByReference(ref).orElseThrow();
        assertThat(paid.getStatus()).isEqualTo(FeePayment.Status.SUCCESS);
        assertThat(paid.getAllocations()).singleElement().satisfies(a -> assertThat(a.getBill().getId()).isEqualTo(coat));
        assertThat(dues.account(ama).billsBalance()).isEqualByComparingTo("0");
        assertThat(dues.account(ama).feesBalance()).isEqualByComparingTo("1000");

        // Voiding a recorded payment takes its allocations off the account
        Map<String, Object> p = cash(ama, "400", null, null);
        payments.voidManual(((Number) p.get("id")).longValue(), "Wrong student", "Bursar");
        assertThat(dues.account(ama).feesBalance()).isEqualByComparingTo("1000");
    }
}
