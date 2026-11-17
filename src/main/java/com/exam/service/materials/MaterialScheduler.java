package com.exam.service.materials;

import com.exam.model.User;
import com.exam.model.materials.AssignmentSubmission;
import com.exam.model.materials.CourseMaterial;
import com.exam.model.materials.CourseSpace;
import com.exam.model.materials.CourseWeek;
import com.exam.repository.materials.AssignmentSubmissionRepository;
import com.exam.repository.materials.CourseMaterialRepository;
import com.exam.repository.materials.CourseWeekRepository;
import com.exam.service.SystemSettingService;
import com.exam.service.academic.AcademicSessionService;
import com.exam.service.comms.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Every minute: tells students about items that have just become visible (straight away or at their
 * scheduled time, one message per course), reminds them a day before an assignment is due, and tells
 * the lecturer when submissions close. Every few hours: empties recycle-bin items older than 30 days.
 */
@Component
public class MaterialScheduler {

    private static final Logger log = LoggerFactory.getLogger(MaterialScheduler.class);
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE d MMM, h:mm a", Locale.ENGLISH);

    @Autowired private SystemSettingService settings;
    @Autowired private CourseMaterialRepository materials;
    @Autowired private CourseWeekRepository weeks;
    @Autowired private AssignmentSubmissionRepository submissions;
    @Autowired private MaterialService materialService;
    @Autowired private MaterialAccess access;
    @Autowired private MaterialStorage storage;
    @Autowired private AcademicSessionService academicSessions;
    @Autowired private NotificationService notifications;

    @Scheduled(fixedDelay = 60_000, initialDelay = 50_000)
    public void everyMinute() {
        if (!settings.materialsOn()) return;
        try { announce(); } catch (Exception e) { log.warn("[Materials] Announcing new items failed: {}", e.getMessage()); }
        try { remindDue(); } catch (Exception e) { log.warn("[Materials] Due reminders failed: {}", e.getMessage()); }
        try { reportClosed(); } catch (Exception e) { log.warn("[Materials] Closing reports failed: {}", e.getMessage()); }
    }

