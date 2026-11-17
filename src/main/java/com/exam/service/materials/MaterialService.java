package com.exam.service.materials;

import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.academic.AcademicSession;
import com.exam.model.exam.Category;
import com.exam.model.exam.LlmProvider;
import com.exam.model.materials.*;
import com.exam.repository.AttendanceSessionRepository;
import com.exam.repository.CategoryRepository;
import com.exam.repository.materials.*;
import com.exam.service.academic.AcademicSessionService;
import com.exam.service.comms.CurrentUserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Course materials: each course's weeks, its notes, links and assignments, their files, who sees
 * what and when, and the students' side. Assignment submission and marking are in AssignmentService.
 * <p>
 * A student sees an item when: the module and the feature are on for the course, the lecturer shows
 * the course to students, the item's week is open (or its release time has passed), the item is
 * visible (or its own time has passed) and, if the lecturer chose so, the student isn't taking a
 * quiz of this course right now.
 */
@Service
public class MaterialService {

    public static final int MAX_FILES_PER_UPLOAD = 10;

    @Autowired private CourseSpaceRepository spaces;
    @Autowired private CourseWeekRepository weeks;
    @Autowired private CourseMaterialRepository materials;
    @Autowired private StoredFileRepository files;
    @Autowired private MaterialViewRepository views;
    @Autowired private AssignmentSubmissionRepository submissions;
    @Autowired private CategoryRepository categories;
    @Autowired private AttendanceSessionRepository rosters;
    @Autowired private AcademicSessionService academicSessions;
    @Autowired private MaterialAccess access;
    @Autowired private MaterialCalendar calendar;
    @Autowired private MaterialStorage storage;
    @Autowired private MaterialLinks links;
    @Autowired private AssignmentAiMarker ai;
    @Autowired private OfficePreview officePreview;

    // ══ Requests ═════════════════════════════════════════════════════════════

    public record SettingsRequest(Boolean visibleToStudents, Boolean hideDuringQuiz, LocalDate weekOneStart,
                                  Integer weeksCount, Boolean useTermDates, Boolean autoRelease,
                                  String autoReleaseDay, LocalTime autoReleaseTime) {}

    public record WeekRequest(String topic, String state, LocalDateTime releaseAt) {}

    public record MaterialRequest(Integer weekNumber, String kind, String title, String description, String linkUrl,
                                  String visibility, LocalDateTime visibleFrom, Boolean pinned,
                                  LocalDateTime dueAt, BigDecimal maxMarks, String markingGuide, String aiProvider,
                                  Boolean allowTyped, Boolean allowFiles) {}

    public record VisibilityRequest(String visibility, LocalDateTime visibleFrom) {}

    // ══ Staff: courses ═══════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public List<Map<String, Object>> manageCourses(User u, String q) {
        access.requireModule();
        List<Category> list = switch (u.getRole()) {
            case SUPER_ADMIN -> categories.findAll();
            case ADMIN -> u.getDepartment() == null ? List.of() : spaces.coursesInDepartment(u.getDepartment().getId());
            case LECTURER -> categories.findByUser_Id(u.getId());
            default -> throw new AccessDeniedException("Only staff manage course materials.");
        };
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        list = list.stream()
                .filter(c -> u.getRole() == Role.SUPER_ADMIN || access.onForCourse(c))
                .filter(c -> needle.isEmpty() || (nz(c.getTitle()) + " " + nz(c.getCourseCode())).toLowerCase(Locale.ROOT).contains(needle))
                .sorted(Comparator.comparing((Category c) -> nz(c.getCourseCode())).thenComparing(c -> nz(c.getTitle())))
                .limit(300).toList();
        if (list.isEmpty()) return List.of();

        AcademicSession session = academicSessions.current();
        Map<Long, CourseSpace> byCourse = new HashMap<>();
        for (CourseSpace s : spaces.findByCourse_CidIn(list.stream().map(Category::getCid).toList()))
            if (s.getAcademicSession().getId().equals(session.getId())) byCourse.put(s.getCourse().getCid(), s);
        Map<Long, List<CourseMaterial>> itemsBySpace = byCourse.isEmpty() ? Map.of()
                : materials.liveIn(byCourse.values().stream().map(CourseSpace::getId).toList()).stream()
                    .collect(Collectors.groupingBy(m -> m.getSpace().getId()));
        List<Long> assignmentIds = itemsBySpace.values().stream().flatMap(List::stream)
                .filter(CourseMaterial::isAssignment).map(CourseMaterial::getId).toList();
        Map<Long, Long> handedIn = counts(assignmentIds.isEmpty() ? List.of() : submissions.countsFor(assignmentIds));
        Map<Long, Long> marked = counts(assignmentIds.isEmpty() ? List.of() : submissions.markedCountsFor(assignmentIds));

