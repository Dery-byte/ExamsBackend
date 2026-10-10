package com.exam.service.reports;

import com.exam.service.QuestionImageService;
import com.exam.service.academic.InstitutionService;
import com.exam.service.reports.QuestionPapers.*;
import com.lowagie.text.*;
import com.lowagie.text.pdf.*;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * Draws a question paper directly with the PDF library (no HTML or CSS), laid out like a printed
 * examination paper:
 * <ul>
 *   <li>cover page: crest beside a vertical rule with the institution, department and programme;
 *       the examination, semester and course between thick rules; "Time allowed" in a box;
 *       registration-number boxes; the instructions box; date of exam and examiner at the foot;</li>
 *   <li>Section A in two columns, flowing down the left column, then the right, then onto the
 *       next page (a question is never split);</li>
 *   <li>Section B on its own pages: Q1, parts a) b), marks on the right.</li>
 * </ul>
 * All text is real text in embedded fonts ({@link PaperFonts}), so it prints sharp at any size.
 */
@Component
public class QuestionPaperPdf {

    private static final float SIDE = 45f, TOP = 48f, BOTTOM = 56f;   // ≈ 16 mm, 17 mm, 20 mm
    private static final float BODY = 10.5f, GUTTER = 18f, NUMBER_W = 20f, LETTER_W = 17f;
    private static final Color KEY_RED = new Color(0x7a, 0x18, 0x20);

    @Autowired private InstitutionService institutionService;

    public byte[] render(Paper p, String printedBy, String printedAt) throws Exception {
        PaperFonts f = PaperFonts.get();
        Document doc = new Document(PageSize.A4, SIDE, SIDE, TOP, BOTTOM);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfWriter w = PdfWriter.getInstance(doc, out);
        w.setPageEvent(new Footer(f, p.answers()));
        doc.addTitle(p.title());
        doc.addCreator(institutionService.name());
        doc.open();

        cover(doc, w, p, f, printedBy, printedAt);
        if (p.hasObjective()) sectionA(doc, w, p, f);
        if (p.hasTheory()) sectionB(doc, w, p, f);

        doc.close();
        return out.toByteArray();
    }

    // ── Cover page ───────────────────────────────────────────────────────────

    /** Gaps between cover blocks: shared out evenly from the page's spare height, within these limits. */
    private static final float MIN_GAP = 14f, MAX_GAP = 62f;

