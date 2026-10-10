package com.exam.service.reports;

import com.lowagie.text.Chunk;
import com.lowagie.text.Font;
import com.lowagie.text.Paragraph;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Turns a question's text from the editor (HTML: paragraphs, bold, italic, underline, sub- and
 * superscript, lists, line breaks) into PDF paragraphs of real text. Anything else (colours,
 * fonts, classes pasted from elsewhere) is ignored, so every question prints in the paper's font.
 */
final class PaperText {

    private static final Set<String> BLOCKS = Set.of("p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "pre", "section", "article");

    private final PaperFonts fonts;
    private final float size;
    private final List<Paragraph> out = new ArrayList<>();
    private final List<int[]> lists = new ArrayList<>();   // [ordered 0/1, counter]
    private Paragraph current;

    private PaperText(PaperFonts fonts, float size) {
        this.fonts = fonts;
        this.size = size;
    }

    /** The question's paragraphs; plain text works too. */
    static List<Paragraph> paragraphs(String html, PaperFonts fonts, float size) {
        PaperText t = new PaperText(fonts, size);
        if (html != null && !html.isBlank()) t.walk(Jsoup.parseBodyFragment(html).body(), Font.NORMAL, 0);
        t.flush();
        return t.out;
    }

    private void walk(Node node, int style, int script) {
        if (node instanceof TextNode tn) {
            String text = tn.text();
            if (current == null || current.isEmpty()) text = text.stripLeading();
            if (text.isEmpty()) return;
            float s = script == 0 ? size : size * 0.7f;
            Chunk c = new Chunk(text, fonts.serif(s, style));
            if (script > 0) c.setTextRise(size * 0.33f);
            if (script < 0) c.setTextRise(-size * 0.15f);
            paragraph().add(c);
            return;
        }
        if (!(node instanceof Element e)) return;
        String tag = e.normalName();
        switch (tag) {
            case "br" -> { paragraph().add(Chunk.NEWLINE); return; }
            case "img", "script", "style" -> { return; }
            case "b", "strong" -> style |= Font.BOLD;
            case "i", "em" -> style |= Font.ITALIC;
            case "u", "ins" -> style |= Font.UNDERLINE;
            case "s", "strike", "del" -> style |= Font.STRIKETHRU;
            case "sup" -> script = 1;
            case "sub" -> script = -1;
            default -> { }
        }
        if (tag.equals("ul") || tag.equals("ol")) {
            flush();
            lists.add(new int[]{tag.equals("ol") ? 1 : 0, 0});
            for (Node child : e.childNodes()) walk(child, style, script);
            lists.remove(lists.size() - 1);
            flush();
            return;
        }
        if (tag.equals("li")) {
            flush();
            int[] list = lists.isEmpty() ? new int[]{0, 0} : lists.get(lists.size() - 1);
            list[1]++;
            Paragraph p = paragraph();
            float indent = 12f * Math.max(1, lists.size());
            p.setIndentationLeft(indent);
            p.setFirstLineIndent(-10f);
            p.add(new Chunk(list[0] == 1 ? list[1] + ". " : "• ", fonts.serif(size, style)));
            for (Node child : e.childNodes()) walk(child, style, script);
            flush();
            return;
        }
        boolean block = BLOCKS.contains(tag);
        if (block) flush();
        for (Node child : e.childNodes()) walk(child, style, script);
        if (block) flush();
    }

    private Paragraph paragraph() {
        if (current == null) {
            current = new Paragraph();
            current.setLeading(size * 1.28f);
            current.setSpacingAfter(1.5f);
        }
        return current;
    }

    private void flush() {
        if (current != null && !current.isEmpty()) out.add(current);
        current = null;
    }
}
