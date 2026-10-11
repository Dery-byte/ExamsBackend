package com.exam;

import com.exam.model.Role;
import com.exam.model.User;
import com.exam.repository.DeveloperEmailRepository;
import com.exam.repository.UserRepository;
import com.exam.service.SystemSettingService;
import com.exam.service.subscription.SubscriptionService;
import com.exam.service.subscription.SubscriptionState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Boots the whole application and checks the subscription is enforced on real requests:
 * read-only refuses changes, suspended keeps everyone but the Super Admin out, developers are never stopped.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.datasource.url=jdbc:h2:mem:subscription;MODE=MySQL;NON_KEYWORDS=USER;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "DEEPSEEK_API_KEY=test", "DEEPSEEK_API_URLL=http://localhost", "GOOGLE.GEMINI.API.KEY=test",
        "GOOGLE.GEMINI.API.URL=http://localhost", "GOOGLE_CLIENT_ID=test", "GOOGLE_CLIENT_SECRET=test",
        "MAILJET_SMTP_PASSWORD=test", "MAILJET_SMTP_PORT=587", "MAILJET_SMTP_USERNAME=test",
        "MNOTIFY_KEY=test", "MNOTIFY_SENDER_ID=test", "OPENAI_API_KEY=test", "OPENAI_API_URL=http://localhost",
})
class SubscriptionEnforcementIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired DeveloperEmailRepository developerEmails;
    @Autowired SystemSettingService settings;
    @Autowired SubscriptionService subscription;

    private User student, lecturer, hod, superAdmin, developer;

    @BeforeEach
    void setUp() {
        users.deleteAll();
        student = save("stu", Role.NORMAL);
        lecturer = save("lec", Role.LECTURER);
        hod = save("hod", Role.ADMIN);
        superAdmin = save("sa", Role.SUPER_ADMIN);
        developer = save("dev", Role.DEVELOPER);
        developerEmails.deleteAll();
        var row = new com.exam.model.monitoring.DeveloperEmail();
        row.setEmail("dev@example.com");
        developerEmails.save(row);
        reset();
    }

    @AfterEach
    void reset() {
        for (String k : List.of(SubscriptionService.KEY, SubscriptionService.FIRST_SEEN, SubscriptionService.SUSPENDED_REASON,
                SubscriptionService.SUSPENDED_AT, SubscriptionService.EXTENDED_UNTIL, SubscriptionService.NOTICE_AUDIENCE))
            settings.deleteSetting(k);
        subscription.refresh();
    }

    private User save(String name, Role role) {
        User u = new User();
        u.setUsername(name);
        u.setEmail(name + "@example.com");
        u.setFirstname(name);
        u.setLastname("Test");
        u.setPassword("x");
        u.setRole(role);
        u.setEnabled(true);
        return users.save(u);
    }

    private MockHttpServletResponse call(MockHttpServletRequestBuilder req, User as) throws Exception {
        if (as != null) req = req.with(user(as));
        return mvc.perform(req.contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().getResponse();
    }

    private int send(MockHttpServletRequestBuilder req, User as, String body) throws Exception {
        return mvc.perform(req.with(user(as)).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getStatus();
    }

    private int status(MockHttpServletRequestBuilder req, User as) throws Exception { return call(req, as).getStatus(); }

    private void readOnly() {
        // No key, first started 35 days ago: 30 days' grace are over, read-only for a few more days
        settings.updateSetting(SubscriptionService.FIRST_SEEN, LocalDate.now().minusDays(35).toString());
        assertThat(subscription.refresh().state()).isEqualTo(SubscriptionState.RESTRICTED);
    }

    private void suspended() {
        settings.updateSetting(SubscriptionService.SUSPENDED_REASON, "Unpaid invoice");
        assertThat(subscription.refresh().state()).isEqualTo(SubscriptionState.SUSPENDED);
    }

    @Test
    void inGoodStandingNothingIsStopped() throws Exception {
        assertThat(subscription.status().state()).isEqualTo(SubscriptionState.GRACE);   // fresh deployment, no key yet
        assertThat(status(post("/api/v1/auth/question/add"), lecturer)).isNotEqualTo(402);
        assertThat(status(get("/api/notifications"), hod)).isEqualTo(200);
    }

    @Test
    void readOnlyLetsEveryoneLookButNotChange() throws Exception {
        readOnly();
        assertThat(status(get("/api/notifications"), lecturer)).isEqualTo(200);
        assertThat(status(get("/api/notifications"), student)).isEqualTo(200);
        MockHttpServletResponse refused = call(post("/api/v1/auth/question/add"), lecturer);
        assertThat(refused.getStatus()).isEqualTo(402);
        assertThat(refused.getContentAsString()).contains("SUBSCRIPTION_READ_ONLY");
        // Signing out and the notice always work
        assertThat(status(post("/api/v1/auth/logout"), lecturer)).isNotEqualTo(402);
    }

    @Test
    void suspendedKeepsEveryoneButTheSuperAdminOut() throws Exception {
        suspended();
        MockHttpServletResponse blocked = call(get("/api/notifications"), hod);
        assertThat(blocked.getStatus()).isEqualTo(402);
        assertThat(blocked.getContentAsString()).contains("SUBSCRIPTION_SUSPENDED");
        assertThat(status(get("/api/notifications"), lecturer)).isEqualTo(402);
        assertThat(status(get("/api/notifications"), student)).isEqualTo(402);

        // The Super Admin can still look and export, but not change anything
        assertThat(status(get("/api/notifications"), superAdmin)).isEqualTo(200);
        assertThat(call(post("/api/v1/auth/question/add"), superAdmin).getContentAsString()).contains("SUBSCRIPTION_READ_ONLY");

        // What the app shell needs keeps working, so the blocked page can show
        assertThat(status(get("/api/v1/auth/current-user"), hod)).isEqualTo(200);
        assertThat(call(get("/api/v1/auth/subscription/notice"), hod).getContentAsString()).contains("\"access\":\"BLOCKED\"");
    }

    @Test
    void theDeveloperIsNeverStopped() throws Exception {
        suspended();
        assertThat(status(get("/api/v1/developer/subscription"), developer)).isEqualTo(200);
        assertThat(status(post("/api/v1/developer/subscription/resume"), developer)).isEqualTo(200);
        assertThat(subscription.status().state()).isNotEqualTo(SubscriptionState.SUSPENDED);
        assertThat(status(get("/api/v1/developer/subscription"), superAdmin)).isEqualTo(403);
    }

    @Test
    void suspendedSignInIsForTheSuperAdminOnly() throws Exception {
        suspended();
        // Blocked before the password is even checked; unknown usernames get the same answer
        assertThat(mvc.perform(post("/api/v1/auth/authenticate").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"hod\",\"password\":\"x\"}")).andReturn().getResponse().getStatus()).isEqualTo(402);
        assertThat(mvc.perform(post("/api/v1/auth/authenticate").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"nobody\",\"password\":\"x\"}")).andReturn().getResponse().getStatus()).isEqualTo(402);
        // The Super Admin gets through to the password check (wrong password here)
        assertThat(mvc.perform(post("/api/v1/auth/authenticate").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"sa\",\"password\":\"wrong\"}")).andReturn().getResponse().getStatus()).isNotEqualTo(402);
    }

    @Test
    void theNoticeGoesToTheAudienceTheDeveloperChose() throws Exception {
        readOnly();
        assertThat(call(get("/api/v1/auth/subscription/notice"), superAdmin).getContentAsString()).contains("\"state\":\"RESTRICTED\"");
        assertThat(call(get("/api/v1/auth/subscription/notice"), hod).getContentAsString()).doesNotContain("\"state\"");

        assertThat(send(put("/api/v1/developer/subscription/settings"), developer, "{\"audience\":\"ADMINS\"}")).isEqualTo(200);
        assertThat(call(get("/api/v1/auth/subscription/notice"), hod).getContentAsString()).contains("\"state\":\"RESTRICTED\"");
        assertThat(call(get("/api/v1/auth/subscription/notice"), lecturer).getContentAsString()).doesNotContain("\"state\"");
    }

    @Test
    void theAudienceSeesTheInvoiceAndCanCheckForPaymentEvenWhenSuspended() throws Exception {
        settings.updateSetting("SUBSCRIPTION_INVOICE", "{\"number\":\"INV-2026-0007\",\"amount\":\"18500.00\",\"currency\":\"GHS\","
                + "\"dueDate\":\"2026-10-20\",\"coversUntil\":\"2027-10-20\",\"payUrl\":\"https://vendor.example.com/pay/abc\"}");
        try {
            suspended();
            assertThat(call(get("/api/v1/auth/subscription/notice"), superAdmin).getContentAsString()).contains("INV-2026-0007");
            assertThat(call(get("/api/v1/auth/subscription/notice"), hod).getContentAsString()).doesNotContain("INV-2026-0007");

            // Not linked to a vendor portal here, so nothing is checked, but the call itself is allowed
            MockHttpServletResponse r = call(post("/api/v1/auth/subscription/check-payment"), superAdmin);
            assertThat(r.getStatus()).isEqualTo(200);
            assertThat(r.getContentAsString()).contains("\"checked\":false");
            assertThat(status(post("/api/v1/auth/subscription/check-payment"), lecturer)).isEqualTo(403);
        } finally {
            settings.deleteSetting("SUBSCRIPTION_INVOICE");
        }
    }

    /** Through the real settings table: a key (~450 characters) is longer than a VARCHAR(255). */
    @Test
    void aRealKeyFitsInTheSettingsTable() {
        String key = "EPSUB1.eyJ2IjoxLCJpZCI6Ijg1MzNmOTNhLTY2MDgtNDNmMS05OWY0LTg3MGUxNmIwMGVlOSIsImluc3QiOiJURVNUIiwibmFtZSI6IkZvcm1hdCBjaGVjayIsInBsYW4iOiJzdGFuZGFyZCIsImlzc3VlZCI6IjIwMjYtMTAtMTEiLCJwYWlkVW50aWwiOiIyMDI3LTAxLTMxIiwiZ3JhY2VEYXlzIjoxNCwicmVzdHJpY3RlZERheXMiOjd9.C1_uGWP6w7sk9iV4rNVc68VTnLXF7563KUarwJqSS5yNIteKJcsYCSD9hsmU0sdFjNP02vb8nWbwn6JPErtIDg";
        assertThat(key.length()).isGreaterThan(255);
        subscription.installKey(key, "test");
        assertThat(settings.getSetting(SubscriptionService.KEY)).isEqualTo(key);
        assertThat(subscription.refresh().key().institutionId()).isEqualTo("TEST");
    }

    @Test
    void theSuperAdminCannotSeeOrChangeSubscriptionSettings() throws Exception {
        assertThat(call(get("/api/v1/super-admin/settings"), superAdmin).getContentAsString()).doesNotContain("SUBSCRIPTION_");
        assertThat(send(put("/api/v1/super-admin/settings"), superAdmin, "{\"SUBSCRIPTION_SUSPENDED_REASON\":\"\"}")).isEqualTo(403);
    }
}