    /**
     * The cover page, spaced like a printed paper: the blocks (crest, examination, time allowed,
     * registration number, instructions, date and examiner) are measured first, then the spare
     * height of the page is shared out as equal gaps between them, so the page looks balanced
     * whether the institution has two heading lines or five.
     */
    private void cover(Document doc, PdfWriter w, Paper p, PaperFonts f, String printedBy, String printedAt) throws Exception {
        Cover c = p.cover();
        float width = doc.right() - doc.left();
        List<PdfPTable> blocks = new java.util.ArrayList<>();

        PdfPTable banner = null;
        if (p.answers()) {
            banner = locked(new PdfPTable(1), width);
            PdfPCell cell = cell(new Phrase("ANSWER KEY - CONFIDENTIAL - NOT FOR CANDIDATES", sansColored(f, 9, KEY_RED)));
            cell.setBorder(Rectangle.BOX);
            cell.setBorderColor(KEY_RED);
            cell.setBorderWidth(1.2f);
            cell.setHorizontalAlignment(Element.ALIGN_CENTER);
            cell.setPadding(4f);
            cell.setPaddingBottom(6f);
            banner.addCell(cell);
        }

        // Crest beside a thick rule, with the institution, department and programme
        Image logo = image(institutionService.logoDataUrl());
        PdfPTable crest = locked(logo != null ? new PdfPTable(new float[]{125f, 380f}) : new PdfPTable(1), width);
        if (logo != null) {
            logo.scaleToFit(100f, 112f);
            PdfPCell lc = new PdfPCell(logo, false);
            lc.setBorder(Rectangle.NO_BORDER);
            lc.setHorizontalAlignment(Element.ALIGN_CENTER);
            lc.setVerticalAlignment(Element.ALIGN_MIDDLE);
            lc.setPaddingRight(12f);
            crest.addCell(lc);
        }
        PdfPCell tc = new PdfPCell();
        tc.setBorder(logo != null ? Rectangle.LEFT : Rectangle.NO_BORDER);
        tc.setBorderWidthLeft(3f);
        tc.setPaddingLeft(14f);
        tc.setPaddingTop(2f);
        tc.setPaddingBottom(14f);
        tc.setVerticalAlignment(Element.ALIGN_MIDDLE);
        for (String line : c.heading()) tc.addElement(line(upper(line), f.serif(11.5f, Font.BOLD), Element.ALIGN_CENTER, 0f, 9f));
        if (c.programme() != null) tc.addElement(line(upper(c.programme()), f.serif(11.5f, Font.BOLD), Element.ALIGN_LEFT, 12f, 0f));
        crest.addCell(tc);
        blocks.add(crest);

        // Examination, semester and course between thick rules
        PdfPTable exam = locked(new PdfPTable(1), width);
        PdfPCell ec = new PdfPCell();
        ec.setBorder(Rectangle.TOP | Rectangle.BOTTOM);
        ec.setBorderWidthTop(3f);
        ec.setBorderWidthBottom(2f);
        ec.setPaddingTop(6f);
        ec.setPaddingBottom(7f);
        for (String s : new String[]{c.examLine(), c.semesterLine(), c.courseLine()})
            if (s != null && !s.isBlank()) ec.addElement(line(upper(s), f.serif(11f, Font.BOLD), Element.ALIGN_CENTER, 0f, 1f));
        exam.addCell(ec);
        blocks.add(exam);

        // Time allowed, right of centre as on the printed paper
        PdfPTable time = locked(new PdfPTable(new float[]{width - 278f - 98f, 88f, 190f, 98f}), width);
        time.addCell(cell(null));
        PdfPCell tl = cell(new Phrase("Time allowed", f.serif(11f, Font.BOLD)));
        tl.setHorizontalAlignment(Element.ALIGN_RIGHT);
        tl.setVerticalAlignment(Element.ALIGN_MIDDLE);
        tl.setPaddingRight(6f);
        time.addCell(tl);
        PdfPCell tb = cell(new Phrase(c.timeAllowed(), f.sans(11f, true)));
        tb.setBorder(Rectangle.BOX);
        tb.setBorderWidth(0.8f);
        tb.setPadding(5f);
        tb.setPaddingLeft(8f);
        tb.setPaddingBottom(8f);
        tb.setNoWrap(true);
        time.addCell(tb);
        time.addCell(cell(null));
        blocks.add(time);

        // Registration number boxes (not on the answer key)
        if (!p.answers()) {
            List<String> cells = c.registrationCells();
            float boxes = 0;
            for (String s : cells) boxes += s.isEmpty() ? 18f : 9f;
            float labelW = Math.max(120f, Math.min(190f, width - boxes));
            float[] widths = new float[cells.size() + 1];
            widths[0] = labelW;
            for (int i = 0; i < cells.size(); i++) widths[i + 1] = cells.get(i).isEmpty() ? 18f : 9f;
            PdfPTable reg = locked(new PdfPTable(widths), labelW + boxes);
            reg.setHorizontalAlignment(Element.ALIGN_LEFT);
            PdfPCell rl = cell(new Phrase(c.registrationLabel(), f.serif(11f, Font.BOLD)));
            rl.setBorder(Rectangle.BOX);
            rl.setBorderWidth(0.8f);
            rl.setPadding(5f);
            rl.setPaddingBottom(11f);
            reg.addCell(rl);
            for (String s : cells) {
                PdfPCell b = cell(new Phrase(s, f.sans(9f, false)));
                b.setBorder(Rectangle.BOX);
                b.setBorderWidth(0.8f);
                b.setHorizontalAlignment(Element.ALIGN_CENTER);
                b.setPaddingTop(1f);
                reg.addCell(b);
            }
            blocks.add(reg);
        }

        // Instructions
        PdfPTable ins = locked(new PdfPTable(1), width);
        PdfPCell ic = new PdfPCell();
        ic.setBorder(Rectangle.BOX);
        ic.setBorderWidth(0.8f);
        ic.setPadding(8f);
        ic.setPaddingLeft(10f);
        ic.setPaddingRight(10f);
        ic.setPaddingBottom(22f);
        ic.addElement(line("INSTRUCTIONS", f.sans(11f, true), Element.ALIGN_LEFT, 0f, 6f));
        for (String s : c.instructions()) ic.addElement(line(upper(s), f.sans(9.8f, true), Element.ALIGN_CENTER, 0f, 2f));
        ins.addCell(ic);
        blocks.add(ins);

        // Date of exam and examiner
        PdfPTable foot = locked(new PdfPTable(new float[]{1f, 1f}), width);
        Phrase date = new Phrase();
        Font ff = f.serif(11f, Font.NORMAL);
        if (c.dateDay() != null) {
            date.add(new Chunk("Date of Exam: " + c.dateDay(), ff));
            Chunk sup = new Chunk(c.dateSuffix(), f.serif(7f, Font.NORMAL));
            sup.setTextRise(4f);
            date.add(sup);
            date.add(new Chunk(" " + c.dateRest() + (c.startTime() != null ? ", " + c.startTime() : ""), ff));
        } else {
            date.add(new Chunk("Date of Exam: not scheduled", ff));
        }
        foot.addCell(cell(date));
        PdfPCell ex = cell(new Phrase(c.examiner() == null ? "" : "Examiner(s): " + c.examiner(), ff));
        ex.setHorizontalAlignment(Element.ALIGN_RIGHT);
        foot.addCell(ex);
        if (p.answers() && printedAt != null) {
            PdfPCell pr = cell(new Phrase("Printed " + printedAt + (printedBy != null ? " by " + printedBy : ""), f.sans(7f, false)));
            pr.setColspan(2);
            pr.setHorizontalAlignment(Element.ALIGN_RIGHT);
            pr.setPaddingTop(8f);
            foot.addCell(pr);
        }
        blocks.add(foot);

        // Share the spare height out: equal gaps between blocks, a little less above the first
        float used = banner == null ? 0f : banner.getTotalHeight() + 8f;
        for (PdfPTable b : blocks) used += b.getTotalHeight();
        float spare = (doc.top() - doc.bottom()) - used;
        float gap = Math.max(MIN_GAP, Math.min(MAX_GAP, spare / (blocks.size() - 1 + 1.2f)));
        float lead = Math.max(0f, Math.min(gap * 0.6f, 36f));

        if (banner != null) {
            banner.setSpacingAfter(8f);
            doc.add(banner);
        }
        for (int i = 0; i < blocks.size(); i++) {
            PdfPTable b = blocks.get(i);
            b.setSpacingBefore(i == 0 ? lead : gap);
            doc.add(b);
        }
    }

