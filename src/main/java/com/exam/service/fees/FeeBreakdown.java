package com.exam.service.fees;

import com.exam.model.fees.FeeComponent;

import java.math.BigDecimal;
import java.util.*;

/**
 * What a student has paid and still owes on each item of an itemised fee.
 * <p>
 * Money paid for a named item goes to that item first (up to its amount). Everything else — payments
 * towards the fee as a whole, anything paid over an item's amount, and payments for items that were
 * later renamed or removed — is applied to the items in the order they are listed. So the items'
 * balances always add up to the fee's balance.
 */
public final class FeeBreakdown {

    private FeeBreakdown() {}

    public record Line(String name, BigDecimal amount, BigDecimal paid, BigDecimal balance) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("amount", amount);
            m.put("paid", paid);
            m.put("balance", balance);
            return m;
        }
    }

    /**
     * @param components   the fee's items, in display order
     * @param paidForItems what was paid for specific items, by item name (any case)
     * @param totalPaid    everything paid towards the fee
     */
    public static List<Line> compute(List<FeeComponent> components, Map<String, BigDecimal> paidForItems, BigDecimal totalPaid) {
        Map<String, BigDecimal> forItem = new HashMap<>();
        paidForItems.forEach((name, amount) -> forItem.merge(key(name), amount, BigDecimal::add));

        List<BigDecimal> direct = new ArrayList<>();
        BigDecimal directTotal = BigDecimal.ZERO;
        for (FeeComponent c : components) {
            BigDecimal d = forItem.getOrDefault(key(c.getName()), BigDecimal.ZERO).min(c.getAmount()).max(BigDecimal.ZERO);
            direct.add(d);
            directTotal = directTotal.add(d);
        }

        BigDecimal pool = totalPaid.subtract(directTotal).max(BigDecimal.ZERO);
        List<Line> out = new ArrayList<>();
        for (int i = 0; i < components.size(); i++) {
            FeeComponent c = components.get(i);
            BigDecimal paid = direct.get(i);
            BigDecimal take = c.getAmount().subtract(paid).min(pool);
            pool = pool.subtract(take);
            paid = paid.add(take);
            out.add(new Line(c.getName(), c.getAmount(), paid, c.getAmount().subtract(paid)));
        }
        return out;
    }

    static String key(String name) {
        return name == null ? "" : name.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
