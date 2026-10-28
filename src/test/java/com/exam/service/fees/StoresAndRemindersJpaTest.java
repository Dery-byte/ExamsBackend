package com.exam.service.fees;

import com.exam.config.MNotifyV2Config;
import com.exam.model.Residency;
import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.exam.Department;
import com.exam.model.exam.Program;
import com.exam.model.fees.FinanceDuty;
import com.exam.repository.*;
import com.exam.service.MNotifyV2SmsService;
import com.exam.service.SystemSettingService;
import com.exam.service.academic.AcademicSessionService;
import com.exam.service.boarding.BoardingService;
import com.exam.service.boarding.BoardingSettings;
import com.exam.service.comms.NotificationService;
import com.exam.service.Impl.EmailService;
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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Phase D on H2: the stores (stock only leaves when handed over, after payment unless allowed, never
 * below zero; a bill with items out can't be cancelled), fee reminders (who owes, not twice within the
 * gap, logged), and the one-person accounts office's cash-ups.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.datasource.url=jdbc:h2:mem:stores;MODE=MySQL;NON_KEYWORDS=USER,LEVEL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.show-sql=false",
})
@Import({FeeDues.class, FeeLedger.class, BillingService.class, StockService.class, ReminderService.class, CashBookService.class,
        ReceiptService.class, BoardingSettings.class, com.exam.service.academic.DocumentVerificationService.class,
        com.exam.service.features.FeatureService.class, com.exam.service.academic.InstitutionService.class,
        com.exam.service.academic.ThemeService.class, com.exam.service.admin.MaintenanceService.class, FeeScheduleService.class,
        FeePaymentService.class, ResultsHoldService.class, AcademicSessionService.class, SystemSettingService.class,
        com.exam.service.comms.CurrentUserService.class})
class StoresAndRemindersJpaTest {

    @MockBean PaystackClient paystack;
    @MockBean NotificationService notifications;
    @MockBean BoardingService boardingService;
    @MockBean EmailService emailService;
    @MockBean MNotifyV2SmsService sms;
    @MockBean MNotifyV2Config smsConfig;

    @Autowired StockService stock;
    @Autowired BillingService billing;
    @Autowired ReminderService reminders;
    @Autowired CashBookService cashBook;
    @Autowired FeeScheduleService schedules;
    @Autowired FeePaymentService payments;
    @Autowired SystemSettingService settings;
    @Autowired UserRepository users;
    @Autowired DepartmentRepository departments;
    @Autowired ProgramRepository programs;
    @Autowired FeeReminderRepository reminderRepo;