    /** A table at a fixed width, so its height can be measured before it is placed. */
    private static PdfPTable locked(PdfPTable t, float width) {
        t.setTotalWidth(width);
        t.setLockedWidth(true);
        t.setHorizontalAlignment(Element.ALIGN_LEFT);
        return t;
    }

    // ── Section A: two columns ───────────────────────────────────────────────

    private void sectionA(Document doc, PdfWriter w, Paper p, PaperFonts f) throws Exception {
        newPage(doc, w);
        Paragraph head = line(p.sectionAHeading(), f.serif(11f, Font.BOLD | Font.UNDERLINE), Element.ALIGN_CENTER, 0f, 10f);
        head.setIndentationLeft(18f);
        head.setIndentationRight(18f);
        doc.add(head);

        float colW = (doc.right() - doc.left() - GUTTER) / 2f;
        ColumnText ct = new ColumnText(w.getDirectContent());
        for (Objective o : p.objective()) ct.addElement(question(o, p.answers(), f, colW));

        float[][] cols = {{doc.left(), doc.left() + colW}, {doc.right() - colW, doc.right()}};
        float top = w.getVerticalPosition(true);
        int col = 0, emptyColumns = 0;
        while (true) {
            ct.setSimpleColumn(cols[col][0], doc.bottom(), cols[col][1], top);
            int status = ct.go();
            if (!ColumnText.hasMoreText(status)) break;
            // Nothing fitted in a whole column twice running: stop rather than add pages forever
            emptyColumns = ct.getYLine() >= top - 0.01f ? emptyColumns + 1 : 0;
            if (emptyColumns >= 2) {
                LoggerFactory.getLogger(QuestionPaperPdf.class).warn("A Section A question is too tall for a column on paper '{}'", p.title());
                break;
            }
            if (col == 0) {
                col = 1;
            } else {
                newPage(doc, w);
                col = 0;
                top = doc.top();
            }
        }
        w.setPageEmpty(false);   // the columns are drawn straight onto the page: keep it
    }

