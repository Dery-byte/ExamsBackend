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
 * bank payments the accounts office records. What a student owes comes from {@link FeeDues}: their
 * class's whole-year fee, or each term up to the current one, priced by the items that apply to them,
 * plus their bills. A payment can be for a chosen fee or bill, or for everything owed; {@link FeeLedger}
 * splits it when it succeeds (the chosen one first, then oldest first, the rest as credit). Each
 * successful payment gets a numbered receipt with a verification code ({@link ReceiptService}).
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
    @Autowired private ResultsHoldService resultsHoldService;
    @Autowired private FeeDues feeDues;
    @Autowired private ReceiptService receipts;
    @Autowired private FeeLedger ledger;
    @Autowired private com.exam.repository.FeeAllocationRepository allocationRepository;
    @Autowired private com.exam.repository.WalletWithdrawalRepository withdrawalRepository;

    @Value("${app.frontend-url:http://localhost:4200}")
    private String frontendUrl;

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * amount: a part payment (null = the whole balance of what is paid for); items: pay these items of a
     * fee's breakdown instead; scheduleId / billId: pay that fee (term) or bill first; neither = everything
     * owed, oldest first.
     */
    public record PayRequest(BigDecimal amount, List<String> items, Long scheduleId, Long billId) {
        public PayRequest(BigDecimal amount, List<String> items, Long scheduleId) { this(amount, items, scheduleId, null); }
        public PayRequest(BigDecimal amount, List<String> items) { this(amount, items, null, null); }
    }

    /**
     * items: what the money was for (optional); the amount is then the items' balances. scheduleId /
     * billId: as for PayRequest. Money beyond everything owed is kept as credit.
     */
    public record ManualPaymentRequest(Long studentId, BigDecimal amount, String method, LocalDate paidOn, String note,
                                       List<String> items, Long scheduleId, Long billId) {
        public ManualPaymentRequest(Long studentId, BigDecimal amount, String method, LocalDate paidOn, String note,
                                    List<String> items, Long scheduleId) {
            this(studentId, amount, method, paidOn, note, items, scheduleId, null);
        }
        public ManualPaymentRequest(Long studentId, BigDecimal amount, String method, LocalDate paidOn, String note, List<String> items) {
            this(studentId, amount, method, paidOn, note, items, null, null);
        }
    }

    /** What a payment is for: one fee (items optional), one bill, or everything (both null); balance = the most it may be. */
    private record Target(FeeDues.Due due, FeeDues.BillDue bill, BigDecimal balance) {
        FeeSchedule schedule() { return due == null ? null : due.schedule(); }
        String name() {
            return due != null ? "your " + FeeDues.periodName(due.schedule()).toLowerCase(Locale.ROOT) + " fees"
                    : bill != null ? "\"" + bill.bill().getTitle() + "\"" : "what you owe";
        }
    }

    /** One item being paid for, with the amount going to it. */
    private record Allocation(String name, BigDecimal amount) {}

    // ── Student ─────────────────────────────────────────────────────────

    /**
     * The signed-in student's account for the current session (each term owed so far, what is coming
     * up, their bills, credit), what they've paid and their payment history. The top-level fee / paid /
     * balance / items are those of the fee the next payment goes to (the oldest with a balance).
     */
    @Transactional
    public Map<String, Object> statement(User principal) {
        requireVisible();
        return account(reloadStudent(principal), false);
    }

    /** The accounts office's view of one student's account (bills of every status, payments with notes). */
    @Transactional
    public Map<String, Object> staffStatement(Long studentId) {
        User student = userRepository.findById(studentId).filter(u -> u.getRole() == Role.NORMAL)
                .orElseThrow(() -> new IllegalArgumentException("Student not found."));
        return account(student, true);
    }

    private Map<String, Object> account(User student, boolean forStaff) {
        FeeDues.Account account = ledger.settled(student);    // credit waiting for something new to pay
        List<FeeDues.Due> dues = account.fees();
        Optional<FeeDues.Due> next = dues.stream().filter(d -> d.balance().signum() > 0).findFirst()
                .or(() -> dues.isEmpty() ? Optional.empty() : Optional.of(dues.get(dues.size() - 1)));

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
        if (forStaff) who.put("id", student.getId());
        out.put("student", who);

        if (next.isPresent()) {
            FeeDues.Due d = next.get();
            out.put("fee", dueFee(d));
            out.put("paid", d.paid());
            out.put("balance", d.balance());
            out.put("credit", d.credit());
            out.put("status", d.status());
            out.put("items", itemLines(d, student.getId()));
        } else {
            out.put("fee", null);
            out.put("paid", BigDecimal.ZERO);
            out.put("balance", BigDecimal.ZERO);
            out.put("credit", BigDecimal.ZERO);
            out.put("status", student.getProgram() == null || student.getCurrentLevel() == null ? "NO_CLASS" : "NO_FEE");
            out.put("items", List.of());
        }
        out.put("dues", dues.stream().map(d -> dueDto(d, student.getId())).toList());
        out.put("upcoming", feeDues.upcoming(student).stream().map(this::dueFee).toList());
        out.put("totalFees", dues.stream().map(FeeDues.Due::amount).reduce(BigDecimal.ZERO, BigDecimal::add));
        out.put("totalPaid", dues.stream().map(FeeDues.Due::paid).reduce(BigDecimal.ZERO, BigDecimal::add));
        out.put("totalBalance", account.feesBalance());
        out.put("totalDiscount", dues.stream().map(FeeDues.Due::discount).reduce(BigDecimal.ZERO, BigDecimal::add));
        out.put("arrears", account.arrears().stream().map(YearEndService::arrearsDto).toList());
        out.put("arrearsBalance", account.arrearsBalance());
        out.put("bills", account.bills().stream().filter(b -> forStaff || b.bill().getStatus() != com.exam.model.fees.StudentBill.Status.CANCELLED)
                .map(BillingService::billDto).toList());
        out.put("billsBalance", account.billsBalance());
        out.put("accountCredit", account.credit());
        out.put("toPay", account.toPay());
        com.exam.model.Residency residency = feeDues.residencyOf(student);
        out.put("residency", residency == null ? null : residency.name());
        out.put("payments", paymentRepository.findForStudent(student.getId()).stream()
                .filter(p -> forStaff || p.getStatus() != FeePayment.Status.VOIDED)
                .map(p -> toDto(p, forStaff)).toList());
        // What their unpaid fees are keeping from them (null when nothing is held)
        out.put("resultsHold", resultsHoldService.check(student, EnumSet.allOf(ResultsHoldService.Document.class)));
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
        boolean byItems = req != null && req.items() != null && !req.items().isEmpty();
        Target target = target(student, req == null ? null : req.scheduleId(), req == null ? null : req.billId(), byItems);
        BigDecimal balance = target.balance();
        if (balance.signum() <= 0)
            throw new IllegalArgumentException(Character.toUpperCase(target.name().charAt(0)) + target.name().substring(1) + (target.bill() != null ? " is" : " are") + " fully paid.");

        List<Allocation> allocations = List.of();
        BigDecimal amount;
        if (byItems) {
            if (!settings.getBooleanSetting(SystemSettingService.FEES_ITEM_PAYMENT, true))
                throw new IllegalArgumentException("Paying for single items is switched off. Please pay the balance instead.");
            allocations = allocate(target.due(), student.getId(), req.items(), true);
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

        // Clicking Pay again for the same amount, fee / bill and items reopens the same checkout
        Map<String, BigDecimal> wanted = allocationKey(allocations);
        FeeSchedule schedule = target.schedule();
        com.exam.model.fees.StudentBill bill = target.bill() == null ? null : target.bill().bill();
        Optional<FeePayment> open = paymentRepository.findOpenCheckouts(student.getId(), FeePayment.Status.PENDING,
                        amount, LocalDateTime.now().minus(REUSE_CHECKOUT)).stream()
                .filter(x -> x.getAuthorizationUrl() != null && !x.isWalletDeposit() && x.getSchedule() == schedule && x.getBill() == bill)
                .filter(x -> allocationKey(x.getItems().stream().map(i -> new Allocation(i.getName(), i.getAmount())).toList()).equals(wanted))
                .findFirst();
        if (open.isPresent()) {
            return Map.of("reference", open.get().getReference(), "authorizationUrl", open.get().getAuthorizationUrl());
        }

        FeePayment p = newPayment(student, schedule, bill, amount);
        p.setReference(newReference("FEE"));
        addItems(p, allocations);
        return checkout(student, p, "/user-dashboard/fees");
    }

    /**
     * Starts a Paystack checkout that puts money in the student's wallet (WalletService checks the
     * wallet is on and has room). Clicking Deposit again for the same amount reopens the same checkout.
     */
    public Map<String, Object> startDeposit(User student, BigDecimal amount) {
        if (!onlinePaymentAvailable())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Online payment isn't open right now. You can pay into your wallet at the accounts office.");
        if (student.getEmail() == null || !student.getEmail().contains("@"))
            throw new IllegalArgumentException("Add a valid email address to your profile before paying online; Paystack sends your payment confirmation there.");
        Optional<FeePayment> open = paymentRepository.findOpenCheckouts(student.getId(), FeePayment.Status.PENDING,
                        amount, LocalDateTime.now().minus(REUSE_CHECKOUT)).stream()
                .filter(x -> x.getAuthorizationUrl() != null && x.isWalletDeposit())
                .findFirst();
        if (open.isPresent()) return Map.of("reference", open.get().getReference(), "authorizationUrl", open.get().getAuthorizationUrl());
        FeePayment p = newPayment(student, null, null, amount);
        p.setWalletDeposit(true);
        p.setReference(newReference("WAL"));
        return checkout(student, p, "/user-dashboard/wallet");
    }

    /** Saves the payment as PENDING and opens Paystack's checkout for it; Paystack sends the student back to returnPath. */
    private Map<String, Object> checkout(User student, FeePayment p, String returnPath) {
        p.setMethod(FeePayment.Method.PAYSTACK);
        p.setStatus(FeePayment.Status.PENDING);
        p.setPayerEmail(student.getEmail());
        p = paymentRepository.save(p);

        try {
            String returnUrl = frontendUrl.replaceAll("/+$", "") + returnPath;
            JsonNode data = paystack.initialize(student.getEmail(), minor(p.getAmount()), p.getCurrency(), p.getReference(),
                    returnUrl, metadata(student, p, returnUrl + "?cancelled=" + p.getReference()));
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
        log.info("[Fees] Checkout {} started for student {} ({} {})", p.getReference(), student.getId(), p.getCurrency(), p.getAmount());
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
            if (!reference.startsWith("FEE-") && !reference.startsWith("WAL-")) return true;    // not one of ours
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
                        succeed(p);
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
            com.exam.model.Residency residency = feeDues.residencyOf(u);
            m.put("residency", residency == null ? null : residency.name());
            m.put("enabled", u.isEnabled());
            FeeDues.Account account = feeDues.account(u);
            List<FeeDues.Due> dues = account.fees();
            Optional<FeeDues.Due> next = dues.stream().filter(d -> d.balance().signum() > 0).findFirst();
            // fee / items: the fee a payment goes to by default (the oldest with a balance); balance: everything owed less credit
            m.put("fee", next.map(FeeDues.Due::amount).orElse(dues.isEmpty() ? null : dues.get(dues.size() - 1).amount()));
            m.put("balance", dues.isEmpty() && account.bills().isEmpty() ? null : account.toPay());
            m.put("credit", account.credit());
            m.put("items", next.map(d -> itemLines(d, u.getId())).orElse(List.of()));
            m.put("dues", dues.stream().map(d -> dueDto(d, u.getId())).toList());
            m.put("bills", account.openBills().stream().filter(b -> b.balance().signum() > 0).map(BillingService::billDto).toList());
            out.add(m);
        }
        return out;
    }

    /**
     * Records money received at the accounts office (cash, bank deposit …): to the fee or bill chosen
     * first, then whatever else is owed, oldest first; anything beyond that is kept as credit.
     */
    @Transactional
    public Map<String, Object> recordManual(ManualPaymentRequest req, User actor) {
        return record(req, CurrentUserService.displayName(actor), actor.getId());
    }

    /** Without a staff account (tests, imports): the payment is in no one's cash-up. */
    @Transactional
    public Map<String, Object> recordManual(ManualPaymentRequest req, String actor) {
        return record(req, actor, null);
    }

    private Map<String, Object> record(ManualPaymentRequest req, String actor, Long actorId) {
        if (req == null || req.studentId() == null) throw new IllegalArgumentException("Choose a student.");
        User student = userRepository.findById(req.studentId())
                .filter(u -> u.getRole() == Role.NORMAL)
                .orElseThrow(() -> new IllegalArgumentException("Student not found."));
        boolean byItems = req.items() != null && !req.items().isEmpty();
        Target target = target(student, req.scheduleId(), req.billId(), byItems);
        List<Allocation> allocations = byItems ? allocate(target.due(), student.getId(), req.items(), false) : List.of();
        BigDecimal amount = allocations.isEmpty()
                ? FeeScheduleService.money(req.amount(), "The amount")
                : allocations.stream().map(Allocation::amount).reduce(BigDecimal.ZERO, BigDecimal::add);

        FeePayment p = newPayment(student, target.schedule(), target.bill() == null ? null : target.bill().bill(), amount);
        p.setReference(newReference("MAN"));
        manual(p, req.method(), req.paidOn(), req.note(), actor, actorId);
        addItems(p, allocations);
        succeed(p);
        return toDto(paymentRepository.save(p), true);
    }

    /** Fills in money received at the office: how, when, the note and who recorded it. */
    private static void manual(FeePayment p, String methodName, LocalDate paidOn, String rawNote, String actor, Long actorId) {
        FeePayment.Method method;
        try { method = FeePayment.Method.valueOf(methodName == null ? "CASH" : methodName.trim().toUpperCase()); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Choose how the money was paid."); }
        if (method == FeePayment.Method.PAYSTACK) throw new IllegalArgumentException("Online payments are recorded automatically.");
        if (paidOn != null && paidOn.isAfter(LocalDate.now())) throw new IllegalArgumentException("The payment date can't be in the future.");
        String note = rawNote == null || rawNote.isBlank() ? null : rawNote.trim();
        if (note != null && note.length() > 300) throw new IllegalArgumentException("The note is too long (300 characters at most).");
        p.setMethod(method);
        p.setStatus(FeePayment.Status.SUCCESS);
        p.setPaidAt(paidOn == null || paidOn.equals(LocalDate.now()) ? LocalDateTime.now() : paidOn.atStartOfDay());
        p.setRecordedBy(actor);
        p.setRecordedById(actorId);
        p.setNote(note);
        p.setPayerEmail(p.getStudent().getEmail());
    }

    /**
     * Money paid into a student's wallet at the accounts office (cash, bank deposit …); WalletService
     * checks the wallet is on and has room. It stays in the wallet until the student uses it.
     */
    @Transactional
    public Map<String, Object> recordDeposit(User student, BigDecimal amount, String method, LocalDate paidOn, String note, User actor) {
        FeePayment p = newPayment(student, null, null, amount);
        p.setWalletDeposit(true);
        p.setReference(newReference("WAL"));
        manual(p, method, paidOn, note, CurrentUserService.displayName(actor), actor.getId());
        succeed(p);
        return toDto(paymentRepository.save(p), true);
    }

    /**
     * Cancels a cash / bank payment entered by mistake (it stays in the register as cancelled). The
     * cashier who recorded it may cancel it until they hand it in (cash-up); after that only the bursar.
     * Online payments can't be cancelled, and a payment part of which was refunded needs that refund
     * cancelled first.
     */
    @Transactional
    public Map<String, Object> voidManual(Long id, String reason, User actor) {
        FeePayment p = paymentRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Payment not found."));
        if (!FinanceAccess.isBursar(actor) && !(Objects.equals(p.getRecordedById(), actor.getId()) && p.getCashUp() == null))
            throw new org.springframework.security.access.AccessDeniedException(Objects.equals(p.getRecordedById(), actor.getId())
                    ? "This payment is already handed in (cash-up). Only the bursar can cancel it now."
                    : "You can cancel only payments you recorded and haven't handed in. Ask the bursar.");
        return cancel(p, reason, CurrentUserService.displayName(actor));
    }

    /** Without a staff account (tests): as the bursar. */
    @Transactional
    Map<String, Object> voidManual(Long id, String reason, String actor) {
        return cancel(paymentRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Payment not found.")), reason, actor);
    }

    private Map<String, Object> cancel(FeePayment p, String reason, String actor) {
        if (p.getMethod() == FeePayment.Method.PAYSTACK)
            throw new IllegalArgumentException("Online payments can't be cancelled. To pay money back, remove the charge so it becomes credit, then refund it.");
        if (p.getStatus() != FeePayment.Status.SUCCESS) throw new IllegalArgumentException("Only a recorded payment can be cancelled.");
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("Give a reason for cancelling this payment.");
        ledger.lock(p.getStudent());
        if (p.getAllocations().stream().anyMatch(a -> a.getRefund() != null))
            throw new IllegalArgumentException("Part of this payment was refunded. Cancel the refund first.");
        if (p.isWalletDeposit()) {
            // Only money still sitting in the wallet can be taken back out of it
            if (p.getAllocations().stream().anyMatch(a -> !a.isWallet()))
                throw new IllegalArgumentException("The student has already used part of this deposit, so it can't be cancelled.");
            Long studentId = p.getStudent().getId();
            if (allocationRepository.wallet(studentId).subtract(p.getAmount()).compareTo(withdrawalRepository.held(studentId)) < 0)
                throw new IllegalArgumentException("Part of this deposit is held for a withdrawal request. Reject or finish that request first.");
        }
        p.setStatus(FeePayment.Status.VOIDED);
        p.setVoidedAt(LocalDateTime.now());
        p.setVoidedBy(actor);
        p.setNote(trim("Cancelled by " + actor + ": " + reason.trim() + (p.getNote() != null ? " | " + p.getNote() : ""), 300));
        receipts.revoke(p, reason.trim());
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

    /**
     * The fee (paying by items needs one: the chosen, else the oldest unpaid), the bill, or everything.
     * Paying everything is capped at what is owed less credit.
     */
    private Target target(User student, Long scheduleId, Long billId, boolean byItems) {
        if (billId != null && !byItems) {
            FeeDues.BillDue b = feeDues.bills(student).stream().filter(x -> x.bill().getId().equals(billId) && x.bill().isOpen()).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("That bill isn't open, or isn't this student's."));
            return new Target(null, b, b.balance());
        }
        if (scheduleId != null || byItems) {
            FeeDues.Due d = feeDues.target(student, scheduleId);
            return new Target(d, null, d.balance());
        }
        FeeDues.Account a = feeDues.account(student);
        if (a.fees().isEmpty() && a.openBills().isEmpty() && a.openArrears().isEmpty())
            throw new IllegalArgumentException("There are no fees, arrears or bills for this student yet.");
        return new Target(null, null, a.toPay());
    }

    private FeePayment newPayment(User student, FeeSchedule schedule, com.exam.model.fees.StudentBill bill, BigDecimal amount) {
        FeePayment p = new FeePayment();
        p.setStudent(student);
        p.setSchedule(schedule);
        p.setBill(bill);
        p.setSession(schedule != null ? schedule.getSession() : academicSessionService.current());
        p.setProgramName(schedule != null ? schedule.getProgram().getName() : student.getProgram() != null ? student.getProgram().getName() : null);
        p.setLevel(schedule != null ? schedule.getLevel() : student.getCurrentLevel());
        p.setAmount(amount);
        p.setCurrency(scheduleService.currency());
        return p;
    }

    /** The money is in: lock the student's account, use any old credit, split this payment, give the receipt. */
    private void succeed(FeePayment p) {
        ledger.lock(p.getStudent());
        ledger.applyCredit(p.getStudent());
        ledger.allocate(p);
        receipts.issue(p);
    }

    /** Each item that applies to this student, with its amount, paid and balance. */
    private List<FeeBreakdown.Line> breakdown(FeeDues.Due due, Long studentId) {
        Map<String, BigDecimal> forItems = new HashMap<>();
        for (Object[] row : paymentRepository.paidItems(studentId, due.schedule().getId())) {
            forItems.merge((String) row[0], (BigDecimal) row[1], BigDecimal::add);
        }
        return FeeBreakdown.compute(due.components(), forItems, due.paid());
    }

    private List<Map<String, Object>> itemLines(FeeDues.Due due, Long studentId) {
        return due.schedule().isItemised() ? breakdown(due, studentId).stream().map(FeeBreakdown.Line::toMap).toList() : List.of();
    }

    /** The fee as this student sees it: their own amount and items (other residencies' items left out). */
    private Map<String, Object> dueFee(FeeDues.Due d) {
        Map<String, Object> fee = scheduleService.toDto(d.schedule());
        fee.put("fullAmount", d.schedule().getAmount());
        fee.put("amount", d.amount());
        fee.put("components", d.components().stream().map(c -> {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("name", c.getName());
            cm.put("amount", c.getAmount());
            cm.put("appliesTo", c.appliesTo().name());
            return cm;
        }).toList());
        return fee;
    }

    private Map<String, Object> dueDto(FeeDues.Due d, Long studentId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fee", dueFee(d));
        m.put("gross", d.gross());
        m.put("discount", d.discount());
        m.put("concessions", d.concessions());
        m.put("paid", d.paid());
        m.put("balance", d.balance());
        m.put("credit", d.credit());
        m.put("status", d.status());
        m.put("items", itemLines(d, studentId));
        return m;
    }

    /** The chosen items, each with its whole remaining balance. */
    private List<Allocation> allocate(FeeDues.Due due, Long studentId, List<String> names, boolean byStudent) {
        if (!due.schedule().isItemised())
            throw new IllegalArgumentException("This fee has no breakdown. Pay towards the balance instead.");
        List<FeeBreakdown.Line> lines = breakdown(due, studentId);
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

    /** As the payment lists show it. Needs an open transaction (student and session are lazy). */
    public Map<String, Object> dto(FeePayment p, boolean forStaff) {
        return toDto(p, forStaff);
    }

    /** Needs an open transaction (student and session are lazy). */
    private Map<String, Object> toDto(FeePayment p, boolean forStaff) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("reference", p.getReference());
        m.put("walletDeposit", p.isWalletDeposit());
        m.put("amount", p.getAmount());
        m.put("currency", p.getCurrency());
        m.put("status", p.getStatus().name());
        m.put("method", p.getMethod().name());
        m.put("channel", p.getChannel());
        m.put("message", p.getGatewayMessage());
        m.put("createdAt", p.getCreatedAt());
        m.put("paidAt", p.getPaidAt());
        m.put("sessionName", p.getSession().getName());
        m.put("periodName", p.getSchedule() != null ? FeeDues.periodName(p.getSchedule()) : null);
        m.put("billTitle", p.getBill() != null ? p.getBill().getTitle() : null);
        m.put("allocations", FeeLedger.describe(p));
        m.put("receiptNo", p.getReceiptNo());
        m.put("verificationCode", p.getVerificationCode());
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
            m.put("recordedById", p.getRecordedById());
            m.put("cashedUp", p.getCashUp() != null);
            m.put("voidedAt", p.getVoidedAt());
            m.put("note", p.getNote());
            m.put("gatewayTransactionId", p.getGatewayTransactionId());
        }
        return m;
    }

    private static Map<String, Object> metadata(User student, FeePayment p, String cancelUrl) {
        FeeSchedule schedule = p.getSchedule();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("payment_id", p.getId());
        meta.put("student_id", student.getId());
        if (schedule != null) meta.put("schedule_id", schedule.getId());
        if (p.getBill() != null) meta.put("bill_id", p.getBill().getId());
        meta.put("cancel_action", cancelUrl);   // where Paystack's "Cancel payment" sends the student
        meta.put("custom_fields", List.of(
                field("Student", CurrentUserService.displayName(student)),
                field("Student ID", student.getUsername()),
                field("Programme", p.getProgramName()),
                field("Level", String.valueOf(p.getLevel())),
                field("Session", p.getSession().getName() + (schedule != null && schedule.getTerm() != null ? " · " + FeeDues.periodName(schedule) : "")),
                field("Paying for", p.isWalletDeposit() ? "Wallet deposit (paid in advance)"
                        : !p.getItems().isEmpty() ? String.join(", ", p.getItems().stream().map(FeePaymentItem::getName).toList())
                        : p.getBill() != null ? p.getBill().getTitle() : schedule != null ? "Fees" : "Fees and bills")));
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
