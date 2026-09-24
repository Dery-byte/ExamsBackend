package com.exam.service;

import com.exam.model.exam.QuestionImage;
import com.exam.repository.QuestionImageRepository;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QuestionImageServiceTest {

    private final Map<String, QuestionImage> store = new HashMap<>();
    private final QuestionImageService service = build();

    private QuestionImageService build() {
        QuestionImageRepository repo = mock(QuestionImageRepository.class);
        when(repo.save(any(QuestionImage.class))).thenAnswer(i -> {
            QuestionImage q = i.getArgument(0);
            store.put(q.getId(), q);
            return q;
        });
        when(repo.findById(any(String.class))).thenAnswer(i -> Optional.ofNullable(store.get(i.<String>getArgument(0))));
        QuestionImageService s = new QuestionImageService();
        ReflectionTestUtils.setField(s, "repository", repo);
        return s;
    }

    private byte[] img(String fmt, int type, int w, int h) throws Exception {
        BufferedImage b = new BufferedImage(w, h, type);
        for (int x = 0; x < w; x++) for (int y = 0; y < h; y++) b.setRGB(x, y, (x * 7 + y * 3) | 0xFF000000);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ImageIO.write(b, fmt, o);
        return o.toByteArray();
    }

    @Test
    void convertsPngJpgJpegToWebp() throws Exception {
        Object[][] cases = {
                {"a.png", "image/png", img("png", BufferedImage.TYPE_INT_ARGB, 120, 80)},
                {"a.jpg", "image/jpeg", img("jpg", BufferedImage.TYPE_INT_RGB, 120, 80)},
                {"a.JPEG", "image/jpeg", img("jpg", BufferedImage.TYPE_INT_RGB, 3000, 100)},
        };
        for (Object[] c : cases) {
            String path = service.storeAsWebp(new MockMultipartFile("file", (String) c[0], (String) c[1], (byte[]) c[2]));
            assertTrue(path.startsWith("question-images/") && path.endsWith(".webp"), path);
            byte[] out = service.load(path.substring("question-images/".length(), path.length() - 5));
            assertEquals("RIFF", new String(out, 0, 4));
            assertEquals("WEBP", new String(out, 8, 4));
            BufferedImage back = ImageIO.read(new ByteArrayInputStream(out));
            assertNotNull(back);
            assertTrue(back.getWidth() <= 1600);
        }
    }

    @Test
    void pdfRenderingDecodesWebpToJpegDataUri() throws Exception {
        String path = service.storeAsWebp(new MockMultipartFile("file", "a.jpg", "image/jpeg", img("jpg", BufferedImage.TYPE_INT_RGB, 800, 400)));
        QuestionImageService.PdfImage pdf = service.loadForPdf(path, 380, 200);
        assertNotNull(pdf);
        assertTrue(pdf.getSrc().startsWith("data:image/jpeg;base64,"));
        assertEquals(380, pdf.getWidthPt());
        assertEquals(190, pdf.getHeightPt());
        assertNull(service.loadForPdf(null, 380, 200));
        assertNull(service.loadForPdf("question-images/missing.webp", 380, 200));
    }

    @Test
    void rejectsBadInput() throws Exception {
        assertThrows(ResponseStatusException.class, () -> service.storeAsWebp(
                new MockMultipartFile("file", "a.gif", "image/gif", img("gif", BufferedImage.TYPE_INT_RGB, 10, 10))));
        assertThrows(ResponseStatusException.class, () -> service.storeAsWebp(
                new MockMultipartFile("file", "a.png", "image/png", "not an image".getBytes())));
    }
}
