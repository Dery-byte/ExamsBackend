package com.exam.service.fees;

import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.fees.FeePayment;
import com.exam.model.fees.FeePaymentItem;
import com.exam.model.fees.FeeSchedule;
import com.exam.repository.FeePaymentRepository;
import com.exam.repository.UserRepository;
import com.exam.service.SystemSettingService;
import com.exam.service.academic.AcademicSessionService;
import com.exam.service.comms.CurrentUserService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Students' fee statement and payments: online through Paystack (card / Mobile Money) and cash or
 * bank payments the Super Admin records.
 * <p>
 * A Paystack payment is created PENDING before the student is sent to checkout, then settled from
 * whichever arrives first: the student returning to the Fees page, Paystack's signed webhook, or
 * the background check of payments left pending. Each path re-reads the row under a lock and
 * compares the amount and currency Paystack charged with what we asked for.
 */
@Service
public class FeePaymentService {

    private static final Logger log = LoggerFactory.getLogger(FeePaymentService.class);

    private static final BigDecimal MIN_PAYMENT = new BigDecimal("1.00");
    /** Clicking Pay again within this time for the same amount reopens the same checkout. */
    private static final Duration REUSE_CHECKOUT = Duration.ofMinutes(20);
    /** Paystack reports an unfinished checkout as "abandoned" straight away; give the student this long. */
    private static final Duration ABANDON_AFTER = Duration.ofHours(1);
    private static final Duration GIVE_UP_AFTER = Duration.ofHours(48);
    private static final DateTimeFormatter REF_DATE = DateTimeFormatter.ofPattern("yyMMdd");
    private static final char[] REF_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired private FeePaymentRepository paymentRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private FeeScheduleService scheduleService;
    @Autowired private AcademicSessionService academicSessionService;
    @Autowired private SystemSettingService settings;
    @Autowired private PaystackClient paystack;
    @Autowired private PlatformTransactionManager transactionManager;

    @Value("${app.frontend-url:http://localhost:4200}")
    private String frontendUrl;

    private final ObjectMapper mapper = new ObjectMapper();

    /** amount: a part payment (null = the full balance); items: pay these items of the breakdown instead. */
    public record PayRequest(BigDecimal amount, List<String> items) {}

    /** items: what the money was for (optional); the amount is then the items' balances. */
    public record ManualPaymentRequest(Long studentId, BigDecimal amount, String method, LocalDate paidOn, String note,
                                       List<String> items) {}

    /** One item being paid for, with the amount going to it. */
    private record Allocation(String name, BigDecimal amount) {}

    // ── Student ─────────────────────────────────────────────────────────

    /** The signed-in student's fee for the current session, what they've paid and their payment history. */
    @Transactional
    public Map<String, Object> statement(User principal) {
        requireVisible();
        User student = reloadStudent(principal);
        Optional<FeeSchedule> schedule = scheduleService.currentScheduleFor(student.getProgram(), student.getCurrentLevel());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("currency", scheduleService.currency());
        out.put("session", FeeScheduleService.sessionDto(academicSessionService.current()));
        out.put("onlinePayment", onlinePaymentAvailable());
        out.put("partPayment", settings.getBooleanSetting(SystemSettingService.FEES_PART_PAYMENT, true));
        out.put("itemPayment", settings.getBooleanSetting(SystemSettingService.FEES_ITEM_PAYMENT, true));
        out.put("minimumPayment", MIN_PAYMENT);

        Map<String, Object> who = new LinkedHashMap<>();
        who.put("name", CurrentUserService.displayName(student));
        who.put("studentId", student.getUsername());
        who.put("email", student.getEmail());
        who.put("programName", student.getProgram() != null ? student.getProgram().getName() : null);
        who.put("level", student.getCurrentLevel());
        out.put("student", who);

        if (schedule.isPresent()) {
            FeeSchedule s = schedule.get();
            BigDecimal paid = paymentRepository.sumPaid(student.getId(), s.getId());
            BigDecimal balance = s.getAmount().subtract(paid);
            out.put("fee", scheduleService.toDto(s));
            out.put("paid", paid);
            out.put("balance", balance.max(BigDecimal.ZERO));
            out.put("credit", balance.signum() < 0 ? balance.negate() : BigDecimal.ZERO);
            out.put("status", balance.signum() <= 0 ? "PAID" : paid.signum() > 0 ? "PART_PAID" : "UNPAID");
            out.put("items", s.isItemised() ? breakdown(s, student.getId(), paid).stream().map(FeeBreakdown.Line::toMap).toList() : List.of());
        } else {
            out.put("fee", null);
            out.put("paid", BigDecimal.ZERO);
            out.put("balance", BigDecimal.ZERO);
            out.put("credit", BigDecimal.ZERO);
            out.put("status", student.getProgram() == null || student.getCurrentLevel() == null ? "NO_CLASS" : "NO_FEE");
            out.put("items", List.of());
        }
        out.put("payments", paymentRepository.findForStudent(student.getId()).stream()
                .filter(p -> p.getStatus() != FeePayment.Status.VOIDED)
                .map(p -> toDto(p, false)).toList());
        return out;
    }

