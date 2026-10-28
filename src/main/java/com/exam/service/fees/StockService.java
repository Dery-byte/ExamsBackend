package com.exam.service.fees;

import com.exam.model.User;
import com.exam.model.fees.*;
import com.exam.repository.*;
import com.exam.service.SystemSettingService;
import com.exam.service.comms.CurrentUserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * The stores: what the school holds of price-list goods (uniform, books). The rules:
 * <ul>
 *   <li>billing is not handing over: stock leaves only when an item is handed to the student, against
 *       the line of their bill, and never more than billed or than the store holds (never below zero);</li>
 *   <li>items are handed over once the bill is paid in full, unless the bursar allows handing over first;</li>
 *   <li>the bursar receives deliveries (quantity, cost, supplier) and corrects the count (a reason is
 *       required); anyone in the accounts office hands items over or takes them back;</li>
 *   <li>a bill with items still out can't be cancelled until they are returned.</li>
 * </ul>
 * Every change locks the item's row and is recorded as a movement with the count after it.
 */
@Service
public class StockService {

    private static final int MAX_QTY = 100_000;

    @Autowired private PriceItemRepository items;
    @Autowired private StockMovementRepository movements;
    @Autowired private BillLineRepository lines;
    @Autowired private FeeAllocationRepository allocations;
    @Autowired private SystemSettingService settings;
    @Autowired private FeeScheduleService scheduleService;

    public record StockedRequest(Boolean stocked, Integer reorderLevel) {}
    public record ReceiveRequest(Integer quantity, BigDecimal unitCost, String reference, String note) {}
    public record AdjustRequest(Integer countedQuantity, String reason) {}
    public record HandOverRequest(Integer quantity, String note) {}

    // ── The store ────────────────────────────────────────────────────────────

