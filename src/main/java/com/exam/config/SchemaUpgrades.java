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
