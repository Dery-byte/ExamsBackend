package com.exam.service.fees;

import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.exam.Department;
import com.exam.model.exam.Program;
import com.exam.repository.DepartmentRepository;
import com.exam.repository.ProgramRepository;
import com.exam.repository.UserRepository;
import com.exam.service.SystemSettingService;
import com.exam.service.academic.AcademicSessionService;
import com.exam.service.fees.ResultsHoldService.Document;
import com.exam.service.fees.ResultsHoldService.HoldRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Holding report cards / transcripts until fees are paid, on in-memory H2: the master switch,
 * full payment, a minimum percentage, chosen items, which documents are held, and the cases
 * that never hold a student (no rule, no fee set for their class).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.datasource.url=jdbc:h2:mem:holds;MODE=MySQL;NON_KEYWORDS=USER,LEVEL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.show-sql=false",
})
@Import({FeeScheduleService.class, FeePaymentService.class, ResultsHoldService.class, AcademicSessionService.class,
        SystemSettingService.class, com.exam.service.comms.CurrentUserService.class})
class ResultsHoldJpaTest {

    @MockBean PaystackClient paystack;

    @Autowired FeeScheduleService schedules;
    @Autowired FeePaymentService payments;
    @Autowired ResultsHoldService holds;
    @Autowired SystemSettingService settings;
    @Autowired UserRepository users;
    @Autowired DepartmentRepository departments;
    @Autowired ProgramRepository programs;

    private Program cs;
    private Program it;
    private User ama;

    @BeforeEach
    void setUp() {
        Department d = new Department();
        d.setName("Computing");
        d.setCode("CMP");
        d = departments.save(d);
        cs = program("Computer Science", "CS", d);
        it = program("Information Technology", "IT", d);
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
        settings.updateSetting(SystemSettingService.FEES_RESULTS_HOLD, "true");
    }

    private Program program(String name, String code, Department d) {
        Program p = new Program();
        p.setName(name);
        p.setCode(code);
        p.setDurationYears(4);
        p.setDepartment(d);
        p.setEnabled(true);
        return programs.save(p);
    }

    private void itemisedFee() {
        schedules.save(new FeeScheduleService.ScheduleRequest(cs.getId(), 200, null, true, null,
                List.of(new FeeScheduleService.ComponentRequest("Tuition", new BigDecimal("2000")),
                        new FeeScheduleService.ComponentRequest("SRC dues", new BigDecimal("100")),
                        new FeeScheduleService.ComponentRequest("Sanitation", new BigDecimal("400"))),
                null, null, null), "SA");
    }

    private void lumpSumFee() {
        schedules.save(new FeeScheduleService.ScheduleRequest(cs.getId(), 200, null, false, new BigDecimal("2500"),
                null, null, null, null), "SA");
    }

    private void pay(String amount, List<String> items) {
        payments.recordManual(new FeePaymentService.ManualPaymentRequest(ama.getId(),
                amount == null ? null : new BigDecimal(amount), "CASH", null, null, items), "Bursar");
    }

    private Map<String, Object> holdFor(Document d) {
        return holds.check(ama, EnumSet.of(d));
    }

    @Test
    void fullPaymentHoldsUntilTheBalanceIsCleared() {
        lumpSumFee();
        holds.save(new HoldRequest(cs.getId(), "FULL", null, null, true, true, null), "SA");

        Map<String, Object> hold = holdFor(Document.REPORT_CARDS);
        assertThat(hold).isNotNull();
        assertThat(hold.get("code")).isEqualTo("FEES_HOLD");
        assertThat((BigDecimal) hold.get("amountToRelease")).isEqualByComparingTo("2500");
        assertThat((String) hold.get("message")).contains("report cards are on hold").contains("GHS 2,500.00");
        assertThatThrownBy(() -> holds.requireReleased(ama, Document.TRANSCRIPT))
                .isInstanceOf(ResultsHoldService.ResultsHeldException.class);

        pay("2000", null);
        assertThat((BigDecimal) holdFor(Document.TRANSCRIPT).get("amountToRelease")).isEqualByComparingTo("500");
        pay("500", null);
        assertThat(holdFor(Document.REPORT_CARDS)).isNull();
        holds.requireReleased(ama, Document.TRANSCRIPT);
    }

