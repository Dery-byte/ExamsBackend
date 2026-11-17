package com.exam.service.materials;

import com.exam.model.materials.StoredFile;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Exact PDF copies of Word and PowerPoint files (.doc .docx .odt .ppt .pptx .odp), made with LibreOffice
 * when it is installed on the server (Ubuntu: {@code apt install libreoffice-nogui}). The viewer shows
 * the PDF so slides and documents look as they do in Office, and Claude / Gemini can read their pictures
 * when marking. Without LibreOffice nothing changes: the browser draws the file itself.
 * <p>
 * Copies are kept beside the files ({dir}/previews/{sha256}.pdf), made once per content, at most two at a
 * time, each allowed two minutes; uploads start their copy straight away so the first view is quick.
 */
@Service
public class OfficePreview {

    private static final Logger log = LoggerFactory.getLogger(OfficePreview.class);
    private static final Set<String> CONVERTIBLE = Set.of("doc", "docx", "odt", "ppt", "pptx", "odp");
    private static final long TIMEOUT_SECONDS = 120;

    @Value("${app.materials.soffice:}")
    private String configured;

    @Autowired private MaterialStorage storage;

    private final Semaphore slots = new Semaphore(2);
    private final ExecutorService warmer = Executors.newFixedThreadPool(1, r -> {
        Thread t = new Thread(r, "office-preview");
        t.setDaemon(true);
        return t;
    });
    /** Contents that couldn't be converted recently, so a broken file isn't retried on every view. */
    private final Map<String, Long> failed = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    private volatile Optional<Path> soffice;

    @PreDestroy
    void stop() { warmer.shutdownNow(); }

    /** The LibreOffice program, if there is one (app.materials.soffice, else the usual places, else the PATH). */
    public Optional<Path> program() {
        Optional<Path> s = soffice;
        if (s != null) return s;
        List<String> candidates = new ArrayList<>();
        if (configured != null && !configured.isBlank()) candidates.add(configured.trim());
        candidates.addAll(List.of("/usr/bin/soffice", "/usr/bin/libreoffice", "/usr/lib/libreoffice/program/soffice",
                "/opt/libreoffice/program/soffice", "/snap/bin/libreoffice",
                "C:/Program Files/LibreOffice/program/soffice.exe", "C:/Program Files (x86)/LibreOffice/program/soffice.exe",
                "/Applications/LibreOffice.app/Contents/MacOS/soffice"));
        String path = System.getenv("PATH");
        if (path != null) for (String dir : path.split(java.io.File.pathSeparator)) {
            candidates.add(dir + "/soffice");
            candidates.add(dir + "/soffice.exe");
        }
        Optional<Path> found = candidates.stream().map(Paths::get).filter(Files::isRegularFile).filter(Files::isExecutable).findFirst();
        found.ifPresent(p -> log.info("[Materials] Word and PowerPoint previews use LibreOffice at {}", p));
        soffice = found;
        return found;
    }

    public boolean available() { return program().isPresent(); }

    public boolean convertible(StoredFile f) {
        return CONVERTIBLE.contains(MaterialStorage.extension(f.getOriginalName()));
    }

    /** Whether the viewer should ask for the PDF copy. */
    public boolean offered(StoredFile f) {
        return convertible(f) && available() && !recentlyFailed(f.getSha256());
    }

    /** The PDF copy if it is already made (no waiting). */
    public Optional<Path> ready(StoredFile f) {
        if (!convertible(f)) return Optional.empty();
        Path p = previewPath(f.getSha256());
        return Files.isRegularFile(p) ? Optional.of(p) : Optional.empty();
    }

    /** Starts making the copy in the background (after an upload). */
    public void warm(StoredFile f) {
        if (!offered(f) || ready(f).isPresent()) return;
        try { warmer.submit(() -> { try { pdf(f); } catch (Exception ignored) { } }); }
        catch (RejectedExecutionException ignored) { }
    }

    /** The PDF copy, made now if needed (may take up to two minutes). */
    public Path pdf(StoredFile f) throws IOException {
        if (!convertible(f)) throw new IllegalArgumentException("This kind of file has no PDF view.");
        Path out = previewPath(f.getSha256());
        if (Files.isRegularFile(out)) return out;
        Path program = program().orElseThrow(() -> new IllegalArgumentException("The server can't convert Office files."));
        if (recentlyFailed(f.getSha256())) throw new IllegalArgumentException("This file couldn't be converted for viewing. Download it instead.");
        Object lock = locks.computeIfAbsent(f.getSha256(), k -> new Object());
        synchronized (lock) {                       // two people opening the same file: convert once
            if (Files.isRegularFile(out)) return out;
            boolean got = false;
            try {
                got = slots.tryAcquire(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (!got) throw new IllegalArgumentException("The server is busy preparing other files. Try again in a minute, or download it.");
                convert(program, f, out);
                return out;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalArgumentException("Preparing the file was interrupted.");
            } finally {
                if (got) slots.release();
                locks.remove(f.getSha256());
            }
        }
    }

    private void convert(Path program, StoredFile f, Path out) throws IOException {
        Path work = Files.createTempDirectory(storage.root().resolve("tmp"), "lo-");
        String ext = MaterialStorage.extension(f.getOriginalName());
        Path in = work.resolve("in." + ext);
        Files.copy(storage.open(f), in);
        // Its own profile folder, so a desktop LibreOffice (or another conversion) doesn't block it
        Path profile = storage.root().resolve("lo-profile");
        List<String> cmd = List.of(program.toString(), "--headless", "--norestore", "--nolockcheck", "--nodefault",
                "-env:UserInstallation=" + profile.toUri(), "--convert-to", "pdf", "--outdir", work.toString(), in.toString());
        Process proc = null;
        try {
            proc = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(work.resolve("log.txt").toFile()).start();
            if (!proc.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                throw new IOException("timed out");
            }
            Path made = work.resolve("in.pdf");
            if (proc.exitValue() != 0 || !Files.isRegularFile(made) || Files.size(made) == 0)
                throw new IOException("LibreOffice exit " + proc.exitValue());
            Files.createDirectories(out.getParent());
            Files.move(made, out, StandardCopyOption.REPLACE_EXISTING);
            failed.remove(f.getSha256());
        } catch (InterruptedException e) {
            if (proc != null) proc.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        } catch (IOException e) {
            failed.put(f.getSha256(), System.currentTimeMillis());
            log.warn("[Materials] Could not make a PDF view of a .{} file: {}", ext, e.getMessage());
            throw new IllegalArgumentException("This file couldn't be prepared for viewing. Download it instead.");
        } finally {
            try (var walk = Files.walk(work)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) { } });
            } catch (IOException ignored) { }
        }
    }

    private boolean recentlyFailed(String sha) {
        Long at = failed.get(sha);
        return at != null && System.currentTimeMillis() - at < 30 * 60_000L;
    }

    private Path previewPath(String sha) {
        if (sha == null || !sha.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Bad file.");
        return storage.root().resolve("previews").resolve(sha + ".pdf");
    }
}
