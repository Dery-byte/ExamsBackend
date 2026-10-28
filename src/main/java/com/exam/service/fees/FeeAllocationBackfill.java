package com.exam.service.fees;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * Payments made before bills existed went to exactly one fee and had no allocation rows: give each
 * one an allocation of its whole amount to that fee, so the totals read the same. Runs at start-up
 * and only touches payments with no allocations yet (new payments always get theirs when they succeed).
 */
@Component
@Order(1)
public class FeeAllocationBackfill implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(FeeAllocationBackfill.class);

    private final JdbcTemplate jdbc;

    public FeeAllocationBackfill(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            int n = jdbc.update("INSERT INTO fee_payment_allocation (payment_id, schedule_id, amount) "
                    + "SELECT p.id, p.schedule_id, p.amount FROM fee_payment p WHERE p.status IN ('SUCCESS', 'VOIDED') "
                    + "AND p.schedule_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM fee_payment_allocation a WHERE a.payment_id = p.id)");
            if (n > 0) log.info("[Fees] Gave {} earlier payments their allocation to the fee they paid", n);
        } catch (Exception e) {
            log.error("[Fees] Could not backfill payment allocations: {}", e.getMessage());
        }
    }
}