    /** Stocked items with what the store holds, what is billed but not handed over yet, and their value. */
    @Transactional(readOnly = true)
    public Map<String, Object> stores() {
        Map<Long, Long> owed = new HashMap<>();
        for (Object[] r : lines.owedByItem(StudentBill.Status.OPEN)) owed.put((Long) r[0], ((Number) r[1]).longValue());
        BigDecimal value = BigDecimal.ZERO;
        int low = 0;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (PriceItem i : items.findByStockedTrueOrderByNameAsc()) {
            Map<String, Object> m = itemDto(i);
            m.put("toHandOver", owed.getOrDefault(i.getId(), 0L));
            rows.add(m);
            if (Boolean.TRUE.equals(m.get("low"))) low++;
            if (i.getUnitCost() != null) value = value.add(i.getUnitCost().multiply(BigDecimal.valueOf(i.getStockOnHand())));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("currency", scheduleService.currency());
        out.put("items", rows);
        out.put("low", low);
        out.put("value", value);
        out.put("issueBeforePayment", issueBeforePayment());
        return out;
    }

    /** Bursar: start or stop keeping stock of an item (stopping only when the store holds none). */
    @Transactional
    public Map<String, Object> setStocked(User actor, Long itemId, StockedRequest req) {
        FinanceAccess.requireBursar(actor);
        PriceItem i = lock(itemId);
        boolean on = req != null && Boolean.TRUE.equals(req.stocked());
        if (!on && i.isStocked() && i.getStockOnHand() > 0)
            throw new IllegalArgumentException("The store still holds " + i.getStockOnHand() + ". Correct the count to 0 first.");
        Integer reorder = req == null ? null : req.reorderLevel();
        if (reorder != null && (reorder < 0 || reorder > MAX_QTY)) throw new IllegalArgumentException("Check the reorder level.");
        i.setStocked(on);
        i.setReorderLevel(on ? reorder : null);
        return itemDto(items.save(i));
    }

    @Transactional
    public Map<String, Object> receive(User actor, Long itemId, ReceiveRequest req) {
        FinanceAccess.requireBursar(actor);
        PriceItem i = stocked(itemId);
        int qty = quantity(req == null ? null : req.quantity());
        BigDecimal cost = req.unitCost() == null ? null : FeeScheduleService.money(req.unitCost(), "The cost");
        i.setStockOnHand(i.getStockOnHand() + qty);
        if (cost != null) i.setUnitCost(cost);
        record(i, StockMovement.Kind.RECEIVED, qty, actor, null, cost,
                ConcessionService.clean(req.reference(), 160), ConcessionService.clean(req.note(), 300));
        return itemDto(items.save(i));
    }

    /** Bursar: the count after a stock-take (damage, loss, found); the difference is recorded with the reason. */
    @Transactional
    public Map<String, Object> adjust(User actor, Long itemId, AdjustRequest req) {
        FinanceAccess.requireBursar(actor);
        PriceItem i = stocked(itemId);
        Integer counted = req == null ? null : req.countedQuantity();
        if (counted == null || counted < 0 || counted > MAX_QTY) throw new IllegalArgumentException("Enter the number counted (0 or more).");
        String reason = ConcessionService.clean(req.reason(), 300);
        if (reason == null) throw new IllegalArgumentException("Give the reason for the correction.");
        int diff = counted - i.getStockOnHand();
        if (diff == 0) throw new IllegalArgumentException("That is what the store already holds.");
        i.setStockOnHand(counted);
        record(i, StockMovement.Kind.ADJUSTED, diff, actor, null, null, null, reason);
        return itemDto(items.save(i));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> history(Long itemId) {
        return movements.findForItem(itemId, PageRequest.of(0, 300)).stream().map(StockService::movementDto).toList();
    }

    // ── Handing over ─────────────────────────────────────────────────────────

    /** Stocked items billed and not all handed over: everyone's, or one student's. */
    @Transactional(readOnly = true)
    public Map<String, Object> awaiting(Long studentId) {
        List<BillLine> list = lines.awaitingHandOver(studentId, StudentBill.Status.OPEN);
        Map<Long, BigDecimal> paid = FeeDues.sums(allocations.paidByBill(list.stream().map(l -> l.getBill().getId()).distinct().toList()));
        boolean early = issueBeforePayment();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("currency", scheduleService.currency());
        out.put("issueBeforePayment", early);
        out.put("lines", list.stream().map(l -> {
            StudentBill b = l.getBill();
            BigDecimal balance = b.getAmount().subtract(paid.getOrDefault(b.getId(), BigDecimal.ZERO)).max(BigDecimal.ZERO);
            Map<String, Object> m = lineDto(l);
            m.put("billBalance", balance);
            m.put("canHandOver", early || balance.signum() <= 0);
            return m;
        }).toList());
        return out;
    }

    @Transactional
    public Map<String, Object> handOver(User actor, Long lineId, HandOverRequest req) {
        FinanceAccess.requireFinance(actor);
        BillLine l = line(lineId);
        StudentBill b = l.getBill();
        if (!b.isOpen()) throw new IllegalArgumentException("This bill is " + b.getStatus().name().toLowerCase(Locale.ROOT) + ".");
        PriceItem i = stocked(l.getItem().getId());              // locks the item
        int qty = quantity(req == null ? null : req.quantity());
        int left = l.getQuantity() - l.getIssuedQty();
        if (qty > left) throw new IllegalArgumentException(left == 0 ? "Everything on this line has been handed over." : "Only " + left + " left to hand over on this bill.");
        if (qty > i.getStockOnHand())
            throw new IllegalArgumentException("The store holds only " + i.getStockOnHand() + " " + i.getName() + ". Receive more stock first.");
        if (!issueBeforePayment()) {
            BigDecimal paid = FeeDues.sums(allocations.paidByBill(List.of(b.getId()))).getOrDefault(b.getId(), BigDecimal.ZERO);
            BigDecimal balance = b.getAmount().subtract(paid);
            if (balance.signum() > 0)
                throw new IllegalArgumentException("The bill isn't paid yet (" + scheduleService.currency() + " " + balance.setScale(2, RoundingMode.HALF_UP).toPlainString()
                        + " to pay). Items are handed over once it is paid.");
        }
        i.setStockOnHand(i.getStockOnHand() - qty);
        l.setIssuedQty(l.getIssuedQty() + qty);
        record(i, StockMovement.Kind.ISSUED, -qty, actor, l, null, null, ConcessionService.clean(req.note(), 300));
        items.save(i);
        return lineDto(lines.save(l));
    }

    /** Items brought back (wrong size, a cancelled order): back into the store. */
    @Transactional
    public Map<String, Object> takeBack(User actor, Long lineId, HandOverRequest req) {
        FinanceAccess.requireFinance(actor);
        BillLine l = line(lineId);
        PriceItem i = stocked(l.getItem().getId());
        int qty = quantity(req == null ? null : req.quantity());
        if (qty > l.getIssuedQty()) throw new IllegalArgumentException("Only " + l.getIssuedQty() + " were handed over on this bill.");
        String note = ConcessionService.clean(req.note(), 300);
        if (note == null) throw new IllegalArgumentException("Say why they are being returned.");
        i.setStockOnHand(i.getStockOnHand() + qty);
        l.setIssuedQty(l.getIssuedQty() - qty);
        record(i, StockMovement.Kind.RETURNED, qty, actor, l, null, null, note);
        items.save(i);
        return lineDto(lines.save(l));
    }

    /** A bill can't be cancelled or declined while items from it are out. */
    public static void requireNothingOut(StudentBill b) {
        int out = b.getLines().stream().mapToInt(BillLine::getIssuedQty).sum();
        if (out > 0) throw new IllegalArgumentException(out + " item(s) on this bill were handed over. Take them back first (Stores › To hand over).");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private boolean issueBeforePayment() {
        return settings.getBooleanSetting(SystemSettingService.FEES_STOCK_ISSUE_UNPAID, false);
    }

    private PriceItem lock(Long id) {
        return items.lockById(id).orElseThrow(() -> new IllegalArgumentException("Item not found."));
    }

    private PriceItem stocked(Long id) {
        PriceItem i = lock(id);
        if (!i.isStocked()) throw new IllegalArgumentException(i.getName() + " is not kept in the store. Turn on stock for it first.");
        return i;
    }

    private BillLine line(Long id) {
        BillLine l = lines.findById(id).orElseThrow(() -> new IllegalArgumentException("Bill line not found."));
        if (l.getItem() == null) throw new IllegalArgumentException("This line is not a store item.");
        return l;
    }

    private static int quantity(Integer q) {
        if (q == null || q < 1 || q > MAX_QTY) throw new IllegalArgumentException("Enter how many (1 or more).");
        return q;
    }

    private void record(PriceItem i, StockMovement.Kind kind, int qty, User actor, BillLine line, BigDecimal cost, String reference, String note) {
        StockMovement m = new StockMovement();
        m.setItem(i);
        m.setKind(kind);
        m.setQuantity(qty);
        m.setBalanceAfter(i.getStockOnHand());
        m.setUnitCost(cost);
        m.setReference(reference);
        m.setNote(note);
        m.setBillLine(line);
        m.setStudent(line == null ? null : line.getBill().getStudent());
        m.setByName(CurrentUserService.displayName(actor));
        m.setById(actor.getId());
        movements.save(m);
    }

    static Map<String, Object> itemDto(PriceItem i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", i.getId());
        m.put("name", i.getName());
        m.put("category", i.getCategory().name());
        m.put("price", i.getPrice());
        m.put("active", i.isActive());
        m.put("stocked", i.isStocked());
        m.put("onHand", i.getStockOnHand());
        m.put("reorderLevel", i.getReorderLevel());
        m.put("unitCost", i.getUnitCost());
        m.put("low", i.isStocked() && i.getReorderLevel() != null && i.getStockOnHand() <= i.getReorderLevel());
        return m;
    }

    private static Map<String, Object> lineDto(BillLine l) {
        StudentBill b = l.getBill();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("lineId", l.getId());
        m.put("itemId", l.getItem() != null ? l.getItem().getId() : null);
        m.put("name", l.getName());
        m.put("quantity", l.getQuantity());
        m.put("issued", l.getIssuedQty());
        m.put("billId", b.getId());
        m.put("billTitle", b.getTitle());
        m.put("billedAt", b.getCreatedAt());
        m.put("studentId", b.getStudent().getId());
        m.put("studentName", CurrentUserService.displayName(b.getStudent()));
        m.put("username", b.getStudent().getUsername());
        return m;
    }

    private static Map<String, Object> movementDto(StockMovement x) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", x.getId());
        m.put("kind", x.getKind().name());
        m.put("quantity", x.getQuantity());
        m.put("balanceAfter", x.getBalanceAfter());
        m.put("unitCost", x.getUnitCost());
        m.put("reference", x.getReference());
        m.put("note", x.getNote());
        m.put("studentName", x.getStudent() != null ? CurrentUserService.displayName(x.getStudent()) : null);
        m.put("by", x.getByName());
        m.put("at", x.getCreatedAt());
        return m;
    }
}
