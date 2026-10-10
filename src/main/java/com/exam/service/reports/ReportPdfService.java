package com.exam.service.reports;

import com.exam.service.academic.InstitutionService;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.xhtmlrenderer.pdf.ITextRenderer;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static com.exam.service.reports.ReportResult.*;

/** Prints any {@link ReportResult} on the institution's letterhead (template report-table.html). */
@Service
public class ReportPdfService {

    /** Rows printed per table; the spreadsheet download always has every row. */
    static final int MAX_ROWS = 3000;

    @Autowired private TemplateEngine templateEngine;
    @Autowired private InstitutionService institutionService;

    public record Cell(String text, boolean right) {}

    public record Head(String label, boolean right) {}

    public record PrintTable(String title, String subtitle, List<Head> heads, List<List<Cell>> rows, String emptyText, String truncated) {}

    public record PrintStat(String label, String value, String hint) {}

    public byte[] render(ReportResult r) throws Exception {
        Context ctx = new Context();
        ctx.setVariable("r", r);
        ctx.setVariable("logo", institutionService.logoDataUrl());
        ctx.setVariable("institutionName", institutionService.name());
        ctx.setVariable("institutionSubtitle", institutionService.subtitle());
        ctx.setVariable("footerName", (institutionService.name() + "  |  " + r.getTitle()).replaceAll("[\"\\\\]", ""));
        ctx.setVariable("generated", r.getGeneratedAt().format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")));
        int maxCols = r.getTables().stream().mapToInt(t -> t.getColumns().size()).max().orElse(0);
        ctx.setVariable("pageSize", maxCols > 20 ? "A3 landscape" : r.isWide() ? "A4 landscape" : "A4 portrait");
        ctx.setVariable("cellFont", maxCols > 13 ? "6.5pt" : maxCols > 9 ? "7.5pt" : "8.5pt");

        List<PrintStat> stats = new ArrayList<>();
        for (Stat s : r.getSummary()) stats.add(new PrintStat(s.label(), s.value() == null ? "-" : format(s.value(), null), s.hint()));
        ctx.setVariable("stats", stats);

        List<PrintTable> tables = new ArrayList<>();
        for (Table t : r.getTables()) {
            List<Head> heads = t.getColumns().stream().map(c -> new Head(c.label(), rightAligned(c.type()))).toList();
            List<List<Cell>> rows = new ArrayList<>();
            for (Map<String, Object> row : t.getRows().subList(0, Math.min(MAX_ROWS, t.getRows().size()))) {
                List<Cell> cells = new ArrayList<>();
                for (Column c : t.getColumns()) cells.add(new Cell(format(row.get(c.key()), c.type()), rightAligned(c.type())));
                rows.add(cells);
            }
            String truncated = t.getRows().size() > MAX_ROWS
                    ? "Showing the first " + MAX_ROWS + " of " + t.getRows().size() + " rows. Download Excel or CSV for all of them." : null;
            tables.add(new PrintTable(t.getTitle(), t.getSubtitle(), heads, rows, t.getEmptyText(), truncated));
        }
        ctx.setVariable("tables", tables);

        String html = templateEngine.process("report-table", ctx);
        Document doc = Jsoup.parse(html);
        doc.outputSettings().syntax(Document.OutputSettings.Syntax.xml);
        doc.outputSettings().escapeMode(org.jsoup.nodes.Entities.EscapeMode.xhtml);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ITextRenderer renderer = new ITextRenderer();
        renderer.setDocumentFromString(doc.html());
        renderer.layout();
        renderer.createPDF(out);
        return out.toByteArray();
    }

    private static boolean rightAligned(String type) {
        return INT.equals(type) || NUMBER.equals(type) || DECIMAL.equals(type) || PERCENT.equals(type) || MONEY.equals(type);
    }

    /** Same formatting as the report page: thousands separators, 2-decimal money and decimals, 1-decimal percentages. */
    static String format(Object v, String type) {
        if (v == null) return "";
        if (v instanceof Number n && type != null) {
            BigDecimal d = new BigDecimal(n.toString());
            return switch (type) {
                case INT -> String.format("%,d", d.setScale(0, RoundingMode.HALF_UP).longValue());
                case PERCENT -> d.setScale(1, RoundingMode.HALF_UP).toPlainString() + "%";
                case MONEY, DECIMAL -> String.format("%,.2f", d.setScale(2, RoundingMode.HALF_UP));
                case NUMBER -> d.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
                default -> d.toPlainString();
            };
        }
        if (v instanceof Number n) {
            BigDecimal d = new BigDecimal(n.toString());
            return d.stripTrailingZeros().scale() <= 0 ? String.format("%,d", d.longValue()) : d.toPlainString();
        }
        return v.toString();
    }
}