        List<Map<String, Object>> out = new ArrayList<>();
        for (Category c : list) {
            CourseSpace s = byCourse.get(c.getCid());
            List<CourseMaterial> items = s == null ? List.of() : itemsBySpace.getOrDefault(s.getId(), List.of());
            long toMark = items.stream().filter(CourseMaterial::isAssignment)
                    .mapToLong(m -> handedIn.getOrDefault(m.getId(), 0L) - marked.getOrDefault(m.getId(), 0L)).sum();
            out.add(map("courseId", c.getCid(), "title", c.getTitle(), "code", c.getCourseCode(), "level", c.getLevel(),
                    "semester", c.getSemester(), "lecturer", CurrentUserService.displayName(c.getUser()),
                    "items", items.stream().filter(m -> !m.isAssignment()).count(),
                    "assignments", items.stream().filter(CourseMaterial::isAssignment).count(),
                    "toMark", Math.max(0, toMark),
                    "visibleToStudents", s == null || s.isVisibleToStudents()));
        }
        return out;
    }

    /** The course's materials page for staff. */
    @Transactional
    public Map<String, Object> manageSpace(User u, Long courseId) {
        Category c = course(courseId);
        access.requireOnFor(u, c);
        MaterialAccess.requireManage(u, c);
        CourseSpace s = spaceFor(c);
        return staffView(s);
    }

    @Transactional
    public Map<String, Object> saveSettings(User u, Long courseId, SettingsRequest r) {
        CourseSpace s = managedSpace(u, courseId);
        if (r.visibleToStudents() != null) s.setVisibleToStudents(r.visibleToStudents());
        if (r.hideDuringQuiz() != null) s.setHideDuringQuiz(r.hideDuringQuiz());
        if (Boolean.TRUE.equals(r.useTermDates())) {
            s.setWeekOneStart(null);
            s.setWeeksCount(null);
        } else {
            if (r.weekOneStart() != null) s.setWeekOneStart(r.weekOneStart());
            if (r.weeksCount() != null) {
                if (r.weeksCount() < 1 || r.weeksCount() > MaterialCalendar.MAX_WEEKS)
                    throw new IllegalArgumentException("Choose between 1 and " + MaterialCalendar.MAX_WEEKS + " weeks.");
                s.setWeeksCount(r.weeksCount());
            }
        }
        if (r.autoRelease() != null) {
            if (r.autoRelease()) {
                DayOfWeek day = r.autoReleaseDay() == null ? DayOfWeek.MONDAY : parseDay(r.autoReleaseDay());
                LocalTime time = r.autoReleaseTime() == null ? LocalTime.of(8, 0) : r.autoReleaseTime();
                if (calendar.weekOneMonday(s) == null)
                    throw new IllegalArgumentException("Set when week 1 starts first, so each week's release date is known.");
                s.setAutoRelease(true);
                s.setAutoReleaseDay(day);
                s.setAutoReleaseTime(time);
                scheduleFutureWeeks(s);
            } else {
                s.setAutoRelease(false);
            }
        }
        s.setUpdatedAt(LocalDateTime.now());
        spaces.save(s);
        return staffView(s);
    }

    /** Weekly release: every week whose release moment is still ahead opens then. */
    private void scheduleFutureWeeks(CourseSpace s) {
        LocalDate one = calendar.weekOneMonday(s);
        if (one == null) return;
        LocalDateTime now = LocalDateTime.now();
        int n = calendar.weeksCount(s);
        Map<Integer, CourseWeek> rows = weeks.findBySpace_IdOrderByNumber(s.getId()).stream()
                .collect(Collectors.toMap(CourseWeek::getNumber, w -> w, (a, b) -> a));
        for (int week = 1; week <= n; week++) {
            LocalDateTime at = releaseMoment(one, week, s.getAutoReleaseDay(), s.getAutoReleaseTime());
            if (!at.isAfter(now)) continue;
            CourseWeek w = rows.get(week);
            if (w == null) {
                w = new CourseWeek();
                w.setSpace(s);
                w.setNumber(week);
            }
            w.setState(CourseWeek.State.SCHEDULED);
            w.setReleaseAt(at);
            w.setUpdatedAt(now);
            weeks.save(w);
        }
    }

    static LocalDateTime releaseMoment(LocalDate weekOneMonday, int week, DayOfWeek day, LocalTime time) {
        LocalDate monday = MaterialCalendar.weekStart(weekOneMonday, week);
        return monday.plusDays((day == null ? DayOfWeek.MONDAY : day).getValue() - 1L)
                .atTime(time == null ? LocalTime.of(8, 0) : time);
    }

    @Transactional
    public Map<String, Object> saveWeek(User u, Long courseId, int number, WeekRequest r) {
        CourseSpace s = managedSpace(u, courseId);
        if (number < 0 || number > MaterialCalendar.MAX_WEEKS) throw new IllegalArgumentException("No such week.");
        CourseWeek w = weeks.findBySpace_IdAndNumber(s.getId(), number).orElseGet(() -> {
            CourseWeek x = new CourseWeek();
            x.setSpace(s);
            x.setNumber(number);
            return x;
        });
        if (r.topic() != null) {
            String t = r.topic().trim();
            if (t.length() > 200) throw new IllegalArgumentException("Keep the topic under 200 characters.");
            w.setTopic(t.isEmpty() ? null : t);
        }
        if (r.state() != null) {
            CourseWeek.State st = parse(CourseWeek.State.class, r.state(), "week state");
            if (st == CourseWeek.State.SCHEDULED) {
                if (r.releaseAt() == null) throw new IllegalArgumentException("Choose when the week opens.");
                w.setReleaseAt(r.releaseAt());
            } else {
                w.setReleaseAt(null);
            }
            w.setState(st);
        }
        w.setUpdatedAt(LocalDateTime.now());
        weeks.save(w);
        return staffView(s);
    }

    // ══ Staff: items ═════════════════════════════════════════════════════════

    @Transactional
    public Map<String, Object> create(User u, Long courseId, MaterialRequest r, List<MultipartFile> uploads) {
        CourseSpace s = managedSpace(u, courseId);
        CourseMaterial m = new CourseMaterial();
        m.setSpace(s);
        m.setKind(parse(CourseMaterial.Kind.class, r.kind(), "kind"));
        m.setCreatedBy(u);
        m.setCreatedByName(CurrentUserService.displayName(u));
        apply(m, r, true);
        List<CourseMaterial> sameWeek = materials.live(s.getId()).stream().filter(x -> x.getWeekNumber() == m.getWeekNumber()).toList();
        m.setSortOrder(sameWeek.stream().mapToInt(CourseMaterial::getSortOrder).max().orElse(0) + 1);
        if (m.getKind() == CourseMaterial.Kind.NOTE && (uploads == null || uploads.stream().allMatch(f -> f == null || f.isEmpty()))
                && (m.getDescription() == null || m.getDescription().isBlank()))
            throw new IllegalArgumentException("Add at least one file (or some text) to the notes.");
        List<StoredFile> stored = storeAll(u, uploads);      // a refused file stops everything before anything is saved
        materials.save(m);
        for (StoredFile f : stored) { f.setMaterial(m); files.save(f); officePreview.warm(f); }
        return staffView(s);
    }

    @Transactional
    public Map<String, Object> update(User u, Long id, MaterialRequest r) {
        CourseMaterial m = managedItem(u, id);
        apply(m, r, false);
        materials.save(m);
        return staffView(m.getSpace());
    }

    @Transactional
    public Map<String, Object> addFiles(User u, Long id, List<MultipartFile> uploads) {
        CourseMaterial m = managedItem(u, id);
        if (m.getKind() == CourseMaterial.Kind.LINK) throw new IllegalArgumentException("Links don't take files; add notes instead.");
        attach(u, m, uploads);
        m.setUpdatedAt(LocalDateTime.now());
        return staffView(m.getSpace());
    }

    @Transactional
    public Map<String, Object> removeFile(User u, Long id, String fileId) {
        CourseMaterial m = managedItem(u, id);
        StoredFile f = files.findById(fileId).filter(x -> x.getMaterial() != null && x.getMaterial().getId().equals(m.getId()))
                .orElseThrow(() -> new IllegalArgumentException("That file isn't on this item."));
        String sha = f.getSha256();
        files.delete(f);
        files.flush();
        storage.deleteIfUnused(sha);
        m.setUpdatedAt(LocalDateTime.now());
        return staffView(m.getSpace());
    }

    @Transactional
    public Map<String, Object> setVisibility(User u, Long id, VisibilityRequest r) {
        CourseMaterial m = managedItem(u, id);
        CourseMaterial.Visibility v = parse(CourseMaterial.Visibility.class, r.visibility(), "visibility");
        applyVisibility(m, v, r.visibleFrom());
        m.setUpdatedAt(LocalDateTime.now());
        materials.save(m);
        return staffView(m.getSpace());
    }

    /** Moves an item to the recycle bin (30 days). Assignments with handed-in work can only be hidden. */
    @Transactional
    public Map<String, Object> delete(User u, Long id) {
        CourseMaterial m = managedItem(u, id);
        if (m.isAssignment() && !submissions.findByAssignment_Id(m.getId()).isEmpty())
            throw new IllegalArgumentException("Students have handed in work for this assignment, so it can't be deleted. Hide it instead.");
        m.setDeletedAt(LocalDateTime.now());
        m.setDeletedByName(CurrentUserService.displayName(u));
        materials.save(m);
        return staffView(m.getSpace());
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> bin(User u, Long courseId) {
        CourseSpace s = managedSpaceReadOnly(u, courseId);
        if (s == null) return List.of();
        return materials.bin(s.getId()).stream().map(m -> map("id", m.getId(), "kind", m.getKind().name(), "title", m.getTitle(),
                "weekNumber", m.getWeekNumber(), "deletedAt", m.getDeletedAt(), "deletedBy", m.getDeletedByName(),
                "purgeOn", m.getDeletedAt().plusDays(30).toLocalDate(),
                "files", files.findByMaterial_IdOrderByCreatedAt(m.getId()).size())).toList();
    }

    @Transactional
    public Map<String, Object> restore(User u, Long id) {
        CourseMaterial m = materials.findById(id).orElseThrow(() -> new IllegalArgumentException("Item not found."));
        access.requireOnFor(u, m.getSpace().getCourse());
        MaterialAccess.requireManage(u, m.getSpace().getCourse());
        m.setDeletedAt(null);
        m.setDeletedByName(null);
        materials.save(m);
        return staffView(m.getSpace());
    }

    /** Deletes a binned item for good, with its files (when nothing else uses them). */
    @Transactional
    public void purge(User u, Long id) {
        CourseMaterial m = materials.findById(id).orElseThrow(() -> new IllegalArgumentException("Item not found."));
        access.requireOnFor(u, m.getSpace().getCourse());
        MaterialAccess.requireManage(u, m.getSpace().getCourse());
        if (m.getDeletedAt() == null) throw new IllegalArgumentException("Move it to the recycle bin first.");
        purgeItem(m);
    }

    /** Bin items older than 30 days (the scheduler). */
    @Transactional
    public int purgeOldBin() {
        List<CourseMaterial> old = materials.binnedBefore(LocalDateTime.now().minusDays(30));
        old.forEach(this::purgeItem);
        return old.size();
    }

    private void purgeItem(CourseMaterial m) {
        List<StoredFile> fs = files.findByMaterial_IdOrderByCreatedAt(m.getId());
        Set<String> shas = fs.stream().map(StoredFile::getSha256).collect(Collectors.toSet());
        files.deleteAll(fs);
        views.deleteAll(views.findByMaterialId(m.getId()));
        materials.delete(m);
        files.flush();
        shas.forEach(storage::deleteIfUnused);
    }

    /** Who opened an item, against the class list. */
    @Transactional(readOnly = true)
    public Map<String, Object> viewsOf(User u, Long id) {
        CourseMaterial m = managedItem(u, id);
        Map<Long, MaterialView> byStudent = views.findByMaterialId(m.getId()).stream()
                .collect(Collectors.toMap(MaterialView::getStudentId, v -> v, (a, b) -> a));
        List<User> roster = roster(m.getSpace());
        List<Map<String, Object>> rows = roster.stream()
                .sorted(Comparator.comparing(CurrentUserService::displayName, String.CASE_INSENSITIVE_ORDER))
                .map(st -> {
                    MaterialView v = byStudent.get(st.getId());
                    return map("studentId", st.getId(), "name", CurrentUserService.displayName(st), "username", st.getUsername(),
                            "opened", v != null, "firstAt", v == null ? null : v.getFirstAt(),
                            "lastAt", v == null ? null : v.getLastAt(), "times", v == null ? 0 : v.getOpenCount());
                }).toList();
        return map("materialId", m.getId(), "title", m.getTitle(), "rosterSize", roster.size(),
                "opened", rows.stream().filter(r -> Boolean.TRUE.equals(r.get("opened"))).count(), "students", rows);
    }

    // ══ Copy from an earlier term ════════════════════════════════════════════

    @Transactional(readOnly = true)
    public List<Map<String, Object>> copySources(User u, Long courseId) {
        Category c = course(courseId);
        access.requireOnFor(u, c);
        MaterialAccess.requireManage(u, c);
        AcademicSession now = academicSessions.current();
        return spaces.otherSessions(c.getCid(), now.getId()).stream().map(s -> {
            List<CourseMaterial> items = materials.live(s.getId());
            return map("spaceId", s.getId(), "session", s.getAcademicSession().getName(),
                    "notes", items.stream().filter(m -> !m.isAssignment()).count(),
                    "assignments", items.stream().filter(CourseMaterial::isAssignment).count());
        }).filter(m -> ((Long) m.get("notes")) + ((Long) m.get("assignments")) > 0).toList();
    }

    /** Copies weeks' topics and every item (as drafts; assignments without dates) from another session. */
    @Transactional
    public Map<String, Object> copyFrom(User u, Long courseId, Long fromSpaceId) {
        CourseSpace target = managedSpace(u, courseId);
        CourseSpace from = spaces.findById(fromSpaceId).orElseThrow(() -> new IllegalArgumentException("Nothing to copy from."));
        if (!from.getCourse().getCid().equals(target.getCourse().getCid()) || from.getId().equals(target.getId()))
            throw new IllegalArgumentException("You can only copy this course's own materials from an earlier term.");

        Map<Integer, CourseWeek> targetWeeks = weeks.findBySpace_IdOrderByNumber(target.getId()).stream()
                .collect(Collectors.toMap(CourseWeek::getNumber, w -> w, (a, b) -> a));
        for (CourseWeek w : weeks.findBySpace_IdOrderByNumber(from.getId())) {
            if (w.getTopic() == null) continue;
            CourseWeek t = targetWeeks.get(w.getNumber());
            if (t != null && t.getTopic() != null) continue;
            if (t == null) {
                t = new CourseWeek();
                t.setSpace(target);
                t.setNumber(w.getNumber());
            }
            t.setTopic(w.getTopic());
            t.setUpdatedAt(LocalDateTime.now());
            weeks.save(t);
        }
        Set<Long> already = materials.live(target.getId()).stream().map(CourseMaterial::getCopiedFromId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        int copied = 0;
        for (CourseMaterial src : materials.live(from.getId())) {
            if (already.contains(src.getId())) continue;
            CourseMaterial m = new CourseMaterial();
            m.setSpace(target);
            m.setWeekNumber(src.getWeekNumber());
            m.setKind(src.getKind());
            m.setTitle(src.getTitle());
            m.setDescription(src.getDescription());
            m.setLinkUrl(src.getLinkUrl());
            m.setVisibility(CourseMaterial.Visibility.DRAFT);
            m.setPinned(src.isPinned());
            m.setSortOrder(src.getSortOrder());
            m.setMaxMarks(src.getMaxMarks());
            m.setMarkingGuide(src.getMarkingGuide());
            m.setAiProvider(src.getAiProvider());
            m.setAllowTyped(src.isAllowTyped());
            m.setAllowFiles(src.isAllowFiles());
            m.setCreatedBy(u);
            m.setCreatedByName(CurrentUserService.displayName(u));
            m.setCopiedFromId(src.getId());
            materials.save(m);
            for (StoredFile f : files.findByMaterial_IdOrderByCreatedAt(src.getId())) {
                StoredFile copy = storage.duplicate(f, StoredFile.Role.ATTACHMENT, u.getId());
                copy.setMaterial(m);
                files.save(copy);
            }
            copied++;
        }
        Map<String, Object> view = staffView(target);
        view.put("copied", copied);
        return view;
    }

    // ══ Links ════════════════════════════════════════════════════════════════

    /** A 5-minute download link for staff (attachments of courses they manage, their students' work). */
    @Transactional(readOnly = true)
    public Map<String, Object> staffFileLink(User u, String fileId, boolean inline) {
        StoredFile f = files.findById(fileId).orElseThrow(() -> new IllegalArgumentException("File not found."));
        CourseMaterial m = f.getMaterial() != null ? f.getMaterial()
                : f.getSubmission() != null ? f.getSubmission().getAssignment() : null;
        if (m == null) throw new IllegalArgumentException("File not found.");
        access.requireOnFor(u, m.getSpace().getCourse());
        MaterialAccess.requireManage(u, m.getSpace().getCourse());
        return linkFor(f, u, inline);
    }

    /** A 5-minute download link for a student: files they can see, their own work and its marked copy. */
    @Transactional
    public Map<String, Object> studentFileLink(User u, String fileId, boolean inline) {
        StoredFile f = files.findById(fileId).orElseThrow(() -> new IllegalArgumentException("File not found."));
        if (f.getMaterial() != null) {
            CourseMaterial m = f.getMaterial();
            requireStudentSees(u, m);
            recordView(u, m.getId());
        } else if (f.getSubmission() != null) {
            AssignmentSubmission sub = f.getSubmission();
            if (!sub.getStudent().getId().equals(u.getId())) throw new AccessDeniedException("That isn't your file.");
            if (f.getRole() == StoredFile.Role.FEEDBACK && !sub.getAssignment().isMarksReleased())
                throw new AccessDeniedException("Your lecturer hasn't released the marked work yet.");
            access.requireOnFor(u, sub.getAssignment().getSpace().getCourse());
        } else {
            throw new IllegalArgumentException("File not found.");
        }
        return linkFor(f, u, inline);
    }

    private Map<String, Object> linkFor(StoredFile f, User u, boolean inline) {
        boolean canInline = inline && (f.getContentType().startsWith("application/pdf") || f.getContentType().startsWith("image/")
                || f.getContentType().startsWith("text/plain"));
        Map<String, Object> out = map("url", links.fileLink(f.getId(), u.getId(), canInline), "name", f.getOriginalName(),
                "contentType", f.getContentType(), "size", f.getSizeBytes(), "expiresInSeconds", MaterialLinks.LIFETIME_SECONDS);
        if (officePreview.offered(f)) out.put("previewUrl", links.previewLink(f.getId(), u.getId()));
        return out;
    }

    @Transactional
    public Map<String, Object> zipLink(User u, Long courseId, int week) {
        Category c = course(courseId);
        CourseSpace s;
        if (u.getRole() == Role.NORMAL) {
            s = studentSpaceOrThrow(u, c);
        } else {
            access.requireOnFor(u, c);
            MaterialAccess.requireManage(u, c);
            s = spaceFor(c);
        }
        return map("url", links.zipLink(s.getId(), week, u.getId()));
    }

    /** A student recorded as having opened an item (links, typed notes). */
    @Transactional
    public void opened(User u, Long materialId) {
        CourseMaterial m = materials.findById(materialId).filter(x -> x.getDeletedAt() == null)
                .orElseThrow(() -> new IllegalArgumentException("Item not found."));
        requireStudentSees(u, m);
        recordView(u, m.getId());
    }

    void recordView(User u, Long materialId) {
        if (u.getRole() != Role.NORMAL) return;
        MaterialView v = views.findByMaterialIdAndStudentId(materialId, u.getId()).orElse(null);
        if (v == null) {
            v = new MaterialView();
            v.setMaterialId(materialId);
            v.setStudentId(u.getId());
        } else {
            v.setLastAt(LocalDateTime.now());
            v.setOpenCount(v.getOpenCount() + 1);
        }
        views.save(v);
    }

    // ══ Downloads (signed links, no sign-in header) ══════════════════════════

    public record Download(Path path, String name, String contentType, long size, boolean inline) {}

    /** The PDF view of a Word / PowerPoint file, made now if needed (signed link from linkFor). */
    public Download preview(String fileId, long userId, long exp, String sig) throws java.io.IOException {
        if (!links.previewLinkValid(fileId, userId, exp, sig))
            throw new AccessDeniedException("This link has expired. Go back and open the file again.");
        StoredFile f = files.findById(fileId).orElseThrow(() -> new IllegalArgumentException("File not found."));
        Path pdf = officePreview.pdf(f);
        String name = f.getOriginalName().replaceAll("\\.[^.]+$", "") + ".pdf";
        return new Download(pdf, name, "application/pdf", java.nio.file.Files.size(pdf), true);
    }

    @Transactional(readOnly = true)
    public Download download(String fileId, long userId, long exp, String inline, String sig) {
        if (!links.fileLinkValid(fileId, userId, exp, inline, sig))
            throw new AccessDeniedException("This download link has expired. Go back and tap the file again.");
        StoredFile f = files.findById(fileId).orElseThrow(() -> new IllegalArgumentException("File not found."));
        return new Download(storage.open(f), f.getOriginalName(), f.getContentType(), f.getSizeBytes(), "1".equals(inline));
    }

    public record ZipEntryFile(String name, Path path) {}
    public record ZipBundle(String name, List<ZipEntryFile> entries) {}

    @Transactional(readOnly = true)
    public ZipBundle zip(long spaceId, int week, long userId, long exp, String sig, User who) {
        if (!links.zipLinkValid(spaceId, week, userId, exp, sig) || who == null || !who.getId().equals(userId))
            throw new AccessDeniedException("This download link has expired. Go back and try again.");
        CourseSpace s = spaces.findById(spaceId).orElseThrow(() -> new IllegalArgumentException("Not found."));
        LocalDateTime now = LocalDateTime.now();
        Map<Integer, CourseWeek> rows = weekRows(s);
        boolean student = who.getRole() == Role.NORMAL;
        List<CourseMaterial> items = materials.live(s.getId()).stream().filter(m -> m.getWeekNumber() == week)
                .filter(m -> !student || studentSees(s, rows.get(m.getWeekNumber()), m, now)).toList();
        List<StoredFile> fs = items.isEmpty() ? List.of() : files.forMaterials(items.stream().map(CourseMaterial::getId).toList());
        Map<Long, String> titles = items.stream().collect(Collectors.toMap(CourseMaterial::getId, CourseMaterial::getTitle));
        Set<String> used = new HashSet<>();
        List<ZipEntryFile> entries = new ArrayList<>();
        for (StoredFile f : fs) {
            String folder = safe(titles.getOrDefault(f.getMaterial().getId(), "Item"));
            String name = folder + "/" + safe(f.getOriginalName());
            int n = 2;
            while (!used.add(name.toLowerCase(Locale.ROOT))) name = folder + "/(" + n++ + ") " + safe(f.getOriginalName());
            entries.add(new ZipEntryFile(name, storage.open(f)));
        }
        String code = nz(s.getCourse().getCourseCode()).isBlank() ? nz(s.getCourse().getTitle()) : s.getCourse().getCourseCode();
        return new ZipBundle(safe(code + " - " + (week == 0 ? "General" : "Week " + week)) + ".zip", entries);
    }

    /** Opening a week's ZIP counts as opening each of its items. */
    @Transactional
    public void recordZipViews(User u, long spaceId, int week) {
        if (u.getRole() != Role.NORMAL) return;
        CourseSpace s = spaces.findById(spaceId).orElse(null);
        if (s == null) return;
        LocalDateTime now = LocalDateTime.now();
        Map<Integer, CourseWeek> rows = weekRows(s);
        materials.live(s.getId()).stream().filter(m -> m.getWeekNumber() == week)
                .filter(m -> studentSees(s, rows.get(week), m, now)).forEach(m -> recordView(u, m.getId()));
    }

    private static String safe(String s) {
        String x = s == null ? "" : s.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ").trim();
        if (x.length() > 120) x = x.substring(0, 120).trim();
        return x.isEmpty() ? "file" : x;
    }

    // ══ Students ═════════════════════════════════════════════════════════════

    /** The student's courses this session that have materials for them. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> studentCourses(User u) {
        access.requireOnForUser(u);
        AcademicSession session = academicSessions.current();
        List<Category> mine = spaces.coursesOf(u.getId(), session.getId()).stream().filter(access::onForCourse)
                .sorted(Comparator.comparing((Category c) -> nz(c.getCourseCode())).thenComparing(c -> nz(c.getTitle()))).toList();
        if (mine.isEmpty()) return List.of();
        StudentScope scope = studentScope(u, mine, session);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Category c : mine) {
            CourseSpace s = scope.spaceByCourse.get(c.getCid());
            List<CourseMaterial> items = scope.visibleByCourse.getOrDefault(c.getCid(), List.of());
            long fresh = items.stream().filter(m -> scope.isNew(m)).count();
            long due = items.stream().filter(CourseMaterial::isAssignment)
                    .filter(m -> isOpen(m, LocalDateTime.now()) && !scope.submitted.contains(m.getId())).count();
            out.add(map("courseId", c.getCid(), "title", c.getTitle(), "code", c.getCourseCode(),
                    "lecturer", CurrentUserService.displayName(c.getUser()),
                    "items", items.stream().filter(m -> !m.isAssignment()).count(),
                    "assignments", items.stream().filter(CourseMaterial::isAssignment).count(),
                    "newItems", fresh, "toHandIn", due,
                    "assignmentTotal", assignmentTotal(items, scope),
                    "hiddenReason", s == null ? null : scope.hiddenReason.get(c.getCid())));
        }
        return out;
    }

    /** Dashboard card: what's new this week and what's due soon. */
    @Transactional(readOnly = true)
    public Map<String, Object> studentOverview(User u) {
        access.requireOnForUser(u);
        AcademicSession session = academicSessions.current();
        List<Category> mine = spaces.coursesOf(u.getId(), session.getId()).stream().filter(access::onForCourse).toList();
        if (mine.isEmpty()) return map("newItems", List.of(), "dueSoon", List.of());
        StudentScope scope = studentScope(u, mine, session);
        LocalDateTime now = LocalDateTime.now();
        List<Map<String, Object>> fresh = new ArrayList<>();
        List<Map<String, Object>> due = new ArrayList<>();
        for (Category c : mine) {
            for (CourseMaterial m : scope.visibleByCourse.getOrDefault(c.getCid(), List.of())) {
                if (scope.isNew(m)) fresh.add(map("id", m.getId(), "courseId", c.getCid(), "courseCode", c.getCourseCode(),
                        "courseTitle", c.getTitle(), "title", m.getTitle(), "kind", m.getKind().name(), "weekNumber", m.getWeekNumber(),
                        "publishedAt", published(m)));
                if (m.isAssignment() && m.getDueAt() != null && isOpen(m, now) && m.getDueAt().isBefore(now.plusDays(14)))
                    due.add(map("id", m.getId(), "courseId", c.getCid(), "courseCode", c.getCourseCode(), "courseTitle", c.getTitle(),
                            "title", m.getTitle(), "dueAt", m.getDueAt(), "closesAt", m.getClosesAt(),
                            "submitted", scope.submitted.contains(m.getId())));
            }
        }
        fresh.sort(Comparator.comparing((Map<String, Object> x) -> (LocalDateTime) x.get("publishedAt")).reversed());
        due.sort(Comparator.comparing(x -> (LocalDateTime) x.get("dueAt")));
        return map("newItems", fresh.stream().limit(8).toList(), "dueSoon", due.stream().limit(8).toList());
    }

    /** One course's materials for a student. */
    @Transactional(readOnly = true)
    public Map<String, Object> studentSpace(User u, Long courseId) {
        Category c = course(courseId);
        access.requireOnFor(u, c);
        AcademicSession session = academicSessions.current();
        if (spaces.registrations(c.getCid(), u.getId(), session.getId()) == 0)
            throw new AccessDeniedException("You aren't registered for this course.");
        StudentScope scope = studentScope(u, List.of(c), session);
        CourseSpace s = scope.spaceByCourse.get(c.getCid());
        Map<String, Object> out = map("course", courseInfo(c), "session", session.getName());
        String hidden = scope.hiddenReason.get(c.getCid());
        out.put("hiddenReason", hidden);
        if (s == null || hidden != null) {
            out.put("weeks", List.of());
            out.put("currentWeek", s == null ? 0 : calendar.currentWeek(s, LocalDate.now()));
            return out;
        }
        List<CourseMaterial> items = scope.visibleByCourse.getOrDefault(c.getCid(), List.of());
        Map<Long, List<StoredFile>> fileMap = filesFor(items);
        Map<Long, AssignmentSubmission> mine = scope.submissions;
        Map<Integer, CourseWeek> rows = weekRows(s);
        LocalDate one = calendar.weekOneMonday(s);
        LocalDateTime now = LocalDateTime.now();
        Map<Integer, List<CourseMaterial>> byWeek = items.stream().collect(Collectors.groupingBy(CourseMaterial::getWeekNumber, TreeMap::new, Collectors.toList()));
        List<Map<String, Object>> weekList = new ArrayList<>();
        for (Map.Entry<Integer, List<CourseMaterial>> e : byWeek.entrySet()) {
            int n = e.getKey();
            CourseWeek w = rows.get(n);
            LocalDate start = MaterialCalendar.weekStart(one, n);
            weekList.add(map("number", n, "topic", w == null ? null : w.getTopic(),
                    "start", start, "end", start == null ? null : start.plusDays(6),
                    "items", e.getValue().stream().map(m -> studentItem(m, fileMap.getOrDefault(m.getId(), List.of()),
                            mine.get(m.getId()), scope, now)).toList()));
        }
        out.put("weeks", weekList);
        out.put("currentWeek", calendar.currentWeek(s, LocalDate.now()));
        out.put("myMarks", Gradebook.forStudent(items, mine, now));
        return out;
    }

    private static Map<String, Object> assignmentTotal(List<CourseMaterial> items, StudentScope scope) {
        Map<String, Object> t = Gradebook.forStudent(items, scope.submissions, LocalDateTime.now());
        return ((BigDecimal) t.get("outOf")).signum() == 0 ? null : map("total", t.get("total"), "outOf", t.get("outOf"), "percent", t.get("percent"));
    }

    Map<String, Object> studentItem(CourseMaterial m, List<StoredFile> fs, AssignmentSubmission sub, StudentScope scope, LocalDateTime now) {
        Map<String, Object> x = map("id", m.getId(), "kind", m.getKind().name(), "title", m.getTitle(), "description", m.getDescription(),
                "linkUrl", m.getLinkUrl(), "pinned", m.isPinned(), "publishedAt", published(m),
                "isNew", scope.isNew(m), "opened", scope.opened.contains(m.getId()),
                "files", fs.stream().map(MaterialService::fileInfo).toList());
        if (m.isAssignment()) x.put("assignment", studentAssignment(m, sub, now));
        return x;
    }

    Map<String, Object> studentAssignment(CourseMaterial m, AssignmentSubmission sub, LocalDateTime now) {
        Map<String, Object> a = map("dueAt", m.getDueAt(), "closesAt", m.getClosesAt(), "open", isOpen(m, now),
                "reopened", m.getClosesAt() != null && m.getDueAt() != null && m.getClosesAt().isAfter(m.getDueAt()),
                "maxMarks", m.getMaxMarks(), "allowTyped", m.isAllowTyped(), "allowFiles", m.isAllowFiles(),
                "marksReleased", m.isMarksReleased());
        if (sub != null && sub.getSubmittedAt() != null) {
            a.put("submittedAt", sub.getSubmittedAt());
            a.put("late", sub.isLate());
        }
        if (sub != null && m.isMarksReleased() && sub.getScore() != null) {
            a.put("score", sub.getScore());
            a.put("feedback", sub.getFeedback());
        }
        a.put("status", sub == null || sub.getSubmittedAt() == null ? (isOpen(m, now) ? "TO_DO" : "MISSED")
                : (m.isMarksReleased() && sub.getScore() != null ? "MARKED" : "HANDED_IN"));
        return a;
    }

    /** What one student can see across some courses, computed once. */
    final class StudentScope {
        final Map<Long, CourseSpace> spaceByCourse = new HashMap<>();
        final Map<Long, List<CourseMaterial>> visibleByCourse = new HashMap<>();
        final Map<Long, String> hiddenReason = new HashMap<>();
        Set<Long> opened = Set.of();
        Map<Long, AssignmentSubmission> submissions = Map.of();
        Set<Long> submitted = Set.of();

        boolean isNew(CourseMaterial m) {
            LocalDateTime p = published(m);
            return !opened.contains(m.getId()) && p != null && p.isAfter(LocalDateTime.now().minusDays(7))
                    && !(m.isAssignment() && submitted.contains(m.getId()));
        }
    }

    StudentScope studentScope(User u, List<Category> courses, AcademicSession session) {
        StudentScope scope = new StudentScope();
        LocalDateTime now = LocalDateTime.now();
        for (CourseSpace s : spaces.findByCourse_CidIn(courses.stream().map(Category::getCid).toList()))
            if (s.getAcademicSession().getId().equals(session.getId())) scope.spaceByCourse.put(s.getCourse().getCid(), s);
        List<Long> visibleSpaceIds = new ArrayList<>();
        for (Category c : courses) {
            CourseSpace s = scope.spaceByCourse.get(c.getCid());
            if (s == null) continue;
            if (!s.isVisibleToStudents()) { scope.hiddenReason.put(c.getCid(), "HIDDEN"); continue; }
            if (s.isHideDuringQuiz() && spaces.liveAttempts(u.getId(), c.getCid(), now.minusHours(6)) > 0) {
                scope.hiddenReason.put(c.getCid(), "QUIZ");
                continue;
            }
            visibleSpaceIds.add(s.getId());
        }
        if (visibleSpaceIds.isEmpty()) return scope;
        Map<Long, Map<Integer, CourseWeek>> rowsBySpace = new HashMap<>();
        for (CourseWeek w : weeks.findBySpace_IdIn(visibleSpaceIds))
            rowsBySpace.computeIfAbsent(w.getSpace().getId(), k -> new HashMap<>()).put(w.getNumber(), w);
        List<CourseMaterial> all = new ArrayList<>();
        for (CourseMaterial m : materials.liveIn(visibleSpaceIds)) {
            CourseWeek w = rowsBySpace.getOrDefault(m.getSpace().getId(), Map.of()).get(m.getWeekNumber());
            if (!studentSees(m.getSpace(), w, m, now)) continue;
            all.add(m);
            scope.visibleByCourse.computeIfAbsent(m.getSpace().getCourse().getCid(), k -> new ArrayList<>()).add(m);
        }
        scope.visibleByCourse.values().forEach(l -> l.sort(Comparator.comparingInt(CourseMaterial::getWeekNumber)
                .thenComparing(CourseMaterial::isPinned, Comparator.reverseOrder())
                .thenComparingInt(CourseMaterial::getSortOrder).thenComparing(CourseMaterial::getId)));
        if (all.isEmpty()) return scope;
        List<Long> ids = all.stream().map(CourseMaterial::getId).toList();
        scope.opened = new HashSet<>(views.openedBy(u.getId(), ids));
        List<Long> assignmentIds = all.stream().filter(CourseMaterial::isAssignment).map(CourseMaterial::getId).toList();
        if (!assignmentIds.isEmpty()) {
            scope.submissions = submissions.mine(u.getId(), assignmentIds).stream()
                    .collect(Collectors.toMap(s -> s.getAssignment().getId(), s -> s, (a, b) -> a));
            scope.submitted = scope.submissions.values().stream().filter(s -> s.getSubmittedAt() != null)
                    .map(s -> s.getAssignment().getId()).collect(Collectors.toSet());
        }
        return scope;
    }

    /** The student may see this item now (module, feature, registration, course switch, quiz, week, item). */
    public void requireStudentSees(User u, CourseMaterial m) {
        if (m.getDeletedAt() != null) throw new IllegalArgumentException("This item has been removed.");
        Category c = m.getSpace().getCourse();
        access.requireOnFor(u, c);
        AcademicSession session = academicSessions.current();
        if (!m.getSpace().getAcademicSession().getId().equals(session.getId())
                || spaces.registrations(c.getCid(), u.getId(), session.getId()) == 0)
            throw new AccessDeniedException("You aren't registered for this course.");
        CourseSpace s = m.getSpace();
        if (!s.isVisibleToStudents()) throw new AccessDeniedException("Your lecturer has hidden this course's materials.");
        if (s.isHideDuringQuiz() && spaces.liveAttempts(u.getId(), c.getCid(), LocalDateTime.now().minusHours(6)) > 0)
            throw new AccessDeniedException("This course's materials are hidden while you are taking one of its quizzes.");
        CourseWeek w = weeks.findBySpace_IdAndNumber(s.getId(), m.getWeekNumber()).orElse(null);
        if (!studentSees(s, w, m, LocalDateTime.now())) throw new AccessDeniedException("This item isn't available yet.");
    }

    private CourseSpace studentSpaceOrThrow(User u, Category c) {
        access.requireOnFor(u, c);
        AcademicSession session = academicSessions.current();
        if (spaces.registrations(c.getCid(), u.getId(), session.getId()) == 0)
            throw new AccessDeniedException("You aren't registered for this course.");
        CourseSpace s = spaces.findByCourse_CidAndAcademicSession_Id(c.getCid(), session.getId())
                .orElseThrow(() -> new IllegalArgumentException("No materials yet."));
        if (!s.isVisibleToStudents()) throw new AccessDeniedException("Your lecturer has hidden this course's materials.");
        if (s.isHideDuringQuiz() && spaces.liveAttempts(u.getId(), c.getCid(), LocalDateTime.now().minusHours(6)) > 0)
            throw new AccessDeniedException("This course's materials are hidden while you are taking one of its quizzes.");
        return s;
    }

    // ══ Visibility rules ═════════════════════════════════════════════════════

    public static boolean weekOpen(CourseWeek w, LocalDateTime now) {
        if (w == null) return true;
        return switch (w.getState()) {
            case OPEN -> true;
            case HIDDEN -> false;
            case SCHEDULED -> w.getReleaseAt() != null && !w.getReleaseAt().isAfter(now);
        };
    }

    public static boolean itemVisible(CourseMaterial m, LocalDateTime now) {
        return switch (m.getVisibility()) {
            case VISIBLE -> true;
            case SCHEDULED -> m.getVisibleFrom() != null && !m.getVisibleFrom().isAfter(now);
            default -> false;
        };
    }

    /** Course switch + week + item (the quiz rule and registration are checked by the callers). */
    public static boolean studentSees(CourseSpace s, CourseWeek w, CourseMaterial m, LocalDateTime now) {
        return m.getDeletedAt() == null && s.isVisibleToStudents() && weekOpen(w, now) && itemVisible(m, now);
    }

    /** Submissions are taken until closesAt (no closing time = open). */
    public static boolean isOpen(CourseMaterial m, LocalDateTime now) {
        return m.getClosesAt() == null || now.isBefore(m.getClosesAt());
    }

    /** When students could first see it, as far as we know. */
    static LocalDateTime published(CourseMaterial m) {
        if (m.getPublishedAt() != null) return m.getPublishedAt();
        if (m.getVisibility() == CourseMaterial.Visibility.SCHEDULED && m.getVisibleFrom() != null) return m.getVisibleFrom();
        return m.getUpdatedAt() != null ? m.getUpdatedAt() : m.getCreatedAt();
    }

    /**
     * Why students can't see an item yet (null = they can): COURSE_HIDDEN, WEEK_HIDDEN, WEEK_SCHEDULED,
     * DRAFT, HIDDEN or SCHEDULED (the last two scheduled ones with the time in hiddenUntil).
     */
    private static String hiddenBecause(CourseSpace s, CourseWeek w, CourseMaterial m, LocalDateTime now) {
        if (!s.isVisibleToStudents()) return "COURSE_HIDDEN";
        if (w != null && w.getState() == CourseWeek.State.HIDDEN) return "WEEK_HIDDEN";
        if (w != null && w.getState() == CourseWeek.State.SCHEDULED && (w.getReleaseAt() == null || w.getReleaseAt().isAfter(now)))
            return "WEEK_SCHEDULED";
        return switch (m.getVisibility()) {
            case DRAFT -> "DRAFT";
            case HIDDEN -> "HIDDEN";
            case SCHEDULED -> m.getVisibleFrom() == null || m.getVisibleFrom().isAfter(now) ? "SCHEDULED" : null;
            case VISIBLE -> null;
        };
    }

    private static LocalDateTime hiddenUntil(CourseWeek w, CourseMaterial m, LocalDateTime now) {
        LocalDateTime a = w != null && w.getState() == CourseWeek.State.SCHEDULED ? w.getReleaseAt() : null;
        LocalDateTime b = m.getVisibility() == CourseMaterial.Visibility.SCHEDULED ? m.getVisibleFrom() : null;
        if (a != null && !a.isAfter(now)) a = null;
        if (b != null && !b.isAfter(now)) b = null;
        if (a == null) return b;
        if (b == null) return a;
        return a.isAfter(b) ? a : b;
    }

    // ══ Staff view ═══════════════════════════════════════════════════════════

    Map<String, Object> staffView(CourseSpace s) {
        Category c = s.getCourse();
        LocalDateTime now = LocalDateTime.now();
        Map<Integer, CourseWeek> rows = weekRows(s);
        List<CourseMaterial> items = materials.live(s.getId());
        Map<Long, List<StoredFile>> fileMap = filesFor(items);
        List<Long> ids = items.stream().map(CourseMaterial::getId).toList();
        Map<Long, Long> openCounts = counts(ids.isEmpty() ? List.of() : views.countsFor(ids));
        List<Long> assignmentIds = items.stream().filter(CourseMaterial::isAssignment).map(CourseMaterial::getId).toList();
        Map<Long, Long> handedIn = counts(assignmentIds.isEmpty() ? List.of() : submissions.countsFor(assignmentIds));
        Map<Long, Long> marked = counts(assignmentIds.isEmpty() ? List.of() : submissions.markedCountsFor(assignmentIds));
        int rosterSize = roster(s).size();

        LocalDate one = calendar.weekOneMonday(s);
        int count = calendar.weeksCount(s);
        int highest = Math.max(count, items.stream().mapToInt(CourseMaterial::getWeekNumber).max().orElse(0));
        highest = Math.max(highest, rows.keySet().stream().mapToInt(Integer::intValue).max().orElse(0));
        Map<Integer, List<CourseMaterial>> byWeek = items.stream().collect(Collectors.groupingBy(CourseMaterial::getWeekNumber));

        List<Map<String, Object>> weekList = new ArrayList<>();
        for (int n = 0; n <= highest; n++) {
            CourseWeek w = rows.get(n);
            LocalDate start = MaterialCalendar.weekStart(one, n);
            weekList.add(map("number", n, "topic", w == null ? null : w.getTopic(),
                    "state", w == null ? "OPEN" : w.getState().name(), "releaseAt", w == null ? null : w.getReleaseAt(),
                    "open", weekOpen(w, now), "start", start, "end", start == null ? null : start.plusDays(6),
                    "items", byWeek.getOrDefault(n, List.of()).stream().map(m -> {
                        Map<String, Object> x = map("id", m.getId(), "kind", m.getKind().name(), "weekNumber", m.getWeekNumber(),
                                "title", m.getTitle(), "description", m.getDescription(), "linkUrl", m.getLinkUrl(),
                                "visibility", m.getVisibility().name(), "visibleFrom", m.getVisibleFrom(), "pinned", m.isPinned(),
                                "studentsSee", studentsSeeNow(s, w, m, now), "hiddenBecause", hiddenBecause(s, w, m, now),
                                "hiddenUntil", hiddenUntil(w, m, now),
                                "publishedAt", m.getPublishedAt(), "createdBy", m.getCreatedByName(),
                                "updatedAt", m.getUpdatedAt() != null ? m.getUpdatedAt() : m.getCreatedAt(),
                                "opened", openCounts.getOrDefault(m.getId(), 0L),
                                "files", fileMap.getOrDefault(m.getId(), List.of()).stream().map(MaterialService::fileInfo).toList());
                        if (m.isAssignment()) x.put("assignment", map("dueAt", m.getDueAt(), "closesAt", m.getClosesAt(),
                                "open", isOpen(m, now), "maxMarks", m.getMaxMarks(), "markingGuide", m.getMarkingGuide(),
                                "aiProvider", m.getAiProvider() == null ? null : m.getAiProvider().name(),
                                "allowTyped", m.isAllowTyped(), "allowFiles", m.isAllowFiles(), "marksReleased", m.isMarksReleased(),
                                "handedIn", handedIn.getOrDefault(m.getId(), 0L), "marked", marked.getOrDefault(m.getId(), 0L)));
                        return x;
                    }).toList()));
        }

        var term = calendar.termFor(s);
        Map<String, Object> settings = map("visibleToStudents", s.isVisibleToStudents(), "hideDuringQuiz", s.isHideDuringQuiz(),
                "weekOneStart", s.getWeekOneStart(), "weeksCount", s.getWeeksCount(),
                "usesTermDates", s.getWeekOneStart() == null && s.getWeeksCount() == null,
                "termStart", term == null ? null : term.getStartDate(), "termWeeks", term == null ? null : term.getWeeks(),
                "weekOneMonday", one, "weeks", count,
                "autoRelease", s.isAutoRelease(), "autoReleaseDay", s.getAutoReleaseDay() == null ? "MONDAY" : s.getAutoReleaseDay().name(),
                "autoReleaseTime", s.getAutoReleaseTime() == null ? LocalTime.of(8, 0) : s.getAutoReleaseTime());

        return map("course", courseInfo(c), "spaceId", s.getId(), "session", s.getAcademicSession().getName(),
                "settings", settings, "currentWeek", calendar.currentWeek(s, LocalDate.now()), "rosterSize", rosterSize,
                "weeks", weekList,
                "limits", map("maxFileMb", storage.maxFileMb(), "accepted", new TreeSet<>(MaterialStorage.MATERIAL_TYPES),
                        "submissionAccepted", new TreeSet<>(MaterialStorage.SUBMISSION_TYPES), "maxFiles", MAX_FILES_PER_UPLOAD),
                "aiProviders", ai.providers(),
                "binCount", materials.bin(s.getId()).size());
    }

    private static boolean studentsSeeNow(CourseSpace s, CourseWeek w, CourseMaterial m, LocalDateTime now) {
        return studentSees(s, w, m, now);
    }

    // ══ Helpers ══════════════════════════════════════════════════════════════

    private void apply(CourseMaterial m, MaterialRequest r, boolean creating) {
        if (r.weekNumber() != null) {
            if (r.weekNumber() < 0 || r.weekNumber() > MaterialCalendar.MAX_WEEKS) throw new IllegalArgumentException("Choose a week between 0 and " + MaterialCalendar.MAX_WEEKS + ".");
            m.setWeekNumber(r.weekNumber());
        }
        if (r.title() != null || creating) {
            String t = r.title() == null ? "" : r.title().trim();
            if (t.isEmpty()) throw new IllegalArgumentException("Give it a title.");
            if (t.length() > 200) throw new IllegalArgumentException("Keep the title under 200 characters.");
            m.setTitle(t);
        }
        if (r.description() != null) {
            String d = r.description().strip();
            if (d.length() > 20_000) throw new IllegalArgumentException("The description is too long (20,000 characters at most).");
            m.setDescription(d.isEmpty() ? null : d);
        }
        if (m.getKind() == CourseMaterial.Kind.LINK && (r.linkUrl() != null || creating)) m.setLinkUrl(cleanUrl(r.linkUrl()));
        if (r.pinned() != null) m.setPinned(r.pinned());
        if (m.isAssignment()) {
            if (r.maxMarks() != null || creating) {
                BigDecimal mm = r.maxMarks() == null ? BigDecimal.TEN : r.maxMarks();
                if (mm.signum() <= 0 || mm.compareTo(BigDecimal.valueOf(1000)) > 0) throw new IllegalArgumentException("Marks must be between 0 and 1000.");
                m.setMaxMarks(mm);
            }
            if (r.markingGuide() != null) {
                String g = r.markingGuide().strip();
                if (g.length() > 20_000) throw new IllegalArgumentException("The marking guide is too long (20,000 characters at most).");
                m.setMarkingGuide(g.isEmpty() ? null : g);
            }
            if (r.aiProvider() != null) m.setAiProvider(r.aiProvider().isBlank() ? null : parse(LlmProvider.class, r.aiProvider(), "AI"));
            if (r.allowTyped() != null) m.setAllowTyped(r.allowTyped());
            if (r.allowFiles() != null) m.setAllowFiles(r.allowFiles());
            if (!m.isAllowTyped() && !m.isAllowFiles()) throw new IllegalArgumentException("Let students type their answer, upload files, or both.");
            if (r.dueAt() != null) {
                LocalDateTime oldDue = m.getDueAt();
                if (creating && !r.dueAt().isAfter(LocalDateTime.now())) throw new IllegalArgumentException("The due date must be in the future.");
                m.setDueAt(r.dueAt());
                // Closing follows the due date unless the lecturer has reopened it beyond that
                if (m.getClosesAt() == null || oldDue == null || !m.getClosesAt().isAfter(oldDue) || m.getClosesAt().isBefore(r.dueAt())) {
                    m.setClosesAt(r.dueAt());
                    m.setClosedNotified(false);
                }
                if (oldDue == null || !oldDue.equals(r.dueAt())) m.setReminderSent(false);
            } else if (creating) {
                throw new IllegalArgumentException("Choose the due date.");
            }
        }
        if (r.visibility() != null || creating) {
            CourseMaterial.Visibility v = r.visibility() == null ? CourseMaterial.Visibility.VISIBLE
                    : parse(CourseMaterial.Visibility.class, r.visibility(), "visibility");
            applyVisibility(m, v, r.visibleFrom());
        } else if (r.visibleFrom() != null && m.getVisibility() == CourseMaterial.Visibility.SCHEDULED) {
            m.setVisibleFrom(r.visibleFrom());
        }
        m.setUpdatedAt(LocalDateTime.now());
    }

    private void applyVisibility(CourseMaterial m, CourseMaterial.Visibility v, LocalDateTime from) {
        if (v == CourseMaterial.Visibility.SCHEDULED) {
            if (from == null) throw new IllegalArgumentException("Choose when students will see it.");
            m.setVisibleFrom(from);
        } else {
            m.setVisibleFrom(null);
        }
        if ((v == CourseMaterial.Visibility.VISIBLE || v == CourseMaterial.Visibility.SCHEDULED) && m.isAssignment() && m.getDueAt() == null)
            throw new IllegalArgumentException("Set the due date before showing this assignment to students.");
        m.setVisibility(v);
    }

    private void attach(User u, CourseMaterial m, List<MultipartFile> uploads) {
        for (StoredFile f : storeAll(u, uploads)) {
            f.setMaterial(m);
            files.save(f);
            officePreview.warm(f);
        }
    }

    /** Checks and stores every upload first, so one refused file leaves no item half made. */
    private List<StoredFile> storeAll(User u, List<MultipartFile> uploads) {
        if (uploads == null) return List.of();
        List<MultipartFile> real = uploads.stream().filter(f -> f != null && !f.isEmpty()).toList();
        if (real.size() > MAX_FILES_PER_UPLOAD) throw new IllegalArgumentException("Upload at most " + MAX_FILES_PER_UPLOAD + " files at a time.");
        List<StoredFile> out = new ArrayList<>();
        for (MultipartFile up : real) out.add(storage.store(up, MaterialStorage.MATERIAL_TYPES, StoredFile.Role.ATTACHMENT, u.getId()));
        return out;
    }

    static String cleanUrl(String raw) {
        String u = raw == null ? "" : raw.trim();
        if (u.isEmpty()) throw new IllegalArgumentException("Paste the link's address.");
        if (!u.matches("(?i)^https?://.*")) u = "https://" + u;
        if (u.length() > 1000) throw new IllegalArgumentException("That address is too long.");
        try {
            URI uri = URI.create(u);
            if (uri.getHost() == null || !uri.getHost().contains(".")) throw new IllegalArgumentException();
        } catch (Exception e) {
            throw new IllegalArgumentException("That doesn't look like a web address.");
        }
        return u;
    }

    Category course(Long id) {
        return categories.findById(id).orElseThrow(() -> new IllegalArgumentException("Course not found."));
    }

    /** The course's space for the current session, created on first use. */
    CourseSpace spaceFor(Category c) {
        AcademicSession session = academicSessions.current();
        return spaces.findByCourse_CidAndAcademicSession_Id(c.getCid(), session.getId()).orElseGet(() -> {
            CourseSpace s = new CourseSpace();
            s.setCourse(c);
            s.setAcademicSession(session);
            return spaces.save(s);
        });
    }

    private CourseSpace managedSpace(User u, Long courseId) {
        Category c = course(courseId);
        access.requireOnFor(u, c);
        MaterialAccess.requireManage(u, c);
        return spaceFor(c);
    }

    private CourseSpace managedSpaceReadOnly(User u, Long courseId) {
        Category c = course(courseId);
        access.requireOnFor(u, c);
        MaterialAccess.requireManage(u, c);
        return spaces.findByCourse_CidAndAcademicSession_Id(c.getCid(), academicSessions.current().getId()).orElse(null);
    }

    CourseMaterial managedItem(User u, Long id) {
        CourseMaterial m = materials.findById(id).filter(x -> x.getDeletedAt() == null)
                .orElseThrow(() -> new IllegalArgumentException("Item not found."));
        access.requireOnFor(u, m.getSpace().getCourse());
        MaterialAccess.requireManage(u, m.getSpace().getCourse());
        return m;
    }

    Map<Integer, CourseWeek> weekRows(CourseSpace s) {
        return weeks.findBySpace_IdOrderByNumber(s.getId()).stream()
                .collect(Collectors.toMap(CourseWeek::getNumber, w -> w, (a, b) -> a));
    }

    List<User> roster(CourseSpace s) {
        return rosters.courseRoster(s.getCourse().getCid(), s.getAcademicSession().getId());
    }

    Map<Long, List<StoredFile>> filesFor(List<CourseMaterial> items) {
        if (items.isEmpty()) return Map.of();
        return files.forMaterials(items.stream().map(CourseMaterial::getId).toList()).stream()
                .collect(Collectors.groupingBy(f -> f.getMaterial().getId()));
    }

    static Map<String, Object> fileInfo(StoredFile f) {
        return map("id", f.getId(), "name", f.getOriginalName(), "size", f.getSizeBytes(), "contentType", f.getContentType(),
                "role", f.getRole().name());
    }

    static Map<String, Object> courseInfo(Category c) {
        return map("id", c.getCid(), "title", c.getTitle(), "code", c.getCourseCode(), "level", c.getLevel(),
                "semester", c.getSemester(), "lecturer", CurrentUserService.displayName(c.getUser()));
    }

    private static Map<Long, Long> counts(List<Object[]> rows) {
        Map<Long, Long> m = new HashMap<>();
        for (Object[] r : rows) m.put(((Number) r[0]).longValue(), ((Number) r[1]).longValue());
        return m;
    }

    static DayOfWeek parseDay(String s) {
        try { return DayOfWeek.valueOf(s.trim().toUpperCase(Locale.ROOT)); }
        catch (Exception e) { throw new IllegalArgumentException("Choose a day of the week."); }
    }

    static <E extends Enum<E>> E parse(Class<E> type, String v, String what) {
        try { return Enum.valueOf(type, v.trim().toUpperCase(Locale.ROOT)); }
        catch (Exception e) { throw new IllegalArgumentException("Unknown " + what + ": " + v); }
    }

    private static String nz(String s) { return s == null ? "" : s; }

    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