    @Test
    void masterSwitchOffHoldsNobody() {
        lumpSumFee();
        holds.save(new HoldRequest(cs.getId(), "FULL", null, null, true, true, null), "SA");
        settings.updateSetting(SystemSettingService.FEES_RESULTS_HOLD, "false");
        assertThat(holdFor(Document.REPORT_CARDS)).isNull();
    }

    @Test
    void minimumPercentageReleasesOncePaidEnough() {
        lumpSumFee();
        holds.save(new HoldRequest(cs.getId(), "PERCENT", 60, null, true, true, null), "SA");

        pay("1000", null);   // 40%
        Map<String, Object> hold = holdFor(Document.REPORT_CARDS);
        assertThat((BigDecimal) hold.get("amountToRelease")).isEqualByComparingTo("500");   // 60% of 2,500 = 1,500
        assertThat((String) hold.get("message")).contains("at least 60%");

        pay("500", null);
        assertThat(holdFor(Document.REPORT_CARDS)).isNull();
    }

    @Test
    void chosenItemsReleaseEvenWithABalanceOnOtherItems() {
        itemisedFee();
        holds.save(new HoldRequest(cs.getId(), "ITEMS", null, List.of("tuition", " SRC  dues "), true, true, null), "SA");

        Map<String, Object> hold = holdFor(Document.REPORT_CARDS);
        assertThat((BigDecimal) hold.get("amountToRelease")).isEqualByComparingTo("2100");
        assertThat((List<?>) hold.get("unpaidItems")).hasSize(2);

        pay(null, List.of("Tuition"));
        hold = holdFor(Document.REPORT_CARDS);
        assertThat((BigDecimal) hold.get("amountToRelease")).isEqualByComparingTo("100");
        assertThat((String) hold.get("message")).contains("SRC dues").doesNotContain("Tuition");

        pay(null, List.of("SRC dues"));
        assertThat(holdFor(Document.REPORT_CARDS)).isNull();   // Sanitation still owed, but not required
    }

    @Test
    void chosenItemsOnAFeeWithoutBreakdownNeedTheFullFee() {
        lumpSumFee();
        holds.save(new HoldRequest(cs.getId(), "ITEMS", null, List.of("Tuition"), true, true, null), "SA");
        assertThat((BigDecimal) holdFor(Document.REPORT_CARDS).get("amountToRelease")).isEqualByComparingTo("2500");
    }

    @Test
    void onlyTheChosenDocumentsAreHeld() {
        lumpSumFee();
        holds.save(new HoldRequest(cs.getId(), "FULL", null, null, false, true, null), "SA");
        assertThat(holdFor(Document.REPORT_CARDS)).isNull();
        assertThat(holdFor(Document.TRANSCRIPT)).isNotNull();
        assertThat(holds.check(ama, EnumSet.allOf(Document.class)).get("documents")).isEqualTo(List.of("TRANSCRIPT"));
    }

    @Test
    void noRuleOrNoFeeNeverHolds() {
        holds.save(new HoldRequest(cs.getId(), "FULL", null, null, true, true, null), "SA");
        assertThat(holdFor(Document.REPORT_CARDS)).isNull();        // no fee set for Level 200 yet

        lumpSumFee();
        holds.remove(cs.getId());
        assertThat(holdFor(Document.REPORT_CARDS)).isNull();        // hold lifted
    }

    @Test
    void ruleCanBeAppliedToSeveralProgrammesAndIsValidated() {
        holds.save(new HoldRequest(cs.getId(), "PERCENT", 50, null, true, false, List.of(it.getId())), "SA");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) holds.overview().get("programs");
        assertThat(rows).allSatisfy(r -> assertThat(r.get("rule")).isNotNull());

        assertThatThrownBy(() -> holds.save(new HoldRequest(cs.getId(), "PERCENT", 0, null, true, true, null), "SA"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> holds.save(new HoldRequest(cs.getId(), "ITEMS", null, List.of(" "), true, true, null), "SA"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> holds.save(new HoldRequest(cs.getId(), "FULL", null, null, false, false, null), "SA"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