    /** Starts a Paystack checkout for the student and returns where to send them. */
    public Map<String, Object> startPayment(User principal, PayRequest req) {
        requireVisible();
        if (!settings.getBooleanSetting(SystemSettingService.FEES_ONLINE_PAYMENT, true))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Online payment is switched off. Please pay at the accounts office.");
        if (!paystack.isConfigured())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Online payment is not set up yet. Please contact the accounts office.");

        User student = reloadStudent(principal);
        if (student.getEmail() == null || !student.getEmail().contains("@"))
            throw new IllegalArgumentException("Add a valid email address to your profile before paying online; Paystack sends your payment confirmation there.");
        FeeSchedule schedule = scheduleService.currentScheduleFor(student.getProgram(), student.getCurrentLevel())
                .orElseThrow(() -> new IllegalArgumentException("No fee has been set for your class yet."));

        BigDecimal paidSoFar = paymentRepository.sumPaid(student.getId(), schedule.getId());
        BigDecimal balance = schedule.getAmount().subtract(paidSoFar);
        if (balance.signum() <= 0) throw new IllegalArgumentException("Your fees for this session are fully paid.");

        List<Allocation> allocations = List.of();
        BigDecimal amount;
        if (req != null && req.items() != null && !req.items().isEmpty()) {
            if (!settings.getBooleanSetting(SystemSettingService.FEES_ITEM_PAYMENT, true))
                throw new IllegalArgumentException("Paying for single items is switched off. Please pay the balance instead.");
            allocations = allocate(schedule, student.getId(), paidSoFar, req.items(), true);
            amount = allocations.stream().map(Allocation::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        } else {
            amount = req == null || req.amount() == null ? balance : FeeScheduleService.money(req.amount(), "The amount");
            boolean partAllowed = settings.getBooleanSetting(SystemSettingService.FEES_PART_PAYMENT, true);
            if (amount.compareTo(balance) > 0)
                throw new IllegalArgumentException("That is more than your balance of " + scheduleService.currency() + " " + balance.toPlainString() + ".");
            if (!partAllowed && amount.compareTo(balance) != 0)
                throw new IllegalArgumentException("Please pay the full balance of " + scheduleService.currency() + " " + balance.toPlainString() + ".");
            if (amount.compareTo(MIN_PAYMENT) < 0 && amount.compareTo(balance) != 0)
                throw new IllegalArgumentException("The smallest payment is " + scheduleService.currency() + " " + MIN_PAYMENT.toPlainString() + ".");
        }

        // Clicking Pay again for the same amount and items reopens the same checkout
        Map<String, BigDecimal> wanted = allocationKey(allocations);
        Optional<FeePayment> open = paymentRepository.findOpenCheckouts(student.getId(), schedule.getId(), FeePayment.Status.PENDING,
                        amount, LocalDateTime.now().minus(REUSE_CHECKOUT)).stream()
                .filter(x -> x.getAuthorizationUrl() != null)
                .filter(x -> allocationKey(x.getItems().stream().map(i -> new Allocation(i.getName(), i.getAmount())).toList()).equals(wanted))
                .findFirst();
        if (open.isPresent()) {
            return Map.of("reference", open.get().getReference(), "authorizationUrl", open.get().getAuthorizationUrl());
        }

        FeePayment p = new FeePayment();
        p.setStudent(student);
        p.setSchedule(schedule);
        p.setSession(schedule.getSession());
        p.setProgramName(schedule.getProgram().getName());
        p.setLevel(schedule.getLevel());
        p.setAmount(amount);
        p.setCurrency(scheduleService.currency());
        p.setReference(newReference("FEE"));
        p.setMethod(FeePayment.Method.PAYSTACK);
        p.setStatus(FeePayment.Status.PENDING);
        p.setPayerEmail(student.getEmail());
        addItems(p, allocations);
        p = paymentRepository.save(p);

        try {
            String returnUrl = frontendUrl.replaceAll("/+$", "") + "/user-dashboard/fees";
            JsonNode data = paystack.initialize(student.getEmail(), minor(amount), p.getCurrency(), p.getReference(),
                    returnUrl, metadata(student, schedule, p, returnUrl + "?cancelled=" + p.getReference()));
            p.setAuthorizationUrl(data.path("authorization_url").asText(null));
            paymentRepository.save(p);
        } catch (PaystackClient.PaystackException e) {
            p.setStatus(FeePayment.Status.FAILED);
            p.setGatewayMessage(trim(e.getMessage(), 255));
            paymentRepository.save(p);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getMessage());
        }
        if (p.getAuthorizationUrl() == null)
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Paystack did not return a checkout page. Please try again.");
        log.info("[Fees] Checkout {} started for student {} ({} {})", p.getReference(), student.getId(), p.getCurrency(), amount);
        return Map.of("reference", p.getReference(), "authorizationUrl", p.getAuthorizationUrl());
    }

