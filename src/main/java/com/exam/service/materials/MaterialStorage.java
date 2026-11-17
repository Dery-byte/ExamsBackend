package com.exam.service.materials;

import com.exam.model.materials.StoredFile;
import com.exam.repository.materials.StoredFileRepository;
import com.exam.service.SystemSettingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.*;

/**
 * Course material files on disk: {dir}/{first two hex of sha256}/{sha256}. The same content is kept
 * once however many rows use it. Each upload is checked by what it really is (its first bytes), not
 * only its name, so a renamed program or web page is refused. Back up this folder with the database.
 */
@Service
public class MaterialStorage {

    private static final Logger log = LoggerFactory.getLogger(MaterialStorage.class);

    public static final int DEFAULT_MAX_FILE_MB = 25;
    public static final int DEFAULT_QUOTA_GB = 20;

    /** What a file may be, by extension → (content type, check of its first bytes). */
    private record Kind(String contentType, String magic) {}

    private static final Map<String, Kind> KINDS = Map.ofEntries(
            Map.entry("pdf", new Kind("application/pdf", "pdf")),
            Map.entry("doc", new Kind("application/msword", "ole")),
            Map.entry("docx", new Kind("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "zip")),
            Map.entry("ppt", new Kind("application/vnd.ms-powerpoint", "ole")),
            Map.entry("pptx", new Kind("application/vnd.openxmlformats-officedocument.presentationml.presentation", "zip")),
            Map.entry("xls", new Kind("application/vnd.ms-excel", "ole")),
            Map.entry("xlsx", new Kind("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "zip")),
            Map.entry("odt", new Kind("application/vnd.oasis.opendocument.text", "zip")),
            Map.entry("odp", new Kind("application/vnd.oasis.opendocument.presentation", "zip")),
            Map.entry("ods", new Kind("application/vnd.oasis.opendocument.spreadsheet", "zip")),
            Map.entry("zip", new Kind("application/zip", "zip")),
            Map.entry("txt", new Kind("text/plain; charset=utf-8", "text")),
            Map.entry("csv", new Kind("text/csv; charset=utf-8", "text")),
            Map.entry("png", new Kind("image/png", "png")),
            Map.entry("jpg", new Kind("image/jpeg", "jpg")),
            Map.entry("jpeg", new Kind("image/jpeg", "jpg")),
            Map.entry("webp", new Kind("image/webp", "webp")));

    /** Lecturers' notes and assignment files. */
    public static final Set<String> MATERIAL_TYPES = KINDS.keySet();
    /** Students' work and the lecturer's marked copies. */
    public static final Set<String> SUBMISSION_TYPES = Set.of("pdf", "doc", "docx", "odt", "ppt", "pptx", "xls", "xlsx",
            "txt", "png", "jpg", "jpeg", "webp", "zip");

    @Value("${app.materials.dir:${user.home}/exam-materials}")
    private String dir;

    @Autowired private SystemSettingService settings;
    @Autowired private StoredFileRepository files;

    private volatile long usedCache = -1;
    private volatile long usedAt = 0;

    public Path root() {
        return Paths.get(dir).toAbsolutePath().normalize();
    }

    public int maxFileMb() {
        return settings.getIntSetting(SystemSettingService.MATERIALS_MAX_FILE_MB, DEFAULT_MAX_FILE_MB, 1, 100);
    }

    public int quotaGb() {
        return settings.getIntSetting(SystemSettingService.MATERIALS_QUOTA_GB, DEFAULT_QUOTA_GB, 1, 10_000);
    }

    /** Bytes used on disk (each content once), refreshed at most every minute. */
    public long usedBytes() {
        long now = System.currentTimeMillis();
        if (usedCache >= 0 && now - usedAt < 60_000) return usedCache;
        long sum = 0;
        for (Object[] row : files.distinctContents()) sum += row[1] == null ? 0 : ((Number) row[1]).longValue();
        usedCache = sum;
        usedAt = now;
        return sum;
    }

    /** Accepted extensions, for messages and the file picker. */
    public static String accepted(Set<String> types) {
        return String.join(", ", new TreeSet<>(types));
    }

    /**
     * Checks and stores one upload; returns an unsaved StoredFile (id, name, type, size, sha) for the
     * caller to link and save. Refuses empty, too large, wrong-type and over-quota files.
     */
    public StoredFile store(MultipartFile upload, Set<String> allowed, StoredFile.Role role, Long uploaderId) {
        if (upload == null || upload.isEmpty()) throw new IllegalArgumentException("The file is empty.");
        String name = cleanName(upload.getOriginalFilename());
        String ext = extension(name);
        Kind kind = KINDS.get(ext);
        if (kind == null || !allowed.contains(ext))
            throw new IllegalArgumentException("\"" + name + "\" can't be uploaded. Allowed: " + accepted(allowed) + ".");
        long max = maxFileMb() * 1024L * 1024L;
        if (upload.getSize() > max)
            throw new IllegalArgumentException("\"" + name + "\" is larger than " + maxFileMb() + " MB.");
        long quota = quotaGb() * 1024L * 1024L * 1024L;
        if (usedBytes() + upload.getSize() > quota)
            throw new IllegalArgumentException("The institution's file storage is full (" + quotaGb() + " GB). Ask the system developer for more space, or empty the recycle bins.");

        Path tmp = null;
        try {
            Path root = root();
            Files.createDirectories(root.resolve("tmp"));
            tmp = Files.createTempFile(root.resolve("tmp"), "up-", ".part");
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            long size;
            try (InputStream in = new DigestInputStream(upload.getInputStream(), md);
                 OutputStream out = Files.newOutputStream(tmp)) {
                size = in.transferTo(out);
            }
            if (size == 0) throw new IllegalArgumentException("The file is empty.");
            if (size > max) throw new IllegalArgumentException("\"" + name + "\" is larger than " + maxFileMb() + " MB.");
            if (!looksLike(tmp, kind.magic()))
                throw new IllegalArgumentException("\"" + name + "\" doesn't look like a real ." + ext + " file. Save it again and upload that copy.");

            String sha = HexFormat.of().formatHex(md.digest());
            Path target = pathFor(sha);
            if (!Files.exists(target)) {
                Files.createDirectories(target.getParent());
                try {
                    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
                    tmp = null;
                } catch (FileAlreadyExistsException same) {
                    // the same content arrived at the same moment; keep the copy already there
                }
                usedCache = -1;
            }
            StoredFile f = new StoredFile();
            f.setId(UUID.randomUUID().toString());
            f.setOriginalName(name);
            f.setContentType(kind.contentType());
            f.setSizeBytes(size);
            f.setSha256(sha);
            f.setRole(role);
            f.setUploadedById(uploaderId);
            return f;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("[Materials] Could not store an upload", e);
            throw new IllegalStateException("The file couldn't be saved. Please try again.");
        } finally {
            if (tmp != null) try { Files.deleteIfExists(tmp); } catch (IOException ignored) { }
        }
    }

    /** A new row sharing a stored file's content (copies from an earlier term use no extra space). */
    public StoredFile duplicate(StoredFile src, StoredFile.Role role, Long uploaderId) {
        StoredFile f = new StoredFile();
        f.setId(UUID.randomUUID().toString());
        f.setOriginalName(src.getOriginalName());
        f.setContentType(src.getContentType());
        f.setSizeBytes(src.getSizeBytes());
        f.setSha256(src.getSha256());
        f.setRole(role);
        f.setUploadedById(uploaderId);
        return f;
    }

    public Path pathFor(String sha) {
        if (sha == null || !sha.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Bad file.");
        return root().resolve(sha.substring(0, 2)).resolve(sha);
    }

    public Path open(StoredFile f) {
        Path p = pathFor(f.getSha256());
        if (!Files.exists(p)) throw new IllegalArgumentException("This file is missing from the server's storage.");
        return p;
    }

    public byte[] read(StoredFile f) throws IOException {
        return Files.readAllBytes(open(f));
    }

    /** Removes the content from disk once no row uses it any more. Call after deleting the rows. */
    public void deleteIfUnused(String sha) {
        try {
            if (files.countBySha256(sha) > 0) return;
            if (Files.deleteIfExists(pathFor(sha))) usedCache = -1;
            Files.deleteIfExists(root().resolve("previews").resolve(sha + ".pdf"));   // its PDF view, if one was made
        } catch (Exception e) {
            log.warn("[Materials] Could not remove an unused file: {}", e.getMessage());
        }
    }

    // ── Checks ──────────────────────────────────────────────────────────────

    static String cleanName(String raw) {
        String n = raw == null ? "" : raw.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1);
        n = n.replaceAll("[\\p{Cntrl}\"<>|*?:]", "").trim();
        if (n.isEmpty()) n = "file";
        if (n.length() > 200) {
            String ext = extension(n);
            n = n.substring(0, 190) + (ext.isEmpty() ? "" : "." + ext);
        }
        return n;
    }

    static String extension(String name) {
        int dot = name == null ? -1 : name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static boolean looksLike(Path p, String magic) throws IOException {
        byte[] head = new byte[8192];
        int n;
        try (InputStream in = Files.newInputStream(p)) { n = in.readNBytes(head, 0, head.length); }
        return looksLike(Arrays.copyOf(head, n), magic);
    }

    static boolean looksLike(byte[] b, String magic) {
        return switch (magic) {
            case "pdf" -> new String(b, 0, Math.min(b.length, 1024), StandardCharsets.ISO_8859_1).contains("%PDF-");
            case "zip" -> starts(b, 0x50, 0x4B, 0x03, 0x04) || starts(b, 0x50, 0x4B, 0x05, 0x06);
            case "ole" -> starts(b, 0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1);
            case "png" -> starts(b, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A);
            case "jpg" -> starts(b, 0xFF, 0xD8, 0xFF);
            case "webp" -> b.length >= 12 && starts(b, 0x52, 0x49, 0x46, 0x46)
                    && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P';
            case "text" -> {
                for (byte x : b) if (x == 0) { yield false; }
                String start = new String(b, 0, Math.min(b.length, 512), StandardCharsets.UTF_8).trim().toLowerCase(Locale.ROOT);
                yield !(start.startsWith("<!doctype") || start.startsWith("<html") || start.startsWith("<script") || start.startsWith("<svg"));
            }
            default -> false;
        };
    }

    private static boolean starts(byte[] b, int... sig) {
        if (b.length < sig.length) return false;
        for (int i = 0; i < sig.length; i++) if ((b[i] & 0xFF) != sig[i]) return false;
        return true;
    }
}
