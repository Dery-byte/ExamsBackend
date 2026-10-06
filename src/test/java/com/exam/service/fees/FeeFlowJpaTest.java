package com.exam.service.fees;

import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.exam.Department;
import com.exam.model.exam.Program;
import com.exam.model.fees.FeePayment;
import com.exam.repository.*;
import com.exam.service.SystemSettingService;
import com.exam.service.academic.AcademicSessionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * Fees end to end on in-memory H2 with Paystack mocked: setting a fee (lump sum and itemised),
 * a student's statement, starting a checkout, settling it from the webhook (once, at the right
 * amount only), part-payment rules, cash entries and the payments register queries.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.datasource.url=jdbc:h2:mem:fees;MODE=MySQL;NON_KEYWORDS=USER,LEVEL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.show-sql=false",
})
@Import({FeeScheduleService.class, FeePaymentService.class, ResultsHoldService.class, AcademicSessionService.class, SystemSettingService.class,
        com.exam.service.comms.CurrentUserService.class})
class FeeFlowJpaTest {

    @MockBean PaystackClient paystack;

    @Autowired FeeScheduleService schedules;
    @Autowired FeePaymentService payments;
    @Autowired SystemSettingService settings;
    @Autowired UserRepository users;
    @Autowired DepartmentRepository departments;
    @Autowired ProgramRepository programs;
    @Autowired FeePaymentRepository paymentRepo;

    private final ObjectMapper mapper = new ObjectMapper();
    private Program cs;
    private User ama;

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

        ama = new User();
        ama.setUsername("10001");
        ama.setEmail("ama@example.com");
        ama.setFirstname("Ama");
        ama.setLastname("Mensah");
        ama.setPassword("x");
        ama.setRole(Role.NORMAL);
        ama.setEnabled(true);
        ama.setProgram(cs);
        ama.setCurrentLevel(200);
        ama = users.save(ama);

