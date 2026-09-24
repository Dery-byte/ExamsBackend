package com.exam.service;

import org.junit.jupiter.api.Test;
import org.xhtmlrenderer.pdf.ITextRenderer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfImageRenderTest {
    @Test
    void flyingSaucerEmbedsJpegDataUri() throws Exception {
        BufferedImage b = new BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream jpg = new ByteArrayOutputStream();
        ImageIO.write(b, "jpg", jpg);
        String src = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpg.toByteArray());
        String withImg = render("<img src=\"" + src + "\" width=\"200\" height=\"100\" alt=\"\"/>");
        String without = render("");
        assertTrue(withImg.length() > 0);
        assertTrue(new String(withImg.getBytes("ISO-8859-1"), "ISO-8859-1").contains("/Subtype/Image")
                || new String(withImg.getBytes("ISO-8859-1"), "ISO-8859-1").contains("/Subtype /Image"));
        assertTrue(!without.contains("/Subtype/Image") && !without.contains("/Subtype /Image"));
    }

    private String render(String body) throws Exception {
        String x = "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><p>Q</p>" + body + "</body></html>";
        ITextRenderer r = new ITextRenderer();
        r.setDocumentFromString(x);
        r.layout();
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        r.createPDF(o);
        return new String(o.toByteArray(), "ISO-8859-1");
    }
}