    private Program cs;
    private User ama, kofi, esi, bursar, cashier;
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
        settings.updateSetting(SystemSettingService.FEES_VISIBLE_STUDENT, "true");
    }

    private User student(Residency r) {
        User u = user("4000" + next++, Role.NORMAL, null);
        u.setProgram(cs);
        u.setCurrentLevel(200);
        u.setCurrentSemester(1);
        u.setResidency(r);
        u.setPhone("0244000000");
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

    private Long sweater() {
        Long id = ((Number) billing.savePriceItem(bursar, null, new BillingService.PriceItemRequest("Sweater", "UNIFORM", new BigDecimal("85"), null)).get("id")).longValue();
        stock.setStocked(bursar, id, new StockService.StockedRequest(true, 2));
        return id;
    }

    /** Bills ama 2 sweaters; returns the bill line id. */
    @SuppressWarnings("unchecked")
    private Long billTwo(Long item) {
        billing.issue(cashier, new BillingService.BillRequest(null, "STUDENT", null, null, null, List.of(ama.getId()),
                List.of(new BillingService.LineRequest(item, null, 2, null)), false, null, null, null));
        return ((Number) ((List<Map<String, Object>>) stock.awaiting(ama.getId()).get("lines")).get(0).get("lineId")).longValue();
    }

    @Test
    void itemsLeaveTheStoreOnlyWhenHandedOverAfterPaymentAndNeverBelowZero() {
        Long item = sweater();
        assertThatThrownBy(() -> stock.receive(cashier, item, new StockService.ReceiveRequest(5, null, null, null))).isInstanceOf(AccessDeniedException.class);
        stock.receive(bursar, item, new StockService.ReceiveRequest(3, new BigDecimal("60"), "Kente Uniforms, DN 0042", null));
        Long line = billTwo(item);

        assertThatThrownBy(() -> stock.handOver(cashier, line, new StockService.HandOverRequest(1, null))).hasMessageContaining("isn't paid yet");
        Long bill = ((Number) ((List<Map<String, Object>>) stock.awaiting(ama.getId()).get("lines")).get(0).get("billId")).longValue();
        payments.recordManual(new FeePaymentService.ManualPaymentRequest(ama.getId(), new BigDecimal("170"), "CASH", null, null, null, null, bill), cashier);

        stock.handOver(cashier, line, new StockService.HandOverRequest(1, null));
        assertThat(stock.stores().get("items")).asList().singleElement().satisfies(m -> assertThat(((Map<?, ?>) m).get("onHand")).isEqualTo(2));
        assertThatThrownBy(() -> stock.handOver(cashier, line, new StockService.HandOverRequest(2, null))).hasMessageContaining("Only 1 left");

        // A bill with items out can't be cancelled until they come back
        assertThatThrownBy(() -> billing.cancelBill(bursar, bill, new BillingService.CancelRequest("Wrong size"))).hasMessageContaining("Take them back first");
        assertThatThrownBy(() -> stock.takeBack(cashier, line, new StockService.HandOverRequest(1, null))).hasMessageContaining("Say why");
        stock.takeBack(cashier, line, new StockService.HandOverRequest(1, "Wrong size"));
        billing.cancelBill(bursar, bill, new BillingService.CancelRequest("Wrong size"));

        // A stock-take: the count after, with the reason
        assertThatThrownBy(() -> stock.adjust(bursar, item, new StockService.AdjustRequest(1, null))).hasMessageContaining("reason");
        Map<String, Object> after = stock.adjust(bursar, item, new StockService.AdjustRequest(1, "Two damaged by rain"));
        assertThat(after.get("onHand")).isEqualTo(1);
        assertThat(after.get("low")).isEqualTo(true);
        assertThat(stock.history(item)).extracting(m -> m.get("kind")).containsExactly("ADJUSTED", "RETURNED", "ISSUED", "RECEIVED");
        assertThatThrownBy(() -> stock.setStocked(bursar, item, new StockService.StockedRequest(false, null))).hasMessageContaining("Correct the count to 0");
    }

    @Test
    void handingOverBeforePaymentNeedsTheBursarsSettingAndStock() {
        Long item = sweater();
        stock.receive(bursar, item, new StockService.ReceiveRequest(1, null, null, null));
        Long line = billTwo(item);
        settings.updateSetting(SystemSettingService.FEES_STOCK_ISSUE_UNPAID, "true");
        assertThatThrownBy(() -> stock.handOver(cashier, line, new StockService.HandOverRequest(2, null))).hasMessageContaining("holds only 1");
        assertThat(stock.handOver(cashier, line, new StockService.HandOverRequest(1, null)).get("issued")).isEqualTo(1);
    }

    @Test
    void remindersGoToWhoOwesAndNotTwiceWithinTheGap() {
        schedules.save(new FeeScheduleService.ScheduleRequest(cs.getId(), 200, null, false, new BigDecimal("1000"), null, null, null, null, 1), "Bursar");
        payments.recordManual(new FeePaymentService.ManualPaymentRequest(ama.getId(), new BigDecimal("1000"), "CASH", null, null, null), cashier);
        ReminderService.ReminderRequest req = new ReminderService.ReminderRequest(null, null, null, 7, true, false, "Dear {name}, you owe {amount}.");

        assertThatThrownBy(() -> reminders.preview(cashier, req)).isInstanceOf(AccessDeniedException.class);
        Map<String, Object> p = reminders.preview(bursar, req);
        assertThat(p.get("students")).isEqualTo(2);                                  // kofi and esi; ama has paid
        assertThat((String) p.get("example")).matches("Dear 4000[23], you owe GHS 1,000\\.00\\.");
        assertThatThrownBy(() -> reminders.send(bursar, new ReminderService.ReminderRequest(null, null, null, 7, false, true, null)))
                .hasMessageContaining("SMS is not set up");

        Map<String, Object> sent = reminders.send(bursar, req);
        assertThat(sent.get("students")).isEqualTo(2);
        assertThat(sent.get("emails")).isEqualTo(2);
        verify(notifications, times(2)).notify(any(User.class), eq(ReminderService.NOTIFY_TYPE), anyString(), anyString(), anyString());
        assertThat(reminderRepo.count()).isEqualTo(2);
        assertThat(reminders.preview(bursar, req).get("skipped")).isEqualTo(2);
        assertThatThrownBy(() -> reminders.send(bursar, req)).hasMessageContaining("reminded recently");
        assertThat((List<?>) reminders.overview(bursar).get("history")).hasSize(1);
    }

    @Test
    void aOnePersonOfficesBursarHandsInWithoutASecondCheck() {
        schedules.save(new FeeScheduleService.ScheduleRequest(cs.getId(), 200, null, false, new BigDecimal("1000"), null, null, null, null, 1), "Bursar");
        payments.recordManual(new FeePaymentService.ManualPaymentRequest(kofi.getId(), new BigDecimal("100"), "CASH", null, null, null), bursar);
        assertThat(cashBook.cashUp(bursar, new CashBookService.CashUpRequest(new BigDecimal("100"), null)).get("status")).isEqualTo("SUBMITTED");
        settings.updateSetting(SystemSettingService.FEES_SELF_CASHUP, "true");
        payments.recordManual(new FeePaymentService.ManualPaymentRequest(esi.getId(), new BigDecimal("50"), "CASH", null, null, null), bursar);
        assertThat(cashBook.cashUp(bursar, new CashBookService.CashUpRequest(new BigDecimal("50"), null)).get("status")).isEqualTo("CONFIRMED");
        // A cashier's still needs the bursar
        payments.recordManual(new FeePaymentService.ManualPaymentRequest(esi.getId(), new BigDecimal("20"), "CASH", null, null, null), cashier);
        assertThat(cashBook.cashUp(cashier, new CashBookService.CashUpRequest(new BigDecimal("20"), null)).get("status")).isEqualTo("SUBMITTED");
    }
}