    @Scheduled(fixedDelay = 6 * 3600_000L, initialDelay = 300_000)
    public void housekeeping() {
        try {
            int n = materialService.purgeOldBin();
            if (n > 0) log.info("[Materials] Emptied {} item(s) from recycle bins after 30 days", n);
        } catch (Exception e) {
            log.warn("[Materials] Emptying recycle bins failed: {}", e.getMessage());
        }
        // Uploads cut off half-way leave their part files behind
        Path tmp = storage.root().resolve("tmp");
        if (Files.isDirectory(tmp)) {
            Instant dayAgo = Instant.now().minus(1, ChronoUnit.DAYS);
            try (Stream<Path> parts = Files.list(tmp)) {
                parts.filter(p -> {
                    try { return Files.getLastModifiedTime(p).toInstant().isBefore(dayAgo); } catch (IOException e) { return false; }
                }).forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) { } });
            } catch (IOException ignored) { }
        }
    }

    @Transactional
    public void announce() {
        LocalDateTime now = LocalDateTime.now();
        Long sessionId = academicSessions.current().getId();
        List<CourseMaterial> waiting = materials.waitingToAnnounce(CourseMaterial.Visibility.VISIBLE, CourseMaterial.Visibility.SCHEDULED, now);
        if (waiting.isEmpty()) return;
        Map<Long, List<CourseMaterial>> bySpace = waiting.stream().collect(Collectors.groupingBy(m -> m.getSpace().getId()));
        for (List<CourseMaterial> items : bySpace.values()) {
            CourseSpace s = items.get(0).getSpace();
            if (!s.getAcademicSession().getId().equals(sessionId)) {        // an older term: never announce
                items.forEach(m -> { m.setNotified(true); materials.save(m); });
                continue;
            }
            if (!s.isVisibleToStudents() || !access.onForCourse(s.getCourse())) continue;   // told once it's switched on
            Map<Integer, CourseWeek> rows = weeks.findBySpace_IdOrderByNumber(s.getId()).stream()
                    .collect(Collectors.toMap(CourseWeek::getNumber, w -> w, (a, b) -> a));
            List<CourseMaterial> out = items.stream().filter(m -> MaterialService.studentSees(s, rows.get(m.getWeekNumber()), m, now)).toList();
            if (out.isEmpty()) continue;
            for (CourseMaterial m : out) {
                m.setNotified(true);
                if (m.getPublishedAt() == null) m.setPublishedAt(now);
                materials.save(m);
            }
            List<User> roster = materialService.roster(s);
            String course = s.getCourse().getCourseCode() != null && !s.getCourse().getCourseCode().isBlank()
                    ? s.getCourse().getCourseCode() : s.getCourse().getTitle();
            String link = "/user-dashboard/materials/" + s.getCourse().getCid();
            if (out.size() == 1) {
                CourseMaterial m = out.get(0);
                String what = switch (m.getKind()) {
                    case ASSIGNMENT -> "New assignment";
                    case LINK -> "New link";
                    case NOTE -> "New notes";
                };
                String due = m.isAssignment() && m.getDueAt() != null ? " Due " + m.getDueAt().format(WHEN) + "." : "";
                notifications.notify(roster, m.isAssignment() ? "ASSIGNMENT" : "MATERIAL", what + " in " + course,
                        "\"" + m.getTitle() + "\"" + weekLabel(m.getWeekNumber(), rows) + "." + due, link + "?item=" + m.getId());
            } else {
                Set<Integer> wk = out.stream().map(CourseMaterial::getWeekNumber).collect(Collectors.toCollection(TreeSet::new));
                long assignments = out.stream().filter(CourseMaterial::isAssignment).count();
                String where = wk.size() == 1 ? weekLabel(wk.iterator().next(), rows) : "";
                notifications.notify(roster, assignments > 0 ? "ASSIGNMENT" : "MATERIAL", "New in " + course,
                        out.size() + " new item" + (out.size() == 1 ? "" : "s") + where
                                + (assignments > 0 ? ", including " + assignments + " assignment" + (assignments == 1 ? "" : "s") : "") + ".",
                        link);
            }
        }
    }

    private static String weekLabel(int week, Map<Integer, CourseWeek> rows) {
        if (week == 0) return " (General)";
        CourseWeek w = rows.get(week);
        return " (week " + week + (w != null && w.getTopic() != null ? ": " + w.getTopic() : "") + ")";
    }

    /** A day before the due date: students who haven't handed in yet. */
    @Transactional
    public void remindDue() {
        LocalDateTime now = LocalDateTime.now();
        for (CourseMaterial m : materials.dueSoon(CourseMaterial.Kind.ASSIGNMENT, now, now.plusHours(24))) {
            m.setReminderSent(true);
            materials.save(m);
            CourseSpace s = m.getSpace();
            CourseWeek w = weeks.findBySpace_IdAndNumber(s.getId(), m.getWeekNumber()).orElse(null);
            if (!MaterialService.studentSees(s, w, m, now) || !access.onForCourse(s.getCourse())
                    || !s.getAcademicSession().getId().equals(academicSessions.current().getId())) continue;
            Set<Long> handed = submissions.findByAssignment_Id(m.getId()).stream().filter(x -> x.getSubmittedAt() != null)
                    .map(x -> x.getStudent().getId()).collect(Collectors.toSet());
            List<User> waiting = materialService.roster(s).stream().filter(st -> !handed.contains(st.getId())).toList();
            notifications.notify(waiting, "ASSIGNMENT", "Assignment due soon",
                    "\"" + m.getTitle() + "\" is due " + m.getDueAt().format(WHEN) + ". Hand it in before submissions close.",
                    AssignmentService.studentLink(m));
        }
    }

    /** Submissions just closed: the course's lecturer hears how many came in. */
    @Transactional
    public void reportClosed() {
        LocalDateTime now = LocalDateTime.now();
        for (CourseMaterial m : materials.justClosed(CourseMaterial.Kind.ASSIGNMENT, now)) {
            m.setClosedNotified(true);
            materials.save(m);
            if (m.getVisibility() == CourseMaterial.Visibility.DRAFT) continue;
            User lecturer = m.getSpace().getCourse().getUser();
            if (lecturer == null) continue;
            List<AssignmentSubmission> subs = submissions.findByAssignment_Id(m.getId());
            long handed = subs.stream().filter(x -> x.getSubmittedAt() != null).count();
            int size = materialService.roster(m.getSpace()).size();
            notifications.notify(lecturer, "ASSIGNMENT", "Submissions closed",
                    "\"" + m.getTitle() + "\": " + handed + " of " + size + " handed in. Open it to mark them.",
                    AssignmentService.staffLink(lecturer, m));
        }
    }
}
