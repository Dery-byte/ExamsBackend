package com.exam.controller;

import com.exam.exception.ErrorMessage;
import com.exam.model.User;
import com.exam.repository.UserRepository;
import com.exam.service.materials.AssignmentService;
import com.exam.service.materials.MaterialService;
import com.exam.service.comms.CurrentUserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Course materials (/api/materials). /manage: staff (lecturer of the course, its HOD, the Super Admin);
 * /student: registered students; /download and /zip: signed 5-minute links (no sign-in header, so a
 * phone can open them in a new tab). The developer's module switch is enforced by
 * {@link com.exam.config.MaterialsModuleInterceptor}; the rest in the services.
 */
@RestController
@RequestMapping("/api/materials")
public class MaterialsController {

    private static final Logger log = LoggerFactory.getLogger(MaterialsController.class);

    @Autowired private CurrentUserService currentUserService;
    @Autowired private MaterialService materials;
    @Autowired private AssignmentService assignments;
    @Autowired private UserRepository users;
    @Autowired private com.exam.service.materials.Gradebook gradebook;

    // ══ Staff ════════════════════════════════════════════════════════════════

    @GetMapping("/manage/courses")
    public ResponseEntity<?> manageCourses(@RequestParam(required = false) String q) {
        return withUser(u -> ResponseEntity.ok(materials.manageCourses(u, q)));
    }

    @GetMapping("/manage/courses/{courseId}")
    public ResponseEntity<?> manageSpace(@PathVariable Long courseId) {
        return withUser(u -> ResponseEntity.ok(materials.manageSpace(u, courseId)));
    }

    @PutMapping("/manage/courses/{courseId}/settings")
    public ResponseEntity<?> saveSettings(@PathVariable Long courseId, @RequestBody MaterialService.SettingsRequest req) {
        return withUser(u -> ResponseEntity.ok(materials.saveSettings(u, courseId, req)));
    }

    @PutMapping("/manage/courses/{courseId}/weeks/{number}")
    public ResponseEntity<?> saveWeek(@PathVariable Long courseId, @PathVariable int number, @RequestBody MaterialService.WeekRequest req) {
        return withUser(u -> ResponseEntity.ok(materials.saveWeek(u, courseId, number, req)));
    }

