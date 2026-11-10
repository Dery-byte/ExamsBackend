package com.exam;

import com.exam.model.PasswordResetLink;
import com.exam.model.Role;
import com.exam.model.User;
import com.exam.repository.PasswordResetLinkRepository;
import com.exam.repository.UserRepository;
import com.exam.service.Impl.EmailService;
import com.exam.service.MNotifyV2SmsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "Forgot password" end to end: the link is stored in the database (hashed), opens the site the
 * request came from, works once, and shared contact details get a clear answer instead of a 500.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.datasource.url=jdbc:h2:mem:pwreset;MODE=MySQL;NON_KEYWORDS=USER;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "DEEPSEEK_API_KEY=test", "DEEPSEEK_API_URLL=http://localhost", "GOOGLE.GEMINI.API.KEY=test",
        "GOOGLE.GEMINI.API.URL=http://localhost", "GOOGLE_CLIENT_ID=test", "GOOGLE_CLIENT_SECRET=test",
        "MAILJET_SMTP_PASSWORD=test", "MAILJET_SMTP_PORT=587", "MAILJET_SMTP_USERNAME=test",
        "MNOTIFY_KEY=test", "MNOTIFY_SENDER_ID=test", "OPENAI_API_KEY=test", "OPENAI_API_URL=http://localhost",
})
class PasswordResetFlowTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordResetLinkRepository links;
    @Autowired PasswordEncoder encoder;

    @MockBean EmailService emailService;
    @MockBean MNotifyV2SmsService smsService;

    @BeforeEach
    void clean() {
        links.deleteAll();
        users.deleteAll();
    }

    private User save(String name, String email, String phone) {
        User u = new User();
        u.setUsername(name);
        u.setEmail(email);
        u.setPhone(phone);
        u.setFirstname(name);
        u.setLastname("Test");
        u.setPassword(encoder.encode("OldPass123!"));
        u.setRole(Role.NORMAL);
        u.setEnabled(true);
        return users.save(u);
    }

    @SuppressWarnings("unchecked")
    private String sentEmailLink() throws Exception {
        ArgumentCaptor<Map<String, Object>> vars = ArgumentCaptor.forClass(Map.class);
        verify(emailService).sendEmail(anyString(), any(), vars.capture(), anyString());
        return (String) vars.getValue().get("resetUrl");
    }

    @Test
    void emailLinkIsStoredHashedOpensTheRequestingSiteAndWorksOnce() throws Exception {
        User u = save("reset1", "Reset1@Example.com", "0240000001");

        // Typed in a different letter case from the stored address
        mvc.perform(post("/api/v1/auth/forgotten-password").header("Origin", "http://localhost:4201")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"reset1@example.com\"}"))
                .andExpect(status().isOk());

        String url = sentEmailLink();
        assertThat(url).startsWith("http://localhost:4201/reset-password?token=");
        String token = url.substring(url.indexOf("token=") + 6);

        // Stored in the database, and only as a hash
        List<PasswordResetLink> stored = links.findAll();
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).getTokenHash()).hasSize(64).isNotEqualTo(token);
        assertThat(stored.get(0).getUserId()).isEqualTo(u.getId());

        mvc.perform(get("/api/v1/auth/validate-reset-token").param("token", token))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/auth/reset-password-with-token").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"newPassword\":\"NewPass123!\"}"))
                .andExpect(status().isOk());
        assertThat(encoder.matches("NewPass123!", users.findById(u.getId()).orElseThrow().getPassword())).isTrue();

        // One use only
        mvc.perform(post("/api/v1/auth/reset-password-with-token").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"newPassword\":\"Another123!\"}"))
                .andExpect(status().isNotFound());
        assertThat(links.count()).isZero();
    }

    @Test
    void unknownOrMadeUpTokenIsRejected() throws Exception {
        mvc.perform(get("/api/v1/auth/validate-reset-token").param("token", "not-a-real-token"))
                .andExpect(status().isNotFound());
    }

    @Test
    void sharedPhoneGetsAClearAnswerNotAServerError() throws Exception {
        save("twinA", "twina@example.com", "0249999999");
        save("twinB", "twinb@example.com", "0249999999");

        mvc.perform(post("/api/v1/auth/forgotten-password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"0249999999\"}"))
                .andExpect(status().isConflict());
        verifyNoInteractions(smsService);
        assertThat(links.count()).isZero();
    }

    @Test
    void failedSendLeavesNoLinkAndAllowsAnImmediateRetry() throws Exception {
        save("reset2", "reset2@example.com", "0240000002");
        doThrow(new RuntimeException("SMTP down")).when(emailService).sendEmail(anyString(), any(), anyMap(), anyString());

        mvc.perform(post("/api/v1/auth/forgotten-password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"reset2@example.com\"}"))
                .andExpect(status().isInternalServerError());
        assertThat(links.count()).isZero();

        // Mail works again: the student can retry straight away (no 2-minute cooldown)
        doNothing().when(emailService).sendEmail(anyString(), any(), anyMap(), anyString());
        mvc.perform(post("/api/v1/auth/forgotten-password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"reset2@example.com\"}"))
                .andExpect(status().isOk());
        assertThat(links.count()).isEqualTo(1);
    }
}
