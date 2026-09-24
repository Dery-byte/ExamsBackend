package com.exam.service;

import com.exam.model.exam.QuestionImage;
import com.exam.repository.QuestionImageRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Accepts .png / .jpg / .jpeg uploads and stores them converted to WebP.
 */
@Service
public class QuestionImageService {

    public static final String PATH_PREFIX = "question-images/";
    private static final String SUFFIX = ".webp";
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("png", "jpg", "jpeg");
    private static final Set<String> ALLOWED_MIME = Set.of("image/png", "image/jpeg", "image/jpg");
    private static final int MAX_DIMENSION = 1600;   // px, longest side
    private static final float WEBP_QUALITY = 0.85f;

    @Autowired
    private QuestionImageRepository repository;

    /** Validates, converts to WebP, persists; returns the path to store on the question. */
    public String storeAsWebp(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw bad("No image file was provided");
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw bad("Only .png, .jpg and .jpeg images are allowed");
        }
        String mime = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        if (!ALLOWED_MIME.contains(mime)) {
            throw bad("Only PNG and JPEG images are allowed");
        }

        BufferedImage source;
        try {
            // Decoding is the real content check — a renamed non-image fails here.
            source = ImageIO.read(file.getInputStream());
        } catch (IOException e) {
            throw bad("The file could not be read as an image");
        }
        if (source == null) {
            throw bad("The file is not a valid PNG or JPEG image");
        }

        byte[] webp;
        try {
            webp = encodeWebp(prepare(source, ext));
        } catch (IOException | RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Image conversion to WebP failed");
        }

        String id = UUID.randomUUID().toString();
        repository.save(new QuestionImage(id, webp));
        return PATH_PREFIX + id + SUFFIX;
    }

    public byte[] load(String id) {
        return repository.findById(id)
                .map(QuestionImage::getData)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Image not found"));
    }

    /** Deletes the stored image referenced by a question's image path (no-op otherwise). */
    public void deleteByPath(String path) {
        if (path == null || !path.startsWith(PATH_PREFIX) || !path.endsWith(SUFFIX)) return;
        String id = path.substring(PATH_PREFIX.length(), path.length() - SUFFIX.length());
        if (repository.existsById(id)) repository.deleteById(id);
    }

    /** Image prepared for the PDF renderer (which cannot decode WebP): JPEG/PNG data URI + size in pt. */
    public static class PdfImage {
        private final String src; private final int widthPt; private final int heightPt;
        public PdfImage(String src, int widthPt, int heightPt) { this.src = src; this.widthPt = widthPt; this.heightPt = heightPt; }
        public String getSrc() { return src; }
        public int getWidthPt() { return widthPt; }
        public int getHeightPt() { return heightPt; }
    }

    /** Returns null when the path is empty/unknown or the image cannot be decoded. */
    public PdfImage loadForPdf(String path, int maxWidthPt, int maxHeightPt) {
        if (path == null || !path.startsWith(PATH_PREFIX) || !path.endsWith(SUFFIX)) return null;
        String id = path.substring(PATH_PREFIX.length(), path.length() - SUFFIX.length());
        try {
            var stored = repository.findById(id);
            if (stored.isEmpty()) return null;
            BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(stored.get().getData()));
            if (img == null) return null;

            boolean alpha = img.getColorModel().hasAlpha();
            BufferedImage out = new BufferedImage(img.getWidth(), img.getHeight(),
                    alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics();
            if (!alpha) { g.setColor(Color.WHITE); g.fillRect(0, 0, out.getWidth(), out.getHeight()); }
            g.drawImage(img, 0, 0, null);
            g.dispose();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(out, alpha ? "png" : "jpg", baos);
            String mime = alpha ? "image/png" : "image/jpeg";

            double scale = Math.min(1.0, Math.min((double) maxWidthPt / img.getWidth(), (double) maxHeightPt / img.getHeight()));
            int w = Math.max(1, (int) Math.round(img.getWidth() * scale));
            int h = Math.max(1, (int) Math.round(img.getHeight() * scale));
            return new PdfImage("data:" + mime + ";base64," + java.util.Base64.getEncoder().encodeToString(baos.toByteArray()), w, h);
        } catch (Throwable e) {
            // Never let a broken image (or missing native WebP library) take the whole report down.
            org.slf4j.LoggerFactory.getLogger(QuestionImageService.class).warn("Could not render question image {} for PDF", path, e);
            return null;
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Downscales oversized images; flattens JPEG-style sources onto white (no alpha needed). */
    private BufferedImage prepare(BufferedImage src, String ext) {
        int w = src.getWidth(), h = src.getHeight();
        double scale = Math.min(1.0, (double) MAX_DIMENSION / Math.max(w, h));
        int nw = Math.max(1, (int) Math.round(w * scale));
        int nh = Math.max(1, (int) Math.round(h * scale));

        boolean keepAlpha = src.getColorModel().hasAlpha() && "png".equals(ext);
        BufferedImage out = new BufferedImage(nw, nh,
                keepAlpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            if (!keepAlpha) {
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, nw, nh);
            }
            g.drawImage(src, 0, 0, nw, nh, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private byte[] encodeWebp(BufferedImage image) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByMIMEType("image/webp");
        if (!writers.hasNext()) {
            throw new IOException("No WebP ImageWriter available");
        }
        ImageWriter writer = writers.next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        if (param.canWriteCompressed()) {
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            String[] types = param.getCompressionTypes();
            if (types != null && types.length > 0) {
                // "Lossy" is the first/default type in the webp-imageio plugin
                param.setCompressionType(types[0]);
            }
            param.setCompressionQuality(WEBP_QUALITY);
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return baos.toByteArray();
    }

    private ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }
}
