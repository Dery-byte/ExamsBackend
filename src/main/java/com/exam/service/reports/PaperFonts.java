package com.exam.service.reports;

import com.lowagie.text.Font;
import com.lowagie.text.pdf.BaseFont;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.List;

/**
 * Fonts for printed question papers. Text is drawn as real (vector) text, so it stays sharp at
 * any zoom or print size.
 * <p>
 * The serif family (questions, headings) is embedded from a TrueType font so any character a
 * question uses (Greek letters, maths symbols …) prints. It is looked for in this order:
 * <ol>
 *   <li>{@code resources/fonts/PaperSerif-Regular.ttf}, {@code -Bold}, {@code -Italic}, {@code -BoldItalic}
 *       (drop an openly licensed font there to choose one);</li>
 *   <li>Liberation Serif (same widths as Times New Roman), then DejaVu Serif, on Linux servers;</li>
 *   <li>Times New Roman on Windows;</li>
 *   <li>the PDF's built-in Times, which needs no file but covers Western characters only.</li>
 * </ol>
 * Short labels (instructions box, time allowed, page numbers) use the built-in Helvetica.
 */
final class PaperFonts {

    private static final Logger log = LoggerFactory.getLogger(PaperFonts.class);

    private static volatile PaperFonts loaded;

    final BaseFont regular, bold, italic, boldItalic, sans, sansBold;
    final String source;

    private PaperFonts(BaseFont regular, BaseFont bold, BaseFont italic, BaseFont boldItalic, String source) throws Exception {
        this.regular = regular;
        this.bold = bold;
        this.italic = italic;
        this.boldItalic = boldItalic;
        this.sans = BaseFont.createFont(BaseFont.HELVETICA, BaseFont.CP1252, BaseFont.NOT_EMBEDDED);
        this.sansBold = BaseFont.createFont(BaseFont.HELVETICA_BOLD, BaseFont.CP1252, BaseFont.NOT_EMBEDDED);
        this.source = source;
    }

    /** Loaded once; the fonts are shared by every paper. */
    static PaperFonts get() {
        PaperFonts f = loaded;
        if (f == null) {
            synchronized (PaperFonts.class) {
                if (loaded == null) loaded = load();
                f = loaded;
            }
        }
        return f;
    }

    /** Serif font; style is a mix of Font.BOLD, ITALIC, UNDERLINE, STRIKETHRU. */
    Font serif(float size, int style) {
        boolean b = (style & Font.BOLD) != 0, i = (style & Font.ITALIC) != 0;
        BaseFont base = b && i ? boldItalic : b ? bold : i ? italic : regular;
        return new Font(base, size, style & (Font.UNDERLINE | Font.STRIKETHRU));
    }

    Font sans(float size, boolean bold) {
        return new Font(bold ? sansBold : sans, size, Font.NORMAL);
    }

    // ── Loading ──────────────────────────────────────────────────────────────

