package com.exam;

import com.exam.model.Residency;
import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.exam.Department;
import com.exam.model.exam.Program;
import com.exam.model.fees.FinanceDuty;
import com.exam.repository.DepartmentRepository;
import com.exam.repository.ProgramRepository;
import com.exam.repository.UserRepository;
import com.exam.service.fees.FeeScheduleService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * The whole application over HTTP, as the screens call it: the developer lets the Super Admin and the
 * bursar pay from student wallets, the Super Admin switches the wallet on, money goes in at the counter,
 * then each of them pays the student's fee from the wallet; switching one off stops only that one.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.datasource.url=jdbc:h2:mem:walletspend;MODE=MySQL;NON_KEYWORDS=USER,LEVEL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "DEEPSEEK_API_KEY=test", "DEEPSEEK_API_URLL=http://localhost", "GOOGLE.GEMINI.API.KEY=test",
        "GOOGLE.GEMINI.API.URL=http://localhost", "GOOGLE_CLIENT_ID=test", "GOOGLE_CLIENT_SECRET=test",
        "MAILJET_SMTP_PASSWORD=test", "MAILJET_SMTP_PORT=587", "MAILJET_SMTP_USERNAME=test",
        "MNOTIFY_KEY=test", "MNOTIFY_SENDER_ID=test", "OPENAI_API_KEY=test", "OPENAI_API_URL=http://localhost",
})
class WalletStaffSpendIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired DepartmentRepository departments;
    @Autowired ProgramRepository programs;
    @Autowired FeeScheduleService schedules;
    @Autowired com.exam.repository.DeveloperEmailRepository developerEmails;

    private final ObjectMapper mapper = new ObjectMapper();

    private User save(String name, Role role, FinanceDuty duty) {
        User u = new User();
        u.setUsername(name);
        u.setEmail(name + "@example.com");
        u.setFirstname(name);
        u.setLastname("Test");
        u.setPassword("x");
        u.setRole(role);
        u.setEnabled(true);
        u.setFinanceDuty(duty);
        return users.save(u);
    }

    private static RequestPostProcessor as(User u) {
        return user(u.getUsername()).authorities(new SimpleGrantedAuthority(u.getRole().name()));
    }

    private MockHttpServletResponse call(MockHttpServletRequestBuilder req, String body) throws Exception {
        return mvc.perform(req.contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
    }

    private JsonNode ok(MockHttpServletRequestBuilder req, String body) throws Exception {
        MockHttpServletResponse r = call(req, body);
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
        return mapper.readTree(r.getContentAsString());
    }

    @Test
    void theSuperAdminAndTheBursarPayFromAWalletOnceTheDeveloperAllowsIt() throws Exception {
        Department d = new Department();
        d.setName("Computing");
        d.setCode("CMP");
        d = departments.save(d);
        Program cs = new Program();
        cs.setName("Computer Science");
        cs.setCode("CS");
        cs.setDurationYears(4);
        cs.setDepartment(d);
        cs.setEnabled(true);
        cs = programs.save(cs);
        User ama = save("ama", Role.NORMAL, null);
        ama.setProgram(cs);
        ama.setCurrentLevel(200);
        ama.setCurrentSemester(1);
        ama.setResidency(Residency.DAY);
        users.save(ama);
        User sa = save("sa", Role.SUPER_ADMIN, null);
        User bursar = save("bur", Role.ACCOUNTANT, FinanceDuty.BURSAR);
        User cashier = save("cash", Role.ACCOUNTANT, FinanceDuty.CASHIER);
        User dev = save("dev", Role.DEVELOPER, null);
        var row = new com.exam.model.monitoring.DeveloperEmail();
        row.setEmail("dev@example.com");
        developerEmails.save(row);
        Long fee = ((Number) schedules.save(new FeeScheduleService.ScheduleRequest(cs.getId(), 200, null, false, new BigDecimal("1000"),
                null, null, null, null, 1), "Bursar").get(0).get("id")).longValue();

        String spendUrl = "/api/v1/super-admin/fees/wallets/students/" + ama.getId() + "/spend";
        String bursarSpendUrl = "/api/finance/fees/wallets/students/" + ama.getId() + "/spend";
        String spend = "{\"scheduleId\":" + fee + ",\"amount\":100,\"note\":\"Asked at the counter\"}";

        // Off at first: the developer's switches and the wallet itself
        JsonNode modules = ok(get("/api/v1/developer/modules").with(as(dev)), "");
        assertThat(modules.path("walletSpendSuperAdmin").asBoolean(true)).isFalse();
        assertThat(modules.path("walletSpendBursar").asBoolean(true)).isFalse();
        assertThat(call(put("/api/v1/developer/modules").with(as(sa)), "{\"walletSpendSuperAdmin\":true}").getStatus()).isEqualTo(403);

        // The developer switches both on; the Super Admin switches the wallet on (it is listed under Feature Controls)
        modules = ok(put("/api/v1/developer/modules").with(as(dev)), "{\"walletSpendSuperAdmin\":true,\"walletSpendBursar\":true}");
        assertThat(modules.path("walletSpendSuperAdmin").asBoolean()).isTrue();
        assertThat(modules.path("walletSpendBursar").asBoolean()).isTrue();
        JsonNode features = ok(get("/api/features").with(as(sa)), "");
        assertThat(features.findValuesAsText("key")).contains("STUDENT_WALLET");
        ok(put("/api/features/STUDENT_WALLET").with(as(sa)), "{\"enabled\":true}");

        // Money in at the counter, then both may pay from it
        ok(post("/api/finance/fees/wallets/students/" + ama.getId() + "/deposits").with(as(cashier)), "{\"amount\":500,\"method\":\"CASH\"}");
        JsonNode wallet = ok(get("/api/v1/super-admin/fees/wallets/students/" + ama.getId()).with(as(sa)), "");
        assertThat(wallet.path("canSpend").asBoolean()).isTrue();
        assertThat(wallet.path("targets").size()).isGreaterThan(0);
        assertThat(ok(get("/api/finance/fees/wallets/students/" + ama.getId()).with(as(cashier)), "").path("canSpend").asBoolean()).isFalse();

        assertThat(ok(post(spendUrl).with(as(sa)), spend).path("balance").decimalValue()).isEqualByComparingTo("400");
        assertThat(ok(post(bursarSpendUrl).with(as(bursar)), spend).path("balance").decimalValue()).isEqualByComparingTo("300");
        assertThat(call(post(bursarSpendUrl).with(as(cashier)), spend).getStatus()).isEqualTo(403);

        // Only the Super Admin's switch on now: the bursar is refused, the Super Admin isn't
        ok(put("/api/v1/developer/modules").with(as(dev)), "{\"walletSpendBursar\":false}");
        MockHttpServletResponse refused = call(post(bursarSpendUrl).with(as(bursar)), spend);
        assertThat(refused.getStatus()).isEqualTo(403);
        assertThat(ok(post(spendUrl).with(as(sa)), spend).path("balance").decimalValue()).isEqualByComparingTo("200");
    }
}
