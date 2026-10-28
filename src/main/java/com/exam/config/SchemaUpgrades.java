package com.exam.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;

/**
 * Column changes that ddl-auto=update can't make (it adds columns but never widens them), applied once
 * at start-up on MySQL and skipped when already done. Runs before the scheduled jobs' first run.
 */
@Component
@Order(0)
public class SchemaUpgrades implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaUpgrades.class);

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;

    public SchemaUpgrades(DataSource dataSource) {
        this.dataSource = dataSource;
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public void run(ApplicationArguments args) {
        try (Connection c = dataSource.getConnection()) {
            if (!c.getMetaData().getDatabaseProductName().toLowerCase().contains("mysql")) return;
        } catch (Exception e) {
            log.warn("[SCHEMA] Could not check the database type: {}", e.getMessage());
            return;
        }
        widenToText("system_settings", "setting_value");
        replaceUniqueKey("fee_schedule", "uk_fee_schedule_program_level_session",
                "uk_fee_schedule_class_period", "program_id, level, session_id, period_key");
        // A payment may now be for a bill, or for everything owed, instead of one fee
        makeNullable("fee_payment", "schedule_id", "BIGINT");
    }

    /** NOT NULL → NULL, which ddl-auto=update never does once the column exists. */
    private void makeNullable(String table, String column, String type) {
        try {
            List<String> nullable = jdbc.queryForList(
                    "SELECT IS_NULLABLE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                    String.class, table, column);
            if (nullable.isEmpty() || "YES".equalsIgnoreCase(nullable.get(0))) return;
            jdbc.execute("ALTER TABLE " + table + " MODIFY " + column + " " + type + " NULL");
            log.info("[SCHEMA] Made {}.{} nullable", table, column);
        } catch (Exception e) {
            log.error("[SCHEMA] Could not make {}.{} nullable: {}", table, column, e.getMessage());
        }
    }

    /**
     * Swaps a unique key that ddl-auto=update can't drop, e.g. fees per term need (programme, level,
     * session) to become (programme, level, session, period). The new key is added first: MySQL may be
     * using the old one for a foreign key, and then refuses to drop it until another index covers it.
     */
    private void replaceUniqueKey(String table, String oldKey, String newKey, String columns) {
        try {
            if (!indexExists(table, oldKey)) return;
            if (!indexExists(table, newKey)) jdbc.execute("ALTER TABLE " + table + " ADD CONSTRAINT " + newKey + " UNIQUE (" + columns + ")");
            jdbc.execute("ALTER TABLE " + table + " DROP INDEX " + oldKey);
            log.info("[SCHEMA] Replaced unique key {}.{} with {}", table, oldKey, newKey);
        } catch (Exception e) {
            log.error("[SCHEMA] Could not replace unique key {}.{}: {}", table, oldKey, e.getMessage());
        }
    }

    private boolean indexExists(String table, String index) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() "
                + "AND TABLE_NAME = ? AND INDEX_NAME = ?", Integer.class, table, index);
        return n != null && n > 0;
    }

    /** VARCHAR(255) → TEXT, e.g. so a subscription key (~450 characters) fits in a system setting. */
    private void widenToText(String table, String column) {
        try {
            List<String> type = jdbc.queryForList(
                    "SELECT DATA_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                    String.class, table, column);
            if (type.isEmpty() || !"varchar".equalsIgnoreCase(type.get(0))) return;
            jdbc.execute("ALTER TABLE " + table + " MODIFY " + column + " TEXT NULL");
            log.info("[SCHEMA] Widened {}.{} to TEXT", table, column);
        } catch (Exception e) {
            log.error("[SCHEMA] Could not widen {}.{} to TEXT: {}", table, column, e.getMessage());
        }
    }
}