    @PostMapping(value = "/manage/courses/{courseId}/items", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> create(@PathVariable Long courseId, @RequestPart("data") MaterialService.MaterialRequest req,
                                    @RequestPart(value = "files", required = false) List<MultipartFile> files) {
        return withUser(u -> ResponseEntity.ok(materials.create(u, courseId, req, files)));
    }

    @PutMapping("/manage/items/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody MaterialService.MaterialRequest req) {
        return withUser(u -> ResponseEntity.ok(materials.update(u, id, req)));
    }

    @PutMapping("/manage/items/{id}/visibility")
    public ResponseEntity<?> visibility(@PathVariable Long id, @RequestBody MaterialService.VisibilityRequest req) {
        return withUser(u -> ResponseEntity.ok(materials.setVisibility(u, id, req)));
    }

    @PostMapping(value = "/manage/items/{id}/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> addFiles(@PathVariable Long id, @RequestPart("files") List<MultipartFile> files) {
        return withUser(u -> ResponseEntity.ok(materials.addFiles(u, id, files)));
    }

    @DeleteMapping("/manage/items/{id}/files/{fileId}")
    public ResponseEntity<?> removeFile(@PathVariable Long id, @PathVariable String fileId) {
        return withUser(u -> ResponseEntity.ok(materials.removeFile(u, id, fileId)));
    }

    @DeleteMapping("/manage/items/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        return withUser(u -> ResponseEntity.ok(materials.delete(u, id)));
    }

    @GetMapping("/manage/courses/{courseId}/bin")
    public ResponseEntity<?> bin(@PathVariable Long courseId) {
        return withUser(u -> ResponseEntity.ok(materials.bin(u, courseId)));
    }

    @PostMapping("/manage/items/{id}/restore")
    public ResponseEntity<?> restore(@PathVariable Long id) {
        return withUser(u -> ResponseEntity.ok(materials.restore(u, id)));
    }

    @DeleteMapping("/manage/items/{id}/purge")
    public ResponseEntity<?> purge(@PathVariable Long id) {
        return withUser(u -> { materials.purge(u, id); return ResponseEntity.ok(Map.of("message", "Deleted for good.")); });
    }

    @GetMapping("/manage/items/{id}/views")
    public ResponseEntity<?> views(@PathVariable Long id) {
        return withUser(u -> ResponseEntity.ok(materials.viewsOf(u, id)));
    }

    @GetMapping("/manage/courses/{courseId}/copy-sources")
    public ResponseEntity<?> copySources(@PathVariable Long courseId) {
        return withUser(u -> ResponseEntity.ok(materials.copySources(u, courseId)));
    }

    @PostMapping("/manage/courses/{courseId}/copy")
    public ResponseEntity<?> copy(@PathVariable Long courseId, @RequestBody Map<String, Long> body) {
        return withUser(u -> ResponseEntity.ok(materials.copyFrom(u, courseId, body == null ? null : body.get("fromSpaceId"))));
    }

    @PostMapping("/manage/files/{fileId}/link")
    public ResponseEntity<?> staffLink(@PathVariable String fileId, @RequestParam(defaultValue = "false") boolean inline) {
        return withUser(u -> ResponseEntity.ok(materials.staffFileLink(u, fileId, inline)));
    }

    @PostMapping("/manage/courses/{courseId}/weeks/{number}/zip-link")
    public ResponseEntity<?> staffZip(@PathVariable Long courseId, @PathVariable int number) {
        return withUser(u -> ResponseEntity.ok(materials.zipLink(u, courseId, number)));
    }

    // ── Marks table: every assignment and the running total ─────────────────

    @GetMapping("/manage/courses/{courseId}/gradebook")
    public ResponseEntity<?> gradebook(@PathVariable Long courseId) {
        return withUser(u -> ResponseEntity.ok(gradebook.staff(u, courseId)));
    }

    @PutMapping("/manage/assignments/{id}/counts")
    public ResponseEntity<?> counts(@PathVariable Long id, @RequestBody Map<String, Boolean> body) {
        return withUser(u -> ResponseEntity.ok(gradebook.setCounts(u, id, body == null || !Boolean.FALSE.equals(body.get("counts")))));
    }

    @GetMapping("/manage/courses/{courseId}/gradebook/sheets")
    public ResponseEntity<?> gradebookSheets(@PathVariable Long courseId) {
        return withUser(u -> ResponseEntity.ok(gradebook.sheets(u, courseId)));
    }

    @PostMapping("/manage/courses/{courseId}/gradebook/send-to-sheet")
    public ResponseEntity<?> gradebookToSheet(@PathVariable Long courseId, @RequestBody com.exam.service.materials.Gradebook.SheetRequest req) {
        return withUser(u -> ResponseEntity.ok(gradebook.sendToSheet(u, courseId, req)));
    }

    // ── Assignments ─────────────────────────────────────────────────────────

    @GetMapping("/manage/assignments/{id}")
    public ResponseEntity<?> roster(@PathVariable Long id) {
        return withUser(u -> ResponseEntity.ok(assignments.roster(u, id)));
    }

    @PostMapping("/manage/assignments/{id}/reopen")
    public ResponseEntity<?> reopen(@PathVariable Long id, @RequestBody AssignmentService.ReopenRequest req) {
        return withUser(u -> ResponseEntity.ok(assignments.reopen(u, id, req)));
    }

    @PostMapping("/manage/assignments/{id}/close")
    public ResponseEntity<?> close(@PathVariable Long id) {
        return withUser(u -> ResponseEntity.ok(assignments.closeNow(u, id)));
    }

    @PutMapping("/manage/assignments/{id}/release")
    public ResponseEntity<?> release(@PathVariable Long id, @RequestBody Map<String, Boolean> body) {
        return withUser(u -> ResponseEntity.ok(assignments.releaseMarks(u, id, body != null && Boolean.TRUE.equals(body.get("released")))));
    }

    @GetMapping("/manage/assignments/{id}/students/{studentId}")
    public ResponseEntity<?> work(@PathVariable Long id, @PathVariable Long studentId) {
        return withUser(u -> ResponseEntity.ok(assignments.work(u, id, studentId)));
    }

    @PutMapping("/manage/assignments/{id}/students/{studentId}/mark")
    public ResponseEntity<?> mark(@PathVariable Long id, @PathVariable Long studentId, @RequestBody AssignmentService.MarkRequest req) {
        return withUser(u -> ResponseEntity.ok(assignments.mark(u, id, studentId, req)));
    }

    @PostMapping("/manage/assignments/{id}/students/{studentId}/ai")
    public ResponseEntity<?> aiSuggest(@PathVariable Long id, @PathVariable Long studentId, @RequestBody(required = false) Map<String, String> body) {
        return withUser(u -> ResponseEntity.ok(assignments.aiSuggest(u, id, studentId, body == null ? null : body.get("provider"))));
    }

    @PostMapping("/manage/assignments/{id}/ai-all")
    public ResponseEntity<?> aiAll(@PathVariable Long id, @RequestBody(required = false) Map<String, String> body) {
        return withUser(u -> ResponseEntity.ok(assignments.aiSuggestAll(u, id, body == null ? null : body.get("provider"))));
    }

    @DeleteMapping("/manage/assignments/{id}/ai-all")
    public ResponseEntity<?> cancelAiAll(@PathVariable Long id) {
        return withUser(u -> ResponseEntity.ok(assignments.cancelAiQueue(u, id)));
    }

    @PostMapping(value = "/manage/assignments/{id}/students/{studentId}/feedback-files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> addFeedback(@PathVariable Long id, @PathVariable Long studentId, @RequestPart("files") List<MultipartFile> files) {
        return withUser(u -> ResponseEntity.ok(assignments.addFeedbackFiles(u, id, studentId, files)));
    }

    @DeleteMapping("/manage/assignments/{id}/students/{studentId}/feedback-files/{fileId}")
    public ResponseEntity<?> removeFeedback(@PathVariable Long id, @PathVariable Long studentId, @PathVariable String fileId) {
        return withUser(u -> ResponseEntity.ok(assignments.removeFeedbackFile(u, id, studentId, fileId)));
    }

    @GetMapping("/manage/assignments/{id}/sheets")
    public ResponseEntity<?> sheets(@PathVariable Long id) {
        return withUser(u -> ResponseEntity.ok(assignments.sheets(u, id)));
    }

    @PostMapping("/manage/assignments/{id}/send-to-sheet")
    public ResponseEntity<?> sendToSheet(@PathVariable Long id, @RequestBody AssignmentService.SheetRequest req) {
        return withUser(u -> ResponseEntity.ok(assignments.sendToSheet(u, id, req)));
    }

    // ══ Students ═════════════════════════════════════════════════════════════

    @GetMapping("/student/courses")
    public ResponseEntity<?> studentCourses() {
        return withUser(u -> ResponseEntity.ok(materials.studentCourses(u)));
    }

    @GetMapping("/student/overview")
    public ResponseEntity<?> overview() {
        return withUser(u -> ResponseEntity.ok(materials.studentOverview(u)));
    }

    @GetMapping("/student/courses/{courseId}")
    public ResponseEntity<?> studentSpace(@PathVariable Long courseId) {
        return withUser(u -> ResponseEntity.ok(materials.studentSpace(u, courseId)));
    }

    @PostMapping("/student/items/{id}/opened")
    public ResponseEntity<?> opened(@PathVariable Long id) {
        return withUser(u -> { materials.opened(u, id); return ResponseEntity.ok(Map.of("ok", true)); });
    }

    @PostMapping("/student/files/{fileId}/link")
    public ResponseEntity<?> studentLink(@PathVariable String fileId, @RequestParam(defaultValue = "false") boolean inline) {
        return withUser(u -> ResponseEntity.ok(materials.studentFileLink(u, fileId, inline)));
    }

    @PostMapping("/student/courses/{courseId}/weeks/{number}/zip-link")
    public ResponseEntity<?> studentZip(@PathVariable Long courseId, @PathVariable int number) {
        return withUser(u -> ResponseEntity.ok(materials.zipLink(u, courseId, number)));
    }

    @GetMapping("/student/assignments/{id}")
    public ResponseEntity<?> studentAssignment(@PathVariable Long id) {
        return withUser(u -> ResponseEntity.ok(assignments.studentView(u, id)));
    }

    @PostMapping(value = "/student/assignments/{id}/submission", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> submit(@PathVariable Long id,
                                    @RequestParam(value = "typedAnswer", required = false) String typedAnswer,
                                    @RequestParam(value = "keep", required = false) List<String> keep,
                                    @RequestPart(value = "files", required = false) List<MultipartFile> files) {
        return withUser(u -> ResponseEntity.ok(assignments.submit(u, id, typedAnswer, keep, files)));
    }

    // ══ Signed downloads ═════════════════════════════════════════════════════

    @GetMapping("/download/{fileId}")
    public ResponseEntity<?> download(@PathVariable String fileId, @RequestParam("u") long userId, @RequestParam("e") long exp,
                                      @RequestParam("i") String inline, @RequestParam("s") String sig) {
        try {
            MaterialService.Download d = materials.download(fileId, userId, exp, inline, sig);
            ContentDisposition cd = (d.inline() ? ContentDisposition.inline() : ContentDisposition.attachment())
                    .filename(d.name(), StandardCharsets.UTF_8).build();
            HttpHeaders h = new HttpHeaders();
            h.setContentDisposition(cd);
            h.setContentType(MediaType.parseMediaType(d.contentType()));
            h.set("X-Content-Type-Options", "nosniff");
            h.setCacheControl(CacheControl.maxAge(java.time.Duration.ofMinutes(5)).cachePrivate());
            // Shown in the browser: no scripts, no forms, nothing loaded from elsewhere
            if (d.inline()) h.set("Content-Security-Policy", "sandbox; default-src 'none'; img-src 'self' data:; style-src 'unsafe-inline'");
            return new ResponseEntity<>(new FileSystemResource(d.path()), h, HttpStatus.OK);
        } catch (AccessDeniedException e) {
            return plain(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (IllegalArgumentException e) {
            return plain(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    /** The PDF view of a Word / PowerPoint file (made by LibreOffice the first time; may take a minute). */
    @GetMapping("/preview/{fileId}")
    public ResponseEntity<?> preview(@PathVariable String fileId, @RequestParam("u") long userId, @RequestParam("e") long exp,
                                     @RequestParam("s") String sig) {
        try {
            MaterialService.Download d = materials.preview(fileId, userId, exp, sig);
            HttpHeaders h = new HttpHeaders();
            h.setContentDisposition(ContentDisposition.inline().filename(d.name(), StandardCharsets.UTF_8).build());
            h.setContentType(MediaType.APPLICATION_PDF);
            h.set("X-Content-Type-Options", "nosniff");
            h.setCacheControl(CacheControl.maxAge(java.time.Duration.ofMinutes(5)).cachePrivate());
            return new ResponseEntity<>(new FileSystemResource(d.path()), h, HttpStatus.OK);
        } catch (AccessDeniedException e) {
            return plain(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (IllegalArgumentException e) {
            return plain(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        } catch (java.io.IOException e) {
            return plain(HttpStatus.INTERNAL_SERVER_ERROR, "The file couldn't be prepared for viewing. Download it instead.");
        }
    }

    /**
     * Declared as ResponseEntity&lt;StreamingResponseBody&gt;, not &lt;?&gt;: Spring streams the body only when the
     * declared type says so; with &lt;?&gt; it looks for a message converter for application/zip and fails (500).
     * Refusals are streamed as plain text for the same reason.
     */
    @GetMapping("/zip/{spaceId}/{week}")
    public ResponseEntity<StreamingResponseBody> zip(@PathVariable long spaceId, @PathVariable int week, @RequestParam("u") long userId,
                                                     @RequestParam("e") long exp, @RequestParam("s") String sig) {
        try {
            User who = users.findById(userId).orElse(null);
            MaterialService.ZipBundle bundle = materials.zip(spaceId, week, userId, exp, sig, who);
            if (bundle.entries().isEmpty()) return plainStream(HttpStatus.NOT_FOUND, "There are no files in this week yet.");
            materials.recordZipViews(who, spaceId, week);
            StreamingResponseBody body = out -> {
                try (ZipOutputStream zip = new ZipOutputStream(out)) {
                    for (MaterialService.ZipEntryFile f : bundle.entries()) {
                        zip.putNextEntry(new ZipEntry(f.name()));
                        try (InputStream in = Files.newInputStream(f.path())) { in.transferTo(zip); }
                        zip.closeEntry();
                    }
                }
            };
            HttpHeaders h = new HttpHeaders();
            h.setContentDisposition(ContentDisposition.attachment().filename(bundle.name(), StandardCharsets.UTF_8).build());
            h.setContentType(MediaType.parseMediaType("application/zip"));
            h.set("X-Content-Type-Options", "nosniff");
            return new ResponseEntity<>(body, h, HttpStatus.OK);
        } catch (AccessDeniedException e) {
            return plainStream(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (IllegalArgumentException e) {
            return plainStream(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    private static ResponseEntity<StreamingResponseBody> plainStream(HttpStatus status, String message) {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.status(status).contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
                .body(out -> out.write(bytes));
    }

    private static ResponseEntity<String> plain(HttpStatus status, String message) {
        return ResponseEntity.status(status).contentType(new MediaType("text", "plain", StandardCharsets.UTF_8)).body(message);
    }

    // ══ Plumbing ═════════════════════════════════════════════════════════════

    @FunctionalInterface
    private interface UserAction { ResponseEntity<?> apply(User u); }

    private ResponseEntity<?> withUser(UserAction action) {
        Optional<User> user = currentUserService.current();
        if (user.isEmpty()) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorMessage("Please sign in."));
        try {
            return action.apply(user.get());
        } catch (AccessDeniedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ErrorMessage(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ErrorMessage(e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ErrorMessage(e.getMessage()));
        } catch (DataIntegrityViolationException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorMessage("That was already saved. Refresh the page and try again."));
        } catch (RuntimeException e) {
            log.error("[Materials] Request failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ErrorMessage("Something went wrong. Please try again."));
        }
    }
}