    /** One objective question: its number, then the question, diagram and options. Never split. */
    private PdfPTable question(Objective o, boolean answers, PaperFonts f, float width) throws Exception {
        PdfPTable t = new PdfPTable(new float[]{NUMBER_W, width - NUMBER_W});
        t.setTotalWidth(width);
        t.setLockedWidth(true);
        t.setSplitLate(true);
        t.setSplitRows(true);
        t.setSpacingAfter(3f);
        t.addCell(cell(new Phrase(o.number() + ")", f.serif(BODY, Font.NORMAL))));

        PdfPCell body = cell(null);
        for (Paragraph para : PaperText.paragraphs(o.html(), f, BODY)) body.addElement(para);
        Image img = image(o.image());
        if (img != null) {
            img.setSpacingBefore(2f);
            img.setSpacingAfter(2f);
            body.addElement(img);
        }
        float inner = width - NUMBER_W;
        if (!o.choices().isEmpty()) {
            PdfPTable opts = labelled(inner);
            for (Choice ch : o.choices()) {
                int style = ch.correct() ? Font.BOLD | Font.UNDERLINE : Font.NORMAL;
                opts.addCell(cell(new Phrase(ch.letter().toLowerCase(Locale.ROOT) + ")", f.serif(BODY, ch.correct() ? Font.BOLD : Font.NORMAL))));
                Phrase text = new Phrase(ch.text(), f.serif(BODY, style));
                if (ch.correct()) text.add(new Chunk("  (correct)", f.sans(7.5f, true)));
                opts.addCell(cell(text));
            }
            body.addElement(opts);
        }
        if (!o.prompts().isEmpty()) {
            PdfPTable prompts = labelled(inner);
            for (Prompt pr : o.prompts()) {
                prompts.addCell(cell(new Phrase("(" + pr.label() + ")", f.serif(BODY, Font.NORMAL))));
                Phrase text = new Phrase(pr.text(), f.serif(BODY, Font.NORMAL));
                if (pr.answerLetter() != null) text.add(new Chunk(" = " + pr.answerLetter(), f.serif(BODY, Font.BOLD)));
                prompts.addCell(cell(text));
            }
            body.addElement(prompts);
            body.addElement(line("Match with:", f.serif(9.5f, Font.ITALIC), Element.ALIGN_LEFT, 1f, 0f));
            PdfPTable pool = labelled(inner);
            for (Choice ma : o.matchAnswers()) {
                pool.addCell(cell(new Phrase(ma.letter() + ".", f.serif(BODY, Font.NORMAL))));
                pool.addCell(cell(new Phrase(ma.text(), f.serif(BODY, Font.NORMAL))));
            }
            body.addElement(pool);
        }
        if (o.typed() && !answers) {
            Paragraph blank = new Paragraph();
            blank.add(new Chunk("Answer: ", f.serif(BODY, Font.NORMAL)));
            Chunk lineChunk = new Chunk(" ".repeat(45), f.serif(BODY, Font.NORMAL));
            lineChunk.setUnderline(0.7f, -2f);
            blank.add(lineChunk);
            blank.setSpacingBefore(3f);
            body.addElement(blank);
        }
        if (answers && o.key() != null && !o.key().isEmpty() && (o.typed() || !o.prompts().isEmpty()))
            body.addElement(line("Answer: " + o.key(), f.serif(BODY, Font.BOLD), Element.ALIGN_LEFT, 2f, 0f));
        t.addCell(body);
        return t;
    }