    private static PaperFonts load() {
        try {
            PaperFonts bundled = fromClasspath();
            if (bundled != null) return bundled;
            for (List<String> family : List.of(
                    List.of("/usr/share/fonts/truetype/liberation2/LiberationSerif-Regular.ttf", "/usr/share/fonts/truetype/liberation2/LiberationSerif-Bold.ttf",
                            "/usr/share/fonts/truetype/liberation2/LiberationSerif-Italic.ttf", "/usr/share/fonts/truetype/liberation2/LiberationSerif-BoldItalic.ttf"),
                    List.of("/usr/share/fonts/truetype/liberation/LiberationSerif-Regular.ttf", "/usr/share/fonts/truetype/liberation/LiberationSerif-Bold.ttf",
                            "/usr/share/fonts/truetype/liberation/LiberationSerif-Italic.ttf", "/usr/share/fonts/truetype/liberation/LiberationSerif-BoldItalic.ttf"),
                    List.of("/usr/share/fonts/truetype/dejavu/DejaVuSerif.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSerif-Bold.ttf",
                            "/usr/share/fonts/truetype/dejavu/DejaVuSerif-Italic.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSerif-BoldItalic.ttf"),
                    List.of("C:/Windows/Fonts/times.ttf", "C:/Windows/Fonts/timesbd.ttf", "C:/Windows/Fonts/timesi.ttf", "C:/Windows/Fonts/timesbi.ttf"))) {
                PaperFonts f = fromFiles(family);
                if (f != null) return f;
            }
            log.info("Question papers use the built-in Times font (no TrueType serif font found); only Western characters will print.");
            return new PaperFonts(
                    BaseFont.createFont(BaseFont.TIMES_ROMAN, BaseFont.CP1252, BaseFont.NOT_EMBEDDED),
                    BaseFont.createFont(BaseFont.TIMES_BOLD, BaseFont.CP1252, BaseFont.NOT_EMBEDDED),
                    BaseFont.createFont(BaseFont.TIMES_ITALIC, BaseFont.CP1252, BaseFont.NOT_EMBEDDED),
                    BaseFont.createFont(BaseFont.TIMES_BOLDITALIC, BaseFont.CP1252, BaseFont.NOT_EMBEDDED), "built-in Times");
        } catch (Exception e) {
            throw new IllegalStateException("Could not load fonts for question papers", e);
        }
    }

    /** Regular and bold are required; a missing italic falls back to the upright font. */
    private static PaperFonts fromFiles(List<String> paths) {
        if (!new File(paths.get(0)).isFile() || !new File(paths.get(1)).isFile()) return null;
        try {
            BaseFont r = embed(Files.readAllBytes(new File(paths.get(0)).toPath()), paths.get(0));
            BaseFont b = embed(Files.readAllBytes(new File(paths.get(1)).toPath()), paths.get(1));
            BaseFont i = new File(paths.get(2)).isFile() ? embed(Files.readAllBytes(new File(paths.get(2)).toPath()), paths.get(2)) : r;
            BaseFont bi = new File(paths.get(3)).isFile() ? embed(Files.readAllBytes(new File(paths.get(3)).toPath()), paths.get(3)) : b;
            return new PaperFonts(r, b, i, bi, paths.get(0));
        } catch (Exception e) {
            log.warn("Could not load font {}: {}", paths.get(0), e.getMessage());
            return null;
        }
    }

    private static PaperFonts fromClasspath() {
        ClassPathResource r = new ClassPathResource("fonts/PaperSerif-Regular.ttf");
        ClassPathResource b = new ClassPathResource("fonts/PaperSerif-Bold.ttf");
        if (!r.exists() || !b.exists()) return null;
        try {
            BaseFont regular = embed(read(r), "PaperSerif-Regular.ttf");
            BaseFont bold = embed(read(b), "PaperSerif-Bold.ttf");
            ClassPathResource i = new ClassPathResource("fonts/PaperSerif-Italic.ttf");
            ClassPathResource bi = new ClassPathResource("fonts/PaperSerif-BoldItalic.ttf");
            return new PaperFonts(regular, bold, i.exists() ? embed(read(i), "PaperSerif-Italic.ttf") : regular,
                    bi.exists() ? embed(read(bi), "PaperSerif-BoldItalic.ttf") : bold, "classpath fonts/PaperSerif-*");
        } catch (Exception e) {
            log.warn("Could not load the bundled question-paper font: {}", e.getMessage());
            return null;
        }
    }

    private static byte[] read(ClassPathResource r) throws Exception {
        try (InputStream in = r.getInputStream()) { return StreamUtils.copyToByteArray(in); }
    }

    /** Embedded (subset) with Unicode encoding, so every character the questions use is available. */
    private static BaseFont embed(byte[] ttf, String name) throws Exception {
        return BaseFont.createFont(name, BaseFont.IDENTITY_H, BaseFont.EMBEDDED, true, ttf, null);
    }
}
