package com.exam.service.fees;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Thin client for the Paystack Transactions API (https://paystack.com/docs/api/transaction).
 * The secret key comes from the PAYSTACK_SECRET_KEY environment variable and never leaves the server.
 */
@Component
public class PaystackClient {

    private static final Logger log = LoggerFactory.getLogger(PaystackClient.class);

    /** Card and Mobile Money (MTN MoMo, Telecel Cash, AirtelTigo Money) only. */
    private static final List<String> CHANNELS = List.of("card", "mobile_money");

    private final String secretKey;
    private final String baseUrl;
    private final RestTemplate http;
    private final ObjectMapper mapper = new ObjectMapper();

    public PaystackClient(@Value("${paystack.secret-key:}") String secretKey,
                          @Value("${paystack.base-url:https://api.paystack.co}") String baseUrl) {
        this.secretKey = secretKey == null ? "" : secretKey.trim();
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(20_000);
        this.http = new RestTemplate(factory);
    }

    public boolean isConfigured() {
        return secretKey.startsWith("sk_");
    }

    /** "live", "test" or null when no key is set. */
    public String mode() {
        if (!isConfigured()) return null;
        return secretKey.startsWith("sk_live_") ? "live" : "test";
    }

    /** Starts a checkout. Returns Paystack's {@code data}: authorization_url, access_code, reference. */
    public JsonNode initialize(String email, long amountMinor, String currency, String reference,
                               String callbackUrl, Map<String, Object> metadata) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("email", email);
        body.put("amount", amountMinor);
        body.put("currency", currency);
        body.put("reference", reference);
        body.put("callback_url", callbackUrl);
        body.put("channels", CHANNELS);
        body.put("metadata", metadata);
        return call(HttpMethod.POST, "/transaction/initialize", body);
    }

    /** Paystack's {@code data} for a transaction: status, amount (minor units), currency, channel, paid_at … */
    public JsonNode verify(String reference) {
        return call(HttpMethod.GET, "/transaction/verify/" + URLEncoder.encode(reference, StandardCharsets.UTF_8), null);
    }

    /** True when the x-paystack-signature header is the HMAC-SHA512 of the raw body with our secret key. */
    public boolean validSignature(byte[] rawBody, String signature) {
        if (!isConfigured() || signature == null || signature.isBlank() || rawBody == null) return false;
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            byte[] expected = HexFormat.of().formatHex(mac.doFinal(rawBody)).getBytes(StandardCharsets.UTF_8);
            return MessageDigest.isEqual(expected, signature.trim().toLowerCase().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("[Paystack] Could not check webhook signature", e);
            return false;
        }
    }

    private JsonNode call(HttpMethod method, String path, Object body) {
        if (!isConfigured()) throw new PaystackException("Online payment is not set up on the server yet.");
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(secretKey);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        try {
            ResponseEntity<String> res = http.exchange(baseUrl + path, method, new HttpEntity<>(body, headers), String.class);
            JsonNode root = mapper.readTree(res.getBody());
            if (!root.path("status").asBoolean(false)) {
                throw new PaystackException(root.path("message").asText("Paystack refused the request."));
            }
            return root.path("data");
        } catch (RestClientResponseException e) {
            String message = "Paystack refused the request.";
            try { message = mapper.readTree(e.getResponseBodyAsString()).path("message").asText(message); } catch (Exception ignored) { }
            log.warn("[Paystack] {} {} -> {} {}", method, path, e.getStatusCode().value(), message);
            throw new PaystackException(message);
        } catch (RestClientException e) {
            log.warn("[Paystack] {} {} failed: {}", method, path, e.getMessage());
            throw new PaystackException("Could not reach Paystack. Please try again in a moment.");
        } catch (PaystackException e) {
            throw e;
        } catch (Exception e) {
            log.error("[Paystack] {} {} returned an unreadable response", method, path, e);
            throw new PaystackException("Paystack returned an unexpected response.");
        }
    }

    public static class PaystackException extends RuntimeException {
        public PaystackException(String message) { super(message); }
    }
}