    /** Two columns: a narrow label ("a)", "(i)", "A.") and the text. */
    private static PdfPTable labelled(float width) throws Exception {
        PdfPTable t = new PdfPTable(new float[]{LETTER_W, width - LETTER_W});
        t.setWidthPercentage(100);
        return t;
    }

    // ── Section B ────────────────────────────────────────────────────────────

    private void sectionB(Document doc, PdfWriter w, Paper p, PaperFonts f) throws Exception {
        newPage(doc, w);
        Paragraph head = line(upper(p.sectionBHeading()), f.serif(11f, Font.BOLD | Font.UNDERLINE), Element.ALIGN_CENTER, 0f, 8f);
        head.setIndentationLeft(18f);
        head.setIndentationRight(18f);
        doc.add(head);

        for (TheoryGroup g : p.theory()) {
            PdfPTable t = new PdfPTable(new float[]{30f, 400f, 75f});
            t.setWidthPercentage(100);
            t.setKeepTogether(true);
            t.setSpacingBefore(8f);
            Phrase title = new Phrase(g.key().toUpperCase(Locale.ROOT).startsWith("Q") ? g.key() : "Q" + g.key(), f.serif(11f, Font.BOLD));
            if (g.compulsory()) title.add(new Chunk("  (Compulsory)", f.serif(9.5f, Font.BOLD)));
            PdfPCell h = cell(title);
            h.setColspan(3);
            h.setPaddingBottom(5f);
            t.addCell(h);
            for (TheoryItem it : g.items()) {
                PdfPCell lbl = cell(new Phrase(it.label() == null ? "" : it.label(), f.serif(BODY, Font.NORMAL)));
                lbl.setPaddingLeft(12f);
                t.addCell(lbl);
                PdfPCell txt = cell(null);
                txt.setPaddingRight(8f);
                for (Paragraph para : PaperText.paragraphs(it.html(), f, BODY)) txt.addElement(para);
                t.addCell(txt);
                String marks = it.marks() == null || it.marks().isBlank() ? "" : "1".equals(it.marks()) ? "[1 Mark]" : "[" + it.marks() + " Marks]";
                PdfPCell mk = cell(new Phrase(marks, f.serif(BODY, Font.BOLD)));
                mk.setHorizontalAlignment(Element.ALIGN_RIGHT);
                mk.setVerticalAlignment(Element.ALIGN_BOTTOM);
                mk.setNoWrap(true);
                t.addCell(mk);
                for (PdfPCell c : new PdfPCell[]{lbl, txt, mk}) c.setPaddingBottom(7f);
                Image img = image(it.image());
                if (img != null) {
                    img.setBorder(Rectangle.BOX);
                    img.setBorderWidth(1f);
                    PdfPCell fig = new PdfPCell(img, false);
                    fig.setColspan(3);
                    fig.setBorder(Rectangle.NO_BORDER);
                    fig.setHorizontalAlignment(Element.ALIGN_CENTER);
                    fig.setPadding(4f);
                    fig.setPaddingBottom(9f);
                    t.addCell(fig);
                }
            }
            doc.add(t);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** A page break that also works after content drawn straight onto the page (the columns). */
    private static void newPage(Document doc, PdfWriter w) {
        w.setPageEmpty(false);
        doc.newPage();
    }

    private static PdfPCell cell(Phrase phrase) {
        PdfPCell c = phrase == null ? new PdfPCell() : new PdfPCell(phrase);
        c.setBorder(Rectangle.NO_BORDER);
        c.setPadding(0f);
        c.setPaddingBottom(1.5f);
        c.setUseAscender(true);
        return c;
    }

    private static Paragraph line(String text, Font font, int align, float before, float after) {
        Paragraph p = new Paragraph(text, font);
        p.setAlignment(align);
        p.setLeading(font.getSize() * 1.25f);
        p.setSpacingBefore(before);
        p.setSpacingAfter(after);
        return p;
    }

    private static Font sansColored(PaperFonts f, float size, Color color) {
        Font font = f.sans(size, true);
        font.setColor(color);
        return font;
    }

    private static String upper(String s) { return s == null ? null : s.toUpperCase(Locale.ROOT); }

    private static Image image(QuestionImageService.PdfImage img) {
        if (img == null) return null;
        Image i = image(img.getSrc());
        if (i != null) i.scaleAbsolute(img.getWidthPt(), img.getHeightPt());
        return i;
    }

    /** A data URL (PNG, JPEG, or anything ImageIO reads, e.g. WebP) as a PDF image; null when it cannot be read. */
    private static Image image(String dataUrl) {
        if (dataUrl == null || dataUrl.isBlank()) return null;
        int comma = dataUrl.indexOf(',');
        if (comma < 0) return null;
        try {
            byte[] bytes = Base64.getDecoder().decode(dataUrl.substring(comma + 1));
            try {
                return Image.getInstance(bytes);
            } catch (Exception unsupported) {
                BufferedImage bi = ImageIO.read(new ByteArrayInputStream(bytes));
                if (bi == null) return null;
                ByteArrayOutputStream png = new ByteArrayOutputStream();
                ImageIO.write(bi, "png", png);
                return Image.getInstance(png.toByteArray());
            }
        } catch (Exception e) {
            LoggerFactory.getLogger(QuestionPaperPdf.class).warn("Could not draw an image on a question paper: {}", e.getMessage());
            return null;
        }
    }

    /** "Page X of Y" at the foot of every page but the cover; the answer key is marked on each page. */
    private static final class Footer extends PdfPageEventHelper {
        private final PaperFonts fonts;
        private final boolean answers;
        private PdfTemplate total;

        Footer(PaperFonts fonts, boolean answers) {
            this.fonts = fonts;
            this.answers = answers;
        }

        @Override
        public void onOpenDocument(PdfWriter writer, Document document) {
            total = writer.getDirectContent().createTemplate(30f, 12f);
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            int page = writer.getPageNumber();
            if (page == 1) return;
            PdfContentByte cb = writer.getDirectContent();
            float y = document.bottom() - 24f;
            String text = "Page " + page + " of ";
            cb.beginText();
            cb.setFontAndSize(fonts.sans, 8f);
            cb.setTextMatrix(document.left(), y);
            cb.showText(text);
            cb.endText();
            cb.addTemplate(total, document.left() + fonts.sans.getWidthPoint(text, 8f), y);
            if (answers) {
                cb.beginText();
                cb.setFontAndSize(fonts.sansBold, 7.5f);
                cb.setColorFill(KEY_RED);
                cb.showTextAligned(Element.ALIGN_RIGHT, "ANSWER KEY - CONFIDENTIAL", document.right(), y, 0f);
                cb.setColorFill(Color.BLACK);
                cb.endText();
            }
        }

        @Override
        public void onCloseDocument(PdfWriter writer, Document document) {
            total.beginText();
            total.setFontAndSize(fonts.sans, 8f);
            total.setTextMatrix(0f, 0f);
            total.showText(String.valueOf(writer.getPageNumber() - 1));
            total.endText();
        }
    }
}
