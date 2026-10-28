package com.exam.service.fees;

import com.exam.model.academic.DocumentVerification;
import com.exam.model.fees.FeePayment;
import com.exam.model.fees.ReceiptSequence;
import com.exam.repository.ReceiptSequenceRepository;
import com.exam.service.academic.DocumentVerificationService;
import com.exam.service.comms.CurrentUserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Locale;

/**
 * Receipt numbers (RC-2026-000123, counted per calendar year, never reused) and the verification code
 * printed on each receipt. A number is given when the money is received: a cash / bank entry, or
 * Paystack confirming an online payment. Voiding the payment revokes the code; the number stays used.
 */
@Service
public class ReceiptService {

    @Autowired private ReceiptSequenceRepository sequences;
    @Autowired private com.exam.repository.FinanceSequenceRepository series;
    @Autowired private DocumentVerificationService verification;
    @Autowired private PlatformTransactionManager transactionManager;

    /** Gives the payment its receipt number and code, once. Call inside the transaction that marks it SUCCESS. */
    public void issue(FeePayment p) {
        if (p.getReceiptNo() != null) return;
        int year = (p.getPaidAt() != null ? p.getPaidAt() : LocalDateTime.now()).getYear();
        p.setReceiptNo(String.format(Locale.ROOT, "RC-%d-%06d", year, next(year)));
        String summary = "Receipt " + p.getReceiptNo() + " · " + p.getCurrency() + " " + String.format(Locale.ROOT, "%,.2f", p.getAmount())
                + " · " + FeeLedger.summary(p) + " · " + p.getSession().getName()
                + " · " + methodName(p.getMethod());
        verification.issue(DocumentVerification.Type.RECEIPT, p.getStudent().getId(), CurrentUserService.displayName(p.getStudent()),
                        p.getStudent().getUsername(), p.getProgramName(), summary)
                .ifPresent(i -> p.setVerificationCode(i.code()));
    }

    public void revoke(FeePayment p, String reason) {
        verification.revokeCode(p.getVerificationCode(), "Payment cancelled: " + reason);
    }

    /**
     * The next number for the year, under a row lock. The year's row is created (on its own) before
     * locking: on MySQL, locking a row that isn't there gap-locks it, and the insert would then wait on
     * our own lock until it timed out, rolling back the payment.
     */
    long next(int year) {
        if (!sequences.existsById(year)) createYear(year);
        ReceiptSequence s = sequences.lockYear(year).orElseThrow();
        s.setLastNumber(s.getLastNumber() + 1);
        sequences.save(s);
        return s.getLastNumber();
    }

    /** In its own transaction, so two first-receipts-of-the-year racing don't break the caller's transaction. */
    private void createYear(int year) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        try {
            tx.executeWithoutResult(st -> {
                if (sequences.existsById(year)) return;
                ReceiptSequence s = new ReceiptSequence();
                s.setYear(year);
                s.setLastNumber(0);
                sequences.saveAndFlush(s);
            });
        } catch (DataIntegrityViolationException e) {
            // Another payment created the year a moment earlier
        }
    }

    /** The next number of a named series for this year, e.g. "RF" → RF-2026-000001 (never reused). */
    public String nextNumber(String prefix) {
        String name = prefix + "-" + LocalDateTime.now().getYear();
        // Created before locking, as in next(year)
        if (!series.existsById(name)) {
            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            try {
                tx.executeWithoutResult(st -> {
                    if (series.existsById(name)) return;
                    com.exam.model.fees.FinanceSequence f = new com.exam.model.fees.FinanceSequence();
                    f.setName(name);
                    series.saveAndFlush(f);
                });
            } catch (DataIntegrityViolationException e) {
                // created by someone else a moment earlier
            }
        }
        com.exam.model.fees.FinanceSequence s = series.lock(name).orElseThrow();
        s.setLastNumber(s.getLastNumber() + 1);
        series.save(s);
        return String.format(Locale.ROOT, "%s-%06d", name, s.getLastNumber());
    }

    public static String methodName(FeePayment.Method m) {
        if (m == null) return "Unknown";
        return switch (m) {
            case PAYSTACK -> "Online (Paystack)";
            case CASH -> "Cash";
            case BANK_TRANSFER -> "Bank transfer";
            case OTHER -> "Other";
        };
    }
}
