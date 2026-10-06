package com.exam.service.fees;

import com.exam.model.fees.FeeComponent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FeeBreakdownTest {

    private static FeeComponent item(String name, String amount) {
        FeeComponent c = new FeeComponent();
        c.setName(name);
        c.setAmount(new BigDecimal(amount));
        return c;
    }

    private static final List<FeeComponent> FEE = List.of(item("Tuition", "100"), item("SRC dues", "50"), item("Library", "30"));

    private static List<String> paid(List<FeeBreakdown.Line> lines) {
        return lines.stream().map(l -> l.paid().stripTrailingZeros().toPlainString()).toList();
    }

    @Test
    void paymentForAnItemGoesToItAndTheRestFillsItemsInOrder() {
        // 50 paid for SRC dues, 20 more towards the fee as a whole
        List<FeeBreakdown.Line> lines = FeeBreakdown.compute(FEE, Map.of("src DUES ", new BigDecimal("50")), new BigDecimal("70"));
        assertThat(paid(lines)).containsExactly("20", "50", "0");
        assertThat(lines.get(0).balance()).isEqualByComparingTo("80");
    }

    @Test
    void moneyForARemovedItemOrOverAnItemsAmountCountsTowardsTheFee() {
        assertThat(paid(FeeBreakdown.compute(FEE, Map.of("Old levy", new BigDecimal("40")), new BigDecimal("40"))))
                .containsExactly("40", "0", "0");
        // Library was cut from 50 to 30 after it was paid
        assertThat(paid(FeeBreakdown.compute(FEE, Map.of("Library", new BigDecimal("50")), new BigDecimal("50"))))
                .containsExactly("20", "0", "30");
    }

    @Test
    void itemBalancesAlwaysAddUpToTheFeeBalance() {
        List<FeeBreakdown.Line> lines = FeeBreakdown.compute(FEE, Map.of("Library", new BigDecimal("30")), new BigDecimal("200"));
        assertThat(paid(lines)).containsExactly("100", "50", "30");
        assertThat(lines.stream().map(FeeBreakdown.Line::balance).reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("0");
    }
}