        settings.updateSetting(SystemSettingService.FEES_VISIBLE_STUDENT, "true");
        when(paystack.isConfigured()).thenReturn(true);
        when(paystack.initialize(anyString(), anyLong(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(mapper.readTree("{\"authorization_url\":\"https://checkout.paystack.com/abc\"}"));
        when(paystack.validSignature(any(), any())).thenReturn(true);
    }

    private void setItemisedFee() {
        schedules.save(new FeeScheduleService.ScheduleRequest(cs.getId(), 200, null, true, null,
                List.of(new FeeScheduleService.ComponentRequest("School fees", new BigDecimal("2500")),
                        new FeeScheduleService.ComponentRequest("Sanitation", new BigDecimal("300.50")),
                        new FeeScheduleService.ComponentRequest("Maintenance", new BigDecimal("199.50"))),
                null, null, List.of(300)), "SA");
    }

    private byte[] webhook(String reference, long amountMinor, String currency) {
        return ("{\"event\":\"charge.success\",\"data\":{\"id\":99,\"status\":\"success\",\"reference\":\"" + reference
                + "\",\"amount\":" + amountMinor + ",\"currency\":\"" + currency
                + "\",\"channel\":\"mobile_money\",\"paid_at\":\"2026-10-06T09:15:02.000Z\",\"gateway_response\":\"Approved\"}}")
                .getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void itemisedFeeTotalsItsComponentsAndCanBeAppliedToOtherLevels() {
        setItemisedFee();
        Map<String, Object> overview = schedules.overview(null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> levels = (List<Map<String, Object>>) ((List<Map<String, Object>>) overview.get("programs")).get(0).get("levels");
        assertThat(levels).extracting(l -> l.get("level")).containsExactly(100, 200, 300, 400);
        @SuppressWarnings("unchecked")
        Map<String, Object> l200 = (Map<String, Object>) levels.get(1).get("schedule");
        assertThat((BigDecimal) l200.get("amount")).isEqualByComparingTo("3000.00");
        assertThat((List<?>) l200.get("components")).hasSize(3);
        assertThat(levels.get(2).get("schedule")).as("applied to level 300 too").isNotNull();
        assertThat(levels.get(0).get("schedule")).isNull();
        assertThat(levels.get(1).get("students")).isEqualTo(1L);
        assertThat((BigDecimal) levels.get(1).get("billed")).isEqualByComparingTo("3000");
    }

    @Test
    void breakdownRejectsDuplicatesAndLevelsOutsideTheProgramme() {
        assertThatThrownBy(() -> schedules.save(new FeeScheduleService.ScheduleRequest(cs.getId(), 200, null, true, null,
                List.of(new FeeScheduleService.ComponentRequest("Sanitation", BigDecimal.TEN),
                        new FeeScheduleService.ComponentRequest(" sanitation ", BigDecimal.ONE)), null, null, null), "SA"))
                .hasMessageContaining("appears twice");
        assertThatThrownBy(() -> schedules.save(new FeeScheduleService.ScheduleRequest(cs.getId(), 700, null, false,
                new BigDecimal("100"), null, null, null, null), "SA"))
                .hasMessageContaining("not part of");
    }

    @Test
    void paystackPaymentSettlesOnceAtTheRightAmount() {
        setItemisedFee();
        Map<String, Object> start = payments.startPayment(ama, new FeePaymentService.PayRequest(new BigDecimal("1000"), null));
        String ref = (String) start.get("reference");
        assertThat(start.get("authorizationUrl")).isEqualTo("https://checkout.paystack.com/abc");
        assertThat(paymentRepo.findByReference(ref).orElseThrow().getStatus()).isEqualTo(FeePayment.Status.PENDING);

        // Clicking Pay again for the same amount reopens the same checkout
        assertThat(payments.startPayment(ama, new FeePaymentService.PayRequest(new BigDecimal("1000"), null)).get("reference")).isEqualTo(ref);

        assertThat(payments.handleWebhook(webhook(ref, 100000, "GHS"), "sig")).isTrue();
        assertThat(payments.handleWebhook(webhook(ref, 100000, "GHS"), "sig")).isTrue();   // Paystack retries are harmless
        FeePayment p = paymentRepo.findByReference(ref).orElseThrow();
        assertThat(p.getStatus()).isEqualTo(FeePayment.Status.SUCCESS);
        assertThat(p.getChannel()).isEqualTo("mobile_money");

        Map<String, Object> st = payments.statement(ama);
        assertThat((BigDecimal) st.get("paid")).isEqualByComparingTo("1000");
        assertThat((BigDecimal) st.get("balance")).isEqualByComparingTo("2000");
        assertThat(st.get("status")).isEqualTo("PART_PAID");

        // A charge for a different amount is never counted
        String ref2 = (String) payments.startPayment(ama, new FeePaymentService.PayRequest(new BigDecimal("2000"), null)).get("reference");
        payments.handleWebhook(webhook(ref2, 100, "GHS"), "sig");
        assertThat(paymentRepo.findByReference(ref2).orElseThrow().getStatus()).isEqualTo(FeePayment.Status.FAILED);
        assertThat((BigDecimal) payments.statement(ama).get("balance")).isEqualByComparingTo("2000");

        // Fees with payments can't be deleted
        Long scheduleId = p.getSchedule().getId();
        assertThatThrownBy(() -> schedules.delete(scheduleId)).hasMessageContaining("already paid");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> items() {
        Map<String, Map<String, Object>> out = new java.util.LinkedHashMap<>();
        for (Map<String, Object> m : (List<Map<String, Object>>) payments.statement(ama).get("items")) out.put((String) m.get("name"), m);
        return out;
    }

    @Test
    void studentsCanPayForChosenItemsOfTheBreakdown() {
        setItemisedFee();   // School fees 2500, Sanitation 300.50, Maintenance 199.50
        settings.updateSetting(SystemSettingService.FEES_PART_PAYMENT, "false");   // paying by item still works

        String ref = (String) payments.startPayment(ama, new FeePaymentService.PayRequest(null, List.of("sanitation", "Maintenance"))).get("reference");
        FeePayment p = paymentRepo.findByReference(ref).orElseThrow();
        assertThat(p.getAmount()).isEqualByComparingTo("500.00");
        assertThat(p.getItems()).extracting(i -> i.getName()).containsExactly("Sanitation", "Maintenance");
        // The same items again (any order) reopen the same checkout
        assertThat(payments.startPayment(ama, new FeePaymentService.PayRequest(null, List.of("Maintenance", "Sanitation"))).get("reference")).isEqualTo(ref);

        payments.handleWebhook(webhook(ref, 50000, "GHS"), "sig");
        assertThat((BigDecimal) items().get("Sanitation").get("balance")).isEqualByComparingTo("0");
        assertThat((BigDecimal) items().get("Maintenance").get("balance")).isEqualByComparingTo("0");
        assertThat((BigDecimal) items().get("School fees").get("balance")).isEqualByComparingTo("2500");

        assertThatThrownBy(() -> payments.startPayment(ama, new FeePaymentService.PayRequest(null, List.of("Sanitation"))))
                .hasMessageContaining("already paid");
        assertThatThrownBy(() -> payments.startPayment(ama, new FeePaymentService.PayRequest(null, List.of("Library"))))
                .hasMessageContaining("not part of");

        // Money not paid for an item goes to the items in order; the accounts office can also take payment for an item
        payments.recordManual(new FeePaymentService.ManualPaymentRequest(ama.getId(), new BigDecimal("1000"), "CASH", null, null, null), "Bursar");
        assertThat((BigDecimal) items().get("School fees").get("paid")).isEqualByComparingTo("1000");
        Map<String, Object> cash = payments.recordManual(
                new FeePaymentService.ManualPaymentRequest(ama.getId(), null, "CASH", null, null, List.of("School fees")), "Bursar");
        assertThat((BigDecimal) cash.get("amount")).isEqualByComparingTo("1500");
        assertThat(payments.statement(ama).get("status")).isEqualTo("PAID");

        settings.updateSetting(SystemSettingService.FEES_ITEM_PAYMENT, "false");
        schedules.save(new FeeScheduleService.ScheduleRequest(cs.getId(), 300, null, true, null,
                List.of(new FeeScheduleService.ComponentRequest("Tuition", new BigDecimal("100"))), null, null, null), "SA");
        ama.setCurrentLevel(300);
        users.save(ama);
        assertThatThrownBy(() -> payments.startPayment(ama, new FeePaymentService.PayRequest(null, List.of("Tuition"))))
                .hasMessageContaining("switched off");
    }

    @Test
    void amountRulesFollowTheSettings() {
        schedules.save(new FeeScheduleService.ScheduleRequest(cs.getId(), 200, null, false, new BigDecimal("1500"),
                null, null, null, null), "SA");
        assertThatThrownBy(() -> payments.startPayment(ama, new FeePaymentService.PayRequest(new BigDecimal("1500.01"), null)))
                .hasMessageContaining("more than your balance");

        settings.updateSetting(SystemSettingService.FEES_PART_PAYMENT, "false");
        assertThatThrownBy(() -> payments.startPayment(ama, new FeePaymentService.PayRequest(new BigDecimal("500"), null)))
                .hasMessageContaining("full balance");
        assertThat(payments.startPayment(ama, null).get("reference")).asString().startsWith("FEE-");

        settings.updateSetting(SystemSettingService.FEES_ONLINE_PAYMENT, "false");
        assertThatThrownBy(() -> payments.startPayment(ama, null)).isInstanceOf(ResponseStatusException.class);

        settings.updateSetting(SystemSettingService.FEES_VISIBLE_STUDENT, "false");
        assertThatThrownBy(() -> payments.statement(ama)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void cashPaymentsCountAndCanBeCancelled() {
        schedules.save(new FeeScheduleService.ScheduleRequest(cs.getId(), 200, null, false, new BigDecimal("1500"),
                null, null, null, null), "SA");
        Map<String, Object> cash = payments.recordManual(
                new FeePaymentService.ManualPaymentRequest(ama.getId(), new BigDecimal("1500"), "CASH", null, "Receipt 42", null), "Bursar");
        assertThat(payments.statement(ama).get("status")).isEqualTo("PAID");
        assertThatThrownBy(() -> payments.startPayment(ama, null)).hasMessageContaining("fully paid");

        // Payments register and student search queries
        Map<String, Object> page = payments.search(null, "SUCCESS", cs.getId(), 200, "mensah", 0, 25);
        assertThat(page.get("total")).isEqualTo(1L);
        assertThat(payments.searchStudents("ama")).singleElement()
                .satisfies(s -> assertThat((BigDecimal) s.get("balance")).isEqualByComparingTo("0"));

        payments.voidManual((Long) cash.get("id"), "Typed the wrong student", "Bursar");
        assertThat(payments.statement(ama).get("status")).isEqualTo("UNPAID");
        assertThat((List<?>) payments.statement(ama).get("payments")).as("cancelled entries are hidden from the student").isEmpty();
    }

    @Test
    void feesCanBeCopiedIntoANewSession(@Autowired AcademicSessionService sessions) {
        setItemisedFee();
        Long from = sessions.current().getId();
        Long to = (Long) sessions.create("2099/2100", null, null, false).get("id");
        assertThat(schedules.copy(from, to, false, "SA")).containsEntry("copied", 2).containsEntry("skipped", 0);
        assertThat(schedules.copy(from, to, false, "SA")).containsEntry("copied", 0).containsEntry("skipped", 2);
    }
}
