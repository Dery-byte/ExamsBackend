package com.exam.service.reports;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.*;

/**
 * A report as data: what it covers, headline figures, and tables. The report page, its CSV / Excel
 * downloads (built in the browser) and its PDF (built on the server) are all drawn from this.
 */
@Getter
public class ReportResult {

    /** Column types; they decide alignment, formatting and sorting. NUMBER = a mark: up to 2 decimals, no trailing zeros. */
    public static final String TEXT = "text", INT = "int", NUMBER = "number", DECIMAL = "decimal", PERCENT = "percent",
            MONEY = "money", DATE = "date", DATETIME = "datetime";

    private final String key;
    private final String title;
    private final String description;
    /** What the report covers, e.g. "Session: 2025/2026", "Department: All departments". */
    private final List<String> scope = new ArrayList<>();
    private final List<Stat> summary = new ArrayList<>();
    private final List<Table> tables = new ArrayList<>();
    /** How figures are worked out, and anything the data cannot show. */
    private final List<String> notes = new ArrayList<>();
    private final LocalDateTime generatedAt = LocalDateTime.now();
    @Setter private String generatedBy;

    public ReportResult(String key, String title, String description) {
        this.key = key;
        this.title = title;
        this.description = description;
    }

    /** A headline figure. tone: "good", "warn", "bad" or null. */
    public record Stat(String label, Object value, String hint, String tone) {}

    public record Column(String key, String label, String type) {}

    /** Clicking a row reloads the report with filter {@code param} set to the row's {@code key} value. */
    public record Drill(String param, String key, String hint) {}

    /** A bar chart of one numeric column against one label column. */
    public record Chart(String label, String value) {}

    public ReportResult scope(String line) { scope.add(line); return this; }

    public ReportResult stat(String label, Object value) { return stat(label, value, null, null); }

    public ReportResult stat(String label, Object value, String hint, String tone) {
        summary.add(new Stat(label, value, hint, tone));
        return this;
    }

    public ReportResult note(String text) { notes.add(text); return this; }

    public Table table(String id, String title) {
        Table t = new Table(id, title);
        tables.add(t);
        return t;
    }

    /** Whether the PDF should be printed landscape. */
    public boolean isWide() {
        return tables.stream().anyMatch(t -> t.getColumns().size() > 7);
    }

    @Getter
    public static class Table {
        private final String id;
        private final String title;
        private String subtitle;
        private final List<Column> columns = new ArrayList<>();
        private final List<Map<String, Object>> rows = new ArrayList<>();
        private Drill drill;
        private Chart chart;
        private String emptyText = "Nothing to show for these filters.";

        Table(String id, String title) {
            this.id = id;
            this.title = title;
        }

        public Table subtitle(String s) { this.subtitle = s; return this; }
        public Table emptyText(String s) { this.emptyText = s; return this; }
        public Table drill(String param, String key, String hint) { this.drill = new Drill(param, key, hint); return this; }
        public Table chart(String labelColumn, String valueColumn) { this.chart = new Chart(labelColumn, valueColumn); return this; }

        public Table col(String key, String label, String type) {
            columns.add(new Column(key, label, type));
            return this;
        }

        public Table text(String key, String label) { return col(key, label, TEXT); }

        /** Adds a row; values follow the column order. Extra values (beyond the columns) are ignored. */
        public Map<String, Object> add(Object... values) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 0; i < columns.size(); i++) row.put(columns.get(i).key(), i < values.length ? values[i] : null);
            rows.add(row);
            return row;
        }

        /** Adds a row given as a map (keys not among the columns are kept, e.g. ids for drilling down). */
        public Map<String, Object> add(Map<String, Object> row) {
            rows.add(row);
            return row;
        }

        public boolean isEmpty() { return rows.isEmpty(); }
    }
}