    /** Called when the student comes back from Paystack: confirms the payment with Paystack and returns it. */
    public Map<String, Object> confirmForStudent(User principal, String reference) {
        FeePayment p = paymentRepository.findByReference(reference)
                .filter(x -> x.getStudent().getId().equals(principal.getId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found."));
        if (p.getStatus() != FeePayment.Status.SUCCESS && p.getMethod() == FeePayment.Method.PAYSTACK) {
            try {
                refresh(reference);
            } catch (PaystackClient.PaystackException e) {
                log.warn("[Fees] Could not confirm {} with Paystack: {}", reference, e.getMessage());
            }
        }
        return dtoByReference(reference, false);
    }

    // ── Paystack webhook and background check ───────────────────────────

    /** Returns false when the signature is wrong (the caller answers 401); true otherwise. */
    public boolean handleWebhook(byte[] body, String signature) {
        if (!paystack.validSignature(body, signature)) {
            log.warn("[Fees] Rejected a Paystack webhook with a bad signature");
            return false;
        }
        try {
            JsonNode root = mapper.readTree(body);
            String event = root.path("event").asText("");
            JsonNode data = root.path("data");
            String reference = data.path("reference").asText("");
            if (reference.isEmpty() || !reference.startsWith("FEE-")) return true;    // not one of ours
            if ("charge.success".equals(event)) {
                FeePayment p = settle(reference, data);
                if (p != null) log.info("[Fees] Webhook settled {} as {}", reference, p.getStatus());
            }
        } catch (Exception e) {
            // Answer 200 anyway: Paystack retries on errors, and the background check will pick it up
            log.error("[Fees] Could not process a Paystack webhook", e);
        }
        return true;
    }

    /** Settles payments whose student never came back and whose webhook never arrived. */
    @Scheduled(initialDelay = 120_000, fixedDelay = 600_000)
    public void reconcilePending() {
        if (!paystack.isConfigured()) return;
        List<String> refs = paymentRepository.pendingReferencesBefore(LocalDateTime.now().minusMinutes(5), PageRequest.of(0, 50));
        for (String ref : refs) {
            try {
                refresh(ref);
            } catch (Exception e) {
                log.warn("[Fees] Background check of {} failed: {}", ref, e.getMessage());
                giveUpIfOld(ref);
            }
        }
    }

    /** Asks Paystack for the payment's current state and records it. */
    public FeePayment refresh(String reference) {
        return settle(reference, paystack.verify(reference));
    }

    private FeePayment settle(String reference, JsonNode data) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            FeePayment p = paymentRepository.lockByReference(reference).orElse(null);
            if (p == null || p.getStatus() == FeePayment.Status.SUCCESS || p.getStatus() == FeePayment.Status.VOIDED) return p;

            String gatewayStatus = data.path("status").asText("");
            switch (gatewayStatus) {
                case "success" -> {
                    long expected = minor(p.getAmount());
                    long charged = data.path("amount").asLong(-1);
                    long requested = data.path("requested_amount").asLong(charged);
                    String currency = data.path("currency").asText("");
                    if ((charged != expected && requested != expected) || !currency.equalsIgnoreCase(p.getCurrency())) {
                        p.setStatus(FeePayment.Status.FAILED);
                        p.setGatewayMessage("Paystack charged " + currency + " " + charged + " (minor units) instead of "
                                + p.getCurrency() + " " + expected + ". Check this payment on the Paystack dashboard.");
                        log.error("[Fees] Amount mismatch on {}: expected {} {}, Paystack reported {} {}",
                                reference, p.getCurrency(), expected, currency, charged);
                    } else {
                        p.setStatus(FeePayment.Status.SUCCESS);
                        p.setPaidAt(parseTime(data.path("paid_at").asText(null)));
                        p.setChannel(trim(data.path("channel").asText(null), 32));
                        p.setGatewayMessage(trim(data.path("gateway_response").asText(null), 255));
                        if (data.hasNonNull("id")) p.setGatewayTransactionId(data.path("id").asLong());
                    }
                }
                case "failed", "reversed" -> {
                    p.setStatus(FeePayment.Status.FAILED);
                    p.setGatewayMessage(trim(data.path("gateway_response").asText("The payment did not go through."), 255));
                    p.setChannel(trim(data.path("channel").asText(null), 32));
                }
                case "abandoned" -> {
                    if (p.getCreatedAt().isBefore(LocalDateTime.now().minus(ABANDON_AFTER))) {
                        p.setStatus(FeePayment.Status.ABANDONED);
                        p.setGatewayMessage("Checkout was not completed.");
                    }
                }
                default -> { /* ongoing, pending, processing, queued: still waiting on the payer */ }
            }
            return paymentRepository.save(p);
        });
    }

    private void giveUpIfOld(String reference) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                paymentRepository.lockByReference(reference).ifPresent(p -> {
                    if (p.getStatus() == FeePayment.Status.PENDING && p.getCreatedAt().isBefore(LocalDateTime.now().minus(GIVE_UP_AFTER))) {
                        p.setStatus(FeePayment.Status.ABANDONED);
                        p.setGatewayMessage("Paystack never confirmed this checkout.");
                        paymentRepository.save(p);
                    }
                }));
    }

    // ── Super Admin ─────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Map<String, Object> search(Long sessionId, String status, Long programId, Integer level, String q, int page, int size) {
        Long sid = sessionId != null ? sessionId : academicSessionService.current().getId();
        FeePayment.Status st = null;
        if (status != null && !status.isBlank()) {
            try { st = FeePayment.Status.valueOf(status.trim().toUpperCase()); }
            catch (IllegalArgumentException e) { throw new IllegalArgumentException("Unknown status " + status + "."); }
        }
        String like = q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
        Page<FeePayment> result = paymentRepository.search(sid, st, programId, level, like,
                PageRequest.of(Math.max(0, page), Math.min(100, Math.max(1, size)), Sort.by(Sort.Direction.DESC, "createdAt")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("content", result.getContent().stream().map(p -> toDto(p, true)).toList());
        out.put("total", result.getTotalElements());
        out.put("page", result.getNumber());
        out.put("size", result.getSize());
        return out;
    }

    /** Students matching q, with their fee and balance for the current session (for recording a payment). */
    @Transactional
    public List<Map<String, Object>> searchStudents(String q) {
        if (q == null || q.trim().length() < 2) return List.of();
        List<User> found = userRepository.searchStudents("%" + q.trim().toLowerCase(Locale.ROOT) + "%", PageRequest.of(0, 15));
        List<Map<String, Object>> out = new ArrayList<>();
        for (User u : found) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", u.getId());
            m.put("name", CurrentUserService.displayName(u));
            m.put("studentId", u.getUsername());
            m.put("email", u.getEmail());
            m.put("programName", u.getProgram() != null ? u.getProgram().getName() : null);
            m.put("level", u.getCurrentLevel());
            Optional<FeeSchedule> s = scheduleService.currentScheduleFor(u.getProgram(), u.getCurrentLevel());
            m.put("fee", s.map(FeeSchedule::getAmount).orElse(null));
            BigDecimal paid = s.map(x -> paymentRepository.sumPaid(u.getId(), x.getId())).orElse(BigDecimal.ZERO);
            m.put("balance", s.map(x -> x.getAmount().subtract(paid).max(BigDecimal.ZERO)).orElse(null));
            m.put("items", s.filter(FeeSchedule::isItemised)
                    .map(x -> breakdown(x, u.getId(), paid).stream().map(FeeBreakdown.Line::toMap).toList())
                    .orElse(List.of()));
            out.add(m);
        }
        return out;
    }

    /** Records money received at the accounts office (cash, bank deposit …) against the student's current fee. */
    @Transactional
    public Map<String, Object> recordManual(ManualPaymentRequest req, String actor) {
        if (req == null || req.studentId() == null) throw new IllegalArgumentException("Choose a student.");
        User student = userRepository.findById(req.studentId())
                .filter(u -> u.getRole() == Role.NORMAL)
                .orElseThrow(() -> new IllegalArgumentException("Student not found."));
        FeeSchedule schedule = scheduleService.currentScheduleFor(student.getProgram(), student.getCurrentLevel())
                .orElseThrow(() -> new IllegalArgumentException("No fee is set for this student's programme and level in the current session."));
        BigDecimal paidSoFar = paymentRepository.sumPaid(student.getId(), schedule.getId());
        BigDecimal balance = schedule.getAmount().subtract(paidSoFar);
        List<Allocation> allocations = req.items() == null || req.items().isEmpty() ? List.of()
                : allocate(schedule, student.getId(), paidSoFar, req.items(), false);
        BigDecimal amount = allocations.isEmpty()
                ? FeeScheduleService.money(req.amount(), "The amount")
                : allocations.stream().map(Allocation::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (amount.compareTo(balance) > 0)
            throw new IllegalArgumentException("That is more than the student's balance of " + scheduleService.currency() + " " + balance.max(BigDecimal.ZERO).toPlainString() + ".");

        FeePayment.Method method;
        try { method = FeePayment.Method.valueOf(req.method() == null ? "CASH" : req.method().trim().toUpperCase()); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Choose how the money was paid."); }
        if (method == FeePayment.Method.PAYSTACK) throw new IllegalArgumentException("Online payments are recorded automatically.");
        if (req.paidOn() != null && req.paidOn().isAfter(LocalDate.now())) throw new IllegalArgumentException("The payment date can't be in the future.");
        String note = req.note() == null || req.note().isBlank() ? null : req.note().trim();
        if (note != null && note.length() > 300) throw new IllegalArgumentException("The note is too long (300 characters at most).");

        FeePayment p = new FeePayment();
        p.setStudent(student);
        p.setSchedule(schedule);
        p.setSession(schedule.getSession());
        p.setProgramName(schedule.getProgram().getName());
        p.setLevel(schedule.getLevel());
        p.setAmount(amount);
        p.setCurrency(scheduleService.currency());
        p.setReference(newReference("MAN"));
        p.setMethod(method);
        p.setStatus(FeePayment.Status.SUCCESS);
        p.setPaidAt(req.paidOn() == null || req.paidOn().equals(LocalDate.now()) ? LocalDateTime.now() : req.paidOn().atStartOfDay());
        p.setRecordedBy(actor);
        p.setNote(note);
        p.setPayerEmail(student.getEmail());
        addItems(p, allocations);
        return toDto(paymentRepository.save(p), true);
    }

    /** Cancels a cash / bank payment entered by mistake. Online payments can only be refunded on Paystack. */
    @Transactional
    public Map<String, Object> voidManual(Long id, String reason, String actor) {
        FeePayment p = paymentRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Payment not found."));
        if (p.getMethod() == FeePayment.Method.PAYSTACK)
            throw new IllegalArgumentException("Online payments can't be cancelled here. Refund them from the Paystack dashboard.");
        if (p.getStatus() != FeePayment.Status.SUCCESS) throw new IllegalArgumentException("Only a recorded payment can be cancelled.");
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("Give a reason for cancelling this payment.");
        p.setStatus(FeePayment.Status.VOIDED);
        p.setNote(trim("Cancelled by " + actor + ": " + reason.trim() + (p.getNote() != null ? " | " + p.getNote() : ""), 300));
        return toDto(paymentRepository.save(p), true);
    }

    /** Super Admin: ask Paystack again about a payment still pending. */
    public Map<String, Object> recheck(Long id) {
        FeePayment p = paymentRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Payment not found."));
        if (p.getMethod() != FeePayment.Method.PAYSTACK) throw new IllegalArgumentException("Only online payments can be checked with Paystack.");
        try {
            refresh(p.getReference());
        } catch (PaystackClient.PaystackException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getMessage());
        }
        return dtoByReference(p.getReference(), true);
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    /** Each item's amount, paid and balance for this student (the schedule's components must be loaded). */
    private List<FeeBreakdown.Line> breakdown(FeeSchedule schedule, Long studentId, BigDecimal totalPaid) {
        Map<String, BigDecimal> forItems = new HashMap<>();
        for (Object[] row : paymentRepository.paidItems(studentId, schedule.getId())) {
            forItems.merge((String) row[0], (BigDecimal) row[1], BigDecimal::add);
        }
        return FeeBreakdown.compute(schedule.getComponents(), forItems, totalPaid);
    }

    /** The chosen items, each with its whole remaining balance. */
    private List<Allocation> allocate(FeeSchedule schedule, Long studentId, BigDecimal paidSoFar, List<String> names, boolean byStudent) {
        if (!schedule.isItemised())
            throw new IllegalArgumentException("This fee has no breakdown. Pay towards the balance instead.");
        List<FeeBreakdown.Line> lines = breakdown(schedule, studentId, paidSoFar);
        List<Allocation> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String raw : names) {
            String key = FeeBreakdown.key(raw);
            if (key.isEmpty() || !seen.add(key)) continue;
            FeeBreakdown.Line line = lines.stream().filter(l -> FeeBreakdown.key(l.name()).equals(key)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("\"" + raw.trim() + "\" is not part of " + (byStudent ? "your" : "this") + " fee."));
            if (line.balance().signum() <= 0)
                throw new IllegalArgumentException(line.name() + " is already paid.");
            out.add(new Allocation(line.name(), line.balance()));
        }
        if (out.isEmpty()) throw new IllegalArgumentException("Choose at least one item to pay for.");
        return out;
    }

    private static void addItems(FeePayment p, List<Allocation> allocations) {
        for (Allocation a : allocations) {
            FeePaymentItem item = new FeePaymentItem();
            item.setPayment(p);
            item.setName(a.name());
            item.setAmount(a.amount());
            p.getItems().add(item);
        }
    }

    private static Map<String, BigDecimal> allocationKey(List<Allocation> allocations) {
        Map<String, BigDecimal> m = new TreeMap<>();
        for (Allocation a : allocations) m.merge(FeeBreakdown.key(a.name()), a.amount().stripTrailingZeros(), BigDecimal::add);
        return m;
    }

    private boolean onlinePaymentAvailable() {
        return paystack.isConfigured() && settings.getBooleanSetting(SystemSettingService.FEES_ONLINE_PAYMENT, true);
    }

    private void requireVisible() {
        if (!settings.getBooleanSetting(SystemSettingService.FEES_VISIBLE_STUDENT, false))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Fees are not available right now.");
    }

    private User reloadStudent(User principal) {
        if (principal == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please sign in to continue.");
        User u = userRepository.findById(principal.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please sign in to continue."));
        if (u.getRole() != Role.NORMAL) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only students have fees.");
        return u;
    }

    private Map<String, Object> dtoByReference(String reference, boolean forStaff) {
        return new TransactionTemplate(transactionManager).execute(s ->
                paymentRepository.findByReference(reference).map(p -> toDto(p, forStaff))
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found.")));
    }

    /** Needs an open transaction (student and session are lazy). */
    private Map<String, Object> toDto(FeePayment p, boolean forStaff) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("reference", p.getReference());
        m.put("amount", p.getAmount());
        m.put("currency", p.getCurrency());
        m.put("status", p.getStatus().name());
        m.put("method", p.getMethod().name());
        m.put("channel", p.getChannel());
        m.put("message", p.getGatewayMessage());
        m.put("createdAt", p.getCreatedAt());
        m.put("paidAt", p.getPaidAt());
        m.put("sessionName", p.getSession().getName());
        m.put("programName", p.getProgramName());
        m.put("level", p.getLevel());
        m.put("studentName", CurrentUserService.displayName(p.getStudent()));
        m.put("studentId", p.getStudent().getUsername());
        m.put("items", p.getItems().stream().map(i -> {
            Map<String, Object> im = new LinkedHashMap<>();
            im.put("name", i.getName());
            im.put("amount", i.getAmount());
            return im;
        }).toList());
        if (forStaff) {
            m.put("studentEmail", p.getStudent().getEmail());
            m.put("recordedBy", p.getRecordedBy());
            m.put("note", p.getNote());
            m.put("gatewayTransactionId", p.getGatewayTransactionId());
        }
        return m;
    }

    private static Map<String, Object> metadata(User student, FeeSchedule schedule, FeePayment p, String cancelUrl) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("payment_id", p.getId());
        meta.put("student_id", student.getId());
        meta.put("schedule_id", schedule.getId());
        meta.put("cancel_action", cancelUrl);   // where Paystack's "Cancel payment" sends the student
        meta.put("custom_fields", List.of(
                field("Student", CurrentUserService.displayName(student)),
                field("Student ID", student.getUsername()),
                field("Programme", schedule.getProgram().getName()),
                field("Level", String.valueOf(schedule.getLevel())),
                field("Session", schedule.getSession().getName()),
                field("Paying for", p.getItems().isEmpty() ? "Fees"
                        : String.join(", ", p.getItems().stream().map(FeePaymentItem::getName).toList()))));
        return meta;
    }

    private static Map<String, Object> field(String name, String value) {
        return Map.of("display_name", name, "variable_name", name.toLowerCase(Locale.ROOT).replace(' ', '_'), "value", value == null ? "" : value);
    }

    private static String newReference(String prefix) {
        StringBuilder sb = new StringBuilder(prefix).append('-').append(LocalDate.now().format(REF_DATE)).append('-');
        for (int i = 0; i < 10; i++) sb.append(REF_CHARS[RANDOM.nextInt(REF_CHARS.length)]);
        return sb.toString();
    }

    /** Pesewas / kobo / cents. */
    static long minor(BigDecimal amount) {
        return amount.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
    }

    private static LocalDateTime parseTime(String iso) {
        if (iso == null || iso.isBlank()) return LocalDateTime.now();
        try {
            return LocalDateTime.ofInstant(OffsetDateTime.parse(iso).toInstant(), ZoneId.systemDefault());
        } catch (Exception e) {
            return LocalDateTime.now();
        }
    }

    private static String trim(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
