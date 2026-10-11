package com.exam.service.subscription;

import com.exam.repository.QuizAttemptRepository;
import com.exam.repository.SubscriptionEventRepository;
import com.exam.repository.UserRepository;
import com.exam.service.FakeSettings;
import com.exam.service.monitoring.ErrorMonitorService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** The portal checking in with a fake vendor portal over real HTTP. */
class SubscriptionSyncTest {

    private final TestKeys vendor = new TestKeys();
    private final Map<String, String> store = new HashMap<>();
    private SubscriptionService service;
    private SubscriptionSync sync;
    private HttpServer server;

    // What the fake vendor portal answers, and what it was sent
    private int status = 200;
    private String answer = "{}";
    private String gotAuth, gotBody;

    @BeforeEach
    void setUp() throws IOException {
        var settings = FakeSettings.create(store);
        service = new SubscriptionService();
        ReflectionTestUtils.setField(service, "settings", settings);
        ReflectionTestUtils.setField(service, "keys", vendor.verifier());
        ReflectionTestUtils.setField(service, "events", mock(SubscriptionEventRepository.class));
        ReflectionTestUtils.setField(service, "attempts", mock(QuizAttemptRepository.class));

        sync = new SubscriptionSync();
        ReflectionTestUtils.setField(sync, "subscription", service);
        ReflectionTestUtils.setField(sync, "keys", vendor.verifier());
        ReflectionTestUtils.setField(sync, "monitor", mock(SubscriptionMonitor.class));
        ReflectionTestUtils.setField(sync, "settings", settings);
        ReflectionTestUtils.setField(sync, "users", mock(UserRepository.class));
        ReflectionTestUtils.setField(sync, "errorMonitor", mock(ErrorMonitorService.class));

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/check-in", ex -> {
            gotAuth = ex.getRequestHeaders().getFirst("Authorization");
            gotBody = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] out = answer.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, out.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(out); }
        });
        server.start();
        store.put(SubscriptionSync.VENDOR_URL, "http://localhost:" + server.getAddress().getPort() + "/");
        store.put(SubscriptionSync.VENDOR_TOKEN, "vpt_test");
    }

    @AfterEach
    void stop() { server.stop(0); }

    private void offer(String key) { answer = "{\"key\":\"" + key + "\",\"checkInHours\":6}"; }

    @Test
    void installsTheVendorsNewerKeyAndReportsItsState() {
        offer(vendor.issue("UCC", LocalDate.now().plusYears(1), 2000, null));
        String result = sync.sync();

        assertThat(result).contains("installed a new key");
        assertThat(service.status().state()).isEqualTo(SubscriptionState.ACTIVE);
        assertThat(service.status().key().seq()).isEqualTo(2000);
        assertThat(gotAuth).isEqualTo("Bearer vpt_test");
        assertThat(gotBody).contains("\"state\":\"GRACE\"");   // what it was before the new key
        assertThat(store.get("SUBSCRIPTION_SYNC_OK")).isEqualTo("true");

        assertThat(sync.sync()).contains("up to date");
    }

    @Test
    void keepsTheOpenInvoiceSoThePortalCanOfferPayOnline() {
        String key = vendor.issue("UCC", LocalDate.now().plusDays(5), 2000, null);
        answer = "{\"key\":\"" + key + "\",\"invoice\":{\"number\":\"INV-2026-0007\",\"amount\":\"18500.00\",\"currency\":\"GHS\","
                + "\"dueDate\":\"2026-10-20\",\"coversUntil\":\"2027-10-20\",\"payUrl\":\"https://vendor.example.com/pay/abc\"}}";
        sync.sync();
        assertThat(service.invoice()).containsEntry("number", "INV-2026-0007").containsEntry("payUrl", "https://vendor.example.com/pay/abc");

        // Paid: the vendor portal stops sending it, and it disappears
        offer(key);
        sync.sync();
        assertThat(service.invoice()).isNull();
    }

    @Test
    void ignoresAnInvoiceWithAnUnsafePayLink() {
        String key = vendor.issue("UCC", LocalDate.now().plusDays(5), 2000, null);
        for (String url : new String[]{"javascript:alert(1)", "http://evil.example.com/pay", "https://x.example.com/\\\"><script>"}) {
            answer = "{\"key\":\"" + key + "\",\"invoice\":{\"number\":\"INV-1\",\"amount\":\"10.00\",\"currency\":\"GHS\","
                    + "\"dueDate\":\"2026-10-20\",\"coversUntil\":\"2027-10-20\",\"payUrl\":\"" + url + "\"}}";
            sync.sync();
            assertThat(service.invoice()).as(url).isNull();
        }
    }

    @Test
    void neverGoesBackToAnOlderKey() {
        service.installKey(vendor.issue("UCC", LocalDate.now().plusYears(2), 3000, null), "dev");
        offer(vendor.issue("UCC", LocalDate.now().plusYears(1), 2000, null));
        assertThat(sync.sync()).contains("kept the installed key");
        assertThat(service.status().key().seq()).isEqualTo(3000);
    }

    @Test
    void theVendorCanSuspendAndThenLiftIt() {
        offer(vendor.issue("UCC", LocalDate.now().plusYears(1), 4000, "Contract ended"));
        assertThat(sync.sync()).contains("suspended");
        assertThat(service.status().state()).isEqualTo(SubscriptionState.SUSPENDED);
        assertThat(service.status().suspendedByProvider()).isTrue();

        offer(vendor.issue("UCC", LocalDate.now().plusYears(1), 5000, null));
        sync.sync();
        assertThat(service.status().state()).isEqualTo(SubscriptionState.ACTIVE);
    }

    @Test
    void aFakeVendorPortalCantGrantAnything() {
        offer(new TestKeys().issue("UCC", LocalDate.now().plusYears(50), 9000, null));
        assertThat(sync.sync()).contains("not accepted");
        assertThat(service.status().key()).isNull();
        assertThat(store.get("SUBSCRIPTION_SYNC_OK")).isEqualTo("false");
    }

    @Test
    void explainsARefusedTokenOrAnUnreachableServer() {
        status = 401;
        assertThat(sync.sync()).contains("refused this portal's token");

        store.put(SubscriptionSync.VENDOR_URL, "http://localhost:1");
        assertThat(sync.sync()).contains("Could not reach");
        assertThat(store.get("SUBSCRIPTION_SYNC_OK")).isEqualTo("false");
    }

    @Test
    void theTokenOnlyTravelsOverHttps() {
        assertThat(SubscriptionSync.secure("https://vendor.example.com")).isTrue();
        assertThat(SubscriptionSync.secure("http://localhost:8090")).isTrue();
        assertThat(SubscriptionSync.secure("http://vendor.example.com")).isFalse();
        store.put(SubscriptionSync.VENDOR_URL, "http://vendor.example.com");
        assertThat(sync.sync()).contains("https://");
        assertThatThrownBy(() -> sync.link("http://vendor.example.com", "vpt_x", "dev")).hasMessageContaining("https://");
    }

    @Test
    void linkingSavesAndChecksInAtOnceAndUnlinkingKeepsTheKey() {
        store.remove(SubscriptionSync.VENDOR_URL);
        store.remove(SubscriptionSync.VENDOR_TOKEN);
        assertThat(sync.sync()).contains("Not linked");

        offer(vendor.issue("UCC", LocalDate.now().plusYears(1), 2000, null));
        Map<String, Object> d = sync.link("http://localhost:" + server.getAddress().getPort(), "vpt_new", "dev");
        assertThat(d).containsEntry("tokenSet", true).containsEntry("tokenHint", "…_new").containsEntry("lastOk", true);
        assertThat(service.status().key()).isNotNull();

        sync.unlink("dev");
        assertThat(sync.configured()).isFalse();
        assertThat(service.status().key()).isNotNull();
    }
}
