package com.exam.service.materials;

import com.exam.model.User;
import com.exam.model.materials.AssignmentSubmission;
import com.exam.model.materials.CourseMaterial;
import com.exam.model.materials.CourseSpace;
import com.exam.model.materials.CourseWeek;
import com.exam.repository.materials.AssignmentSubmissionRepository;
import com.exam.repository.materials.CourseMaterialRepository;
import com.exam.repository.materials.CourseSpaceRepository;
import com.exam.repository.materials.CourseWeekRepository;
import com.exam.service.academic.AcademicSessionService;
import com.exam.service.comms.NotificationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * The every-minute notices, one course or one assignment per transaction (called by MaterialScheduler
 * from another bean, so the transactions are real):
 * <ul>
 *   <li>new items that have just become visible (one message per course);</li>
 *   <li>a reminder a day before an assignment is due, to those who haven't handed in;</li>
 *   <li>the lecturer hears when submissions close.</li>
 * </ul>
 * The work is found by ids only, each unit is loaded on its own (as the web pages load it), and it is
 * marked done with one direct database update. Notifications are written when the transaction commits,
 * so a student is never told about something that wasn't recorded, nor told twice.
 * <p>
 * 2026-11-18: the first version ran without a transaction (the timer called its own @Transactional
 * methods, which Spring doesn't wrap) and saved each item; Hibernate then failed with "force
 * initializing collection loading" and nothing was ever announced.
 */
@Service
public class MaterialNotices {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE d MMM, h:mm a", Locale.ENGLISH);

    @Autowired private CourseMaterialRepository materials;
    @Autowired private CourseWeekRepository weeks;
    @Autowired private CourseSpaceRepository spaces;
    @Autowired private AssignmentSubmissionRepository submissions;
    @Autowired private MaterialService materialService;
    @Autowired private MaterialAccess access;
    @Autowired private AcademicSessionService academicSessions;
    @Autowired private NotificationService notifications;

    // ── New items ───────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Long> coursesToAnnounce(LocalDateTime now) {
        return materials.spacesWaitingToAnnounce(CourseMaterial.Visibility.VISIBLE, CourseMaterial.Visibility.SCHEDULED, now);
    }

    /** Tells one course's students about its newly visible items; returns how many were announced. */
    @Transactional
    public int announce(Long spaceId, LocalDateTime now) {
        CourseSpace s = spaces.findById(spaceId).orElse(null);
        if (s == null) return 0;
        List<CourseMaterial> items = materials.waitingToAnnounceIn(spaceId, CourseMaterial.Visibility.VISIBLE, CourseMaterial.Visibility.SCHEDULED, now);
        if (items.isEmpty()) return 0;
        if (!s.getAcademicSession().getId().equals(academicSessions.current().getId())) {   // an earlier term: never announce
            materials.markNotified(items.stream().map(CourseMaterial::getId).toList());
            return 0;
        }
        if (!s.isVisibleToStudents() || !access.onForCourse(s.getCourse())) return 0;      // told once it's switched on
        Map<Integer, CourseWeek> rows = weeks.findBySpace_IdOrderByNumber(s.getId()).stream()
                .collect(Collectors.toMap(CourseWeek::getNumber, w -> w, (a, b) -> a));
        List<CourseMaterial> out = items.stream().filter(m -> MaterialService.studentSees(s, rows.get(m.getWeekNumber()), m, now)).toList();
        if (out.isEmpty()) return 0;

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
                    out.size() + " new items" + where + (assignments > 0 ? ", including " + assignments + " assignment" + (assignments == 1 ? "" : "s") : "") + ".",
                    link);
        }
        materials.markAnnounced(out.stream().map(CourseMaterial::getId).toList(), now);   // last: it clears the session
        return out.size();
    }

    private static String weekLabel(int week, Map<Integer, CourseWeek> rows) {
        if (week == 0) return " (General)";
        CourseWeek w = rows.get(week);
        return " (week " + week + (w != null && w.getTopic() != null ? ": " + w.getTopic() : "") + ")";
    }

    // ── Due tomorrow ────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Long> assignmentsDueSoon(LocalDateTime now) {
        return materials.dueSoonIds(CourseMaterial.Kind.ASSIGNMENT, now, now.plusHours(24));
    }

    /** A day before the due date: students who haven't handed in. Returns whether anyone was reminded. */
    @Transactional
    public boolean remind(Long assignmentId, LocalDateTime now) {
        CourseMaterial m = materials.findById(assignmentId).orElse(null);
        if (m == null || m.isReminderSent() || m.getDeletedAt() != null) return false;
        CourseSpace s = m.getSpace();
        CourseWeek w = weeks.findBySpace_IdAndNumber(s.getId(), m.getWeekNumber()).orElse(null);
        boolean tell = MaterialService.studentSees(s, w, m, now) && access.onForCourse(s.getCourse())
                && s.getAcademicSession().getId().equals(academicSessions.current().getId());
        List<User> waiting = List.of();
        if (tell) {
            Set<Long> handed = submissions.findByAssignment_Id(m.getId()).stream().filter(x -> x.getSubmittedAt() != null)
                    .map(x -> x.getStudent().getId()).collect(Collectors.toSet());
            waiting = materialService.roster(s).stream().filter(st -> !handed.contains(st.getId())).toList();
            notifications.notify(waiting, "ASSIGNMENT", "Assignment due soon",
                    "\"" + m.getTitle() + "\" is due " + m.getDueAt().format(WHEN) + ". Hand it in before submissions close.",
                    AssignmentService.studentLink(m));
        }
        materials.markReminded(m.getId());
        return !waiting.isEmpty();
    }

    // ── Submissions closed ──────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Long> assignmentsJustClosed(LocalDateTime now) {
        return materials.justClosedIds(CourseMaterial.Kind.ASSIGNMENT, now);
    }

    /** The course's lecturer hears how many handed in. Returns whether they were told. */
    @Transactional
    public boolean reportClosed(Long assignmentId) {
        CourseMaterial m = materials.findById(assignmentId).orElse(null);
        if (m == null || m.isClosedNotified() || m.getDeletedAt() != null) return false;
        User lecturer = m.getSpace().getCourse().getUser();
        boolean tell = m.getVisibility() != CourseMaterial.Visibility.DRAFT && lecturer != null;
        if (tell) {
            List<AssignmentSubmission> subs = submissions.findByAssignment_Id(m.getId());
            long handed = subs.stream().filter(x -> x.getSubmittedAt() != null).count();
            int size = materialService.roster(m.getSpace()).size();
            notifications.notify(lecturer, "ASSIGNMENT", "Submissions closed",
                    "\"" + m.getTitle() + "\": " + handed + " of " + size + " handed in. Open it to mark them.",
                    AssignmentService.staffLink(lecturer, m));
        }
        materials.markClosedReported(m.getId());
        return tell;
    }
}
