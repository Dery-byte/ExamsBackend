package com.exam.service.attendance;

import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.academic.AcademicSession;
import com.exam.model.attendance.AttendanceRecord;
import com.exam.model.exam.Category;
import com.exam.model.exam.Department;
import com.exam.model.exam.Program;
import com.exam.model.exam.Registered_courses;
import com.exam.repository.*;
import com.exam.service.SystemSettingService;
import com.exam.service.academic.AcademicSessionService;
import com.exam.service.features.FeatureService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.TestPropertySource;

import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Attendance end to end on H2: codes that change and expire, the attempt lock-out, one phone per
 * student, the distance + accuracy rule, the programme/course mode lock, the second check,
 * finalising a meeting (absentees) and lecturer review of read-aloud check-ins.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.datasource.url=jdbc:h2:mem:attendance;MODE=MySQL;NON_KEYWORDS=USER,LEVEL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.show-sql=false",
})
@Import({AttendanceService.class, AttendancePolicyService.class, ClassroomService.class, ClassScheduleService.class, AttendanceMinimum.class, AttendanceDays.class,
        FeatureService.class, SystemSettingService.class, AcademicSessionService.class, com.exam.service.comms.CurrentUserService.class})
class AttendanceJpaTest {

    @Autowired AttendanceService attendance;
    @Autowired AttendancePolicyService policies;
    @Autowired ClassScheduleService schedules;
    @Autowired SystemSettingService settings;
    @Autowired AcademicSessionService academicSessions;
    @Autowired UserRepository users;
    @Autowired DepartmentRepository departments;
    @Autowired ProgramRepository programs;
    @Autowired CategoryRepository categories;
    @Autowired Registered_coursesRepository registrations;
    @Autowired AttendanceRecordRepository records;

    /** Classroom centre (Accra); 0.00009° of latitude is about 10 m. */
    static final double LAT = 5.6037, LNG = -0.1870, M = 0.000009;

    static final class MutableClock extends Clock {
        Instant now = LocalDateTime.of(2026, 10, 14, 8, 0).atZone(ZoneId.systemDefault()).toInstant();
        @Override public ZoneId getZone() { return ZoneId.systemDefault(); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void plusSeconds(long s) { now = now.plusSeconds(s); }
    }

    final MutableClock clock = new MutableClock();
    Department cmp;
    Program cs;
    Category course;
    User lecturer, hod, sa, ama, kofi, esi, outsider;

    @BeforeEach
    void setUp() {
        attendance.setClock(clock);
        settings.updateSetting("FEATURE_ATTENDANCE", "true");

        cmp = new Department();
        cmp.setName("Computing");
        cmp.setCode("CMP");
        cmp = departments.save(cmp);
        cs = new Program();
        cs.setName("Computer Science");
        cs.setCode("CS");
        cs.setDurationYears(4);
        cs.setDepartment(cmp);
        cs.setEnabled(true);
        cs = programs.save(cs);

        lecturer = user("lect1", Role.LECTURER, null);
        hod = user("hod1", Role.ADMIN, null);
        sa = user("sa1", Role.SUPER_ADMIN, null);
        ama = user("10001", Role.NORMAL, cs);
        kofi = user("10002", Role.NORMAL, cs);
        esi = user("10003", Role.NORMAL, cs);
        outsider = user("10004", Role.NORMAL, cs);

        course = new Category();
        course.setTitle("Data Structures");
        course.setCourseCode("CSC 201");
        course.setLevel("200");
        course.setSemester(1);
        course.setPrograms(new HashSet<>(Set.of(cs)));
        course.setUser(lecturer);
        course = categories.save(course);

        AcademicSession current = academicSessions.current();
        for (User s : List.of(ama, kofi, esi)) {
            Registered_courses r = new Registered_courses(null, new Date(), course, s);
            r.setSession(current);
            registrations.save(r);
        }
    }

    private User user(String username, Role role, Program program) {
        User u = new User();
        u.setUsername(username);
        u.setEmail(username + "@example.com");
        u.setFirstname(username);
        u.setLastname("Test");
        u.setPassword("x");
        u.setRole(role);
        u.setEnabled(true);
        if (role == Role.NORMAL) {
            u.setProgram(program);
            u.setCurrentLevel(200);
        } else if (role != Role.SUPER_ADMIN) {
            u.setDepartment(cmp);
        }
        return users.save(u);
    }

    private Long openCourse(String display, Integer minutes, Boolean secondCheck) {
        Map<String, Object> s = attendance.open(lecturer, new AttendanceService.OpenRequest("COURSE", course.getCid(), null, null, null,
                LAT, LNG, 8.0, display, minutes, "Linked lists", null, secondCheck));
        return (Long) s.get("id");
    }

    private String code(Long sessionId) {
        return (String) attendance.codeView(lecturer, sessionId).get("code");
    }

    private AttendanceService.CheckInResult checkIn(User s, Long sessionId, String code, double metresNorth, double accuracy, String device) {
        return attendance.checkIn(s, new AttendanceService.CheckInRequest(sessionId, code, LAT + metresNorth * M, LNG, accuracy, device), "10.0.0.1");
    }

    static final String AMA_PHONE = "ama-phone-0000000001";
    static final String KOFI_PHONE = "kofi-phone-000000002";
    static final String ESI_PHONE = "esi-phone-0000000003";

    @Test
    void theCodeChangesAndAForwardedCodeIsDead() {
        Long id = openCourse("PROJECTOR", 120, false);
        String first = code(id);
        assertThat(first).hasSize(5).containsOnlyDigits();

        assertThat(checkIn(ama, id, first, 5, 10, AMA_PHONE).status()).isEqualTo("PRESENT");

        // 17 s later: the next code is showing; the old one still works for 2 more seconds (typing allowance)
        clock.plusSeconds(17);
        assertThat(code(id)).isNotEqualTo(first);
        assertThat(checkIn(kofi, id, first, 5, 10, KOFI_PHONE).ok()).as("within the 5 s typing allowance").isTrue();

        // 40 s after it was shown (a forwarded code), it is refused as expired and counts as an attempt
        clock.plusSeconds(23);
        AttendanceService.CheckInResult late = checkIn(esi, id, first, 5, 10, ESI_PHONE);
        assertThat(late.ok()).isFalse();
        assertThat(late.reason()).isEqualTo("EXPIRED_CODE");
        assertThat(late.attemptsLeft()).isEqualTo(4);
    }

    @Test
    void fiveWrongCodesLockTheStudentOutUntilUnlocked() {
        Long id = openCourse("PROJECTOR", 120, false);
        String right = code(id);
        String wrong = right.equals("12345") ? "54321" : "12345";
        for (int i = 0; i < 4; i++) assertThat(checkIn(ama, id, wrong, 5, 10, AMA_PHONE).reason()).isEqualTo("WRONG_CODE");
        assertThat(checkIn(ama, id, wrong, 5, 10, AMA_PHONE).reason()).isEqualTo("LOCKED");
        assertThat(checkIn(ama, id, right, 5, 10, AMA_PHONE).reason()).as("even the right code is refused").isEqualTo("LOCKED");

        attendance.unlock(lecturer, id, ama.getId());
        assertThat(checkIn(ama, id, code(id), 5, 10, AMA_PHONE).status()).isEqualTo("PRESENT");
    }

    @SuppressWarnings("unchecked")
    private boolean phoneLinked(Long sessionId, User student) {
        return ((List<Map<String, Object>>) attendance.detail(lecturer, sessionId).get("students")).stream()
                .filter(m -> student.getId().equals(m.get("studentId"))).findFirst().map(m -> (Boolean) m.get("phoneLinked")).orElseThrow();
    }

    @Test
    void onePhonePerStudentAndOneStudentPerPhone() {
        Long id = openCourse("PROJECTOR", 120, false);
        assertThat(checkIn(ama, id, code(id), 5, 10, AMA_PHONE).ok()).isTrue();
        // Ama checks in her absent friend on her own phone
        AttendanceService.CheckInResult friend = checkIn(kofi, id, code(id), 5, 10, AMA_PHONE);
        assertThat(friend.ok()).isFalse();
        assertThat(friend.reason()).isEqualTo("PHONE_TAKEN");

        // Next meeting: Ama tries from another phone
        attendance.end(lecturer, id);
        Long next = openCourse("PROJECTOR", 120, false);
        assertThat(checkIn(ama, next, code(next), 5, 10, "ama-new-phone-00000009").reason()).isEqualTo("OTHER_PHONE");
        // The class list shows whose phone is linked, so staff know whose to reset
        assertThat(phoneLinked(next, ama)).isTrue();
        assertThat(phoneLinked(next, esi)).as("never checked in").isFalse();
        attendance.resetPhone(lecturer, next, ama.getId());
        assertThat(phoneLinked(next, ama)).isFalse();
        assertThat(checkIn(ama, next, code(next), 5, 10, "ama-new-phone-00000009").ok()).isTrue();
    }

    @Test
    void resettingAPhoneLinkFreesThatPhoneInTheSameMeeting() {
        Long id = openCourse("PROJECTOR", 120, false);
        assertThat(checkIn(ama, id, code(id), 5, 10, AMA_PHONE).ok()).isTrue();
        assertThat(checkIn(kofi, id, code(id), 5, 10, AMA_PHONE).reason()).isEqualTo("PHONE_TAKEN");

        // Staff decide the phone is really Kofi's (Ama borrowed it): after the reset it is free for him
        attendance.resetPhone(lecturer, id, ama.getId());
        AttendanceService.CheckInResult kofiIn = checkIn(kofi, id, code(id), 5, 10, AMA_PHONE);
        assertThat(kofiIn.ok()).as(kofiIn.message()).isTrue();
        assertThat(phoneLinked(id, kofi)).isTrue();
        // ...and now Ama can't take it back without another reset
        assertThat(phoneLinked(id, ama)).isFalse();
    }

    @Test
    void distancePlusAccuracyMustFitInsideTheRoom() {
        Long id = openCourse("PROJECTOR", 120, false);   // default allowed distance 50 m, precision 30 m
        assertThat(checkIn(ama, id, code(id), 5, 45, AMA_PHONE).reason()).isEqualTo("IMPRECISE");
        AttendanceService.CheckInResult laptop = checkIn(ama, id, code(id), 5, 20000, AMA_PHONE);
        assertThat(laptop.reason()).as("a computer guessing from its internet connection").isEqualTo("NO_GPS");
        assertThat(laptop.message()).contains("20 km").contains("phone");
        assertThat(checkIn(ama, id, code(id), 40, 15, AMA_PHONE).reason()).as("40 m ± 15 m could be 55 m away").isEqualTo("TOO_FAR");
        assertThat(checkIn(ama, id, code(id), 150, 5, AMA_PHONE).reason()).isEqualTo("TOO_FAR");
        assertThat(checkIn(ama, id, code(id), 25, 20, AMA_PHONE).ok()).isTrue();
        assertThat(checkIn(outsider, id, code(id), 5, 10, "outsider-phone-00000004").reason()).isEqualTo("NOT_LISTED");
    }

    @Test
    void lateCheckInsAreMarkedLate() {
        Long id = openCourse("PROJECTOR", 120, false);
        clock.plusSeconds(11 * 60);   // late after 10 minutes, check-in open for 15
        assertThat(checkIn(ama, id, code(id), 5, 10, AMA_PHONE).status()).isEqualTo("LATE");
        clock.plusSeconds(5 * 60);
        assertThat(checkIn(kofi, id, code(id), 5, 10, KOFI_PHONE).reason()).isEqualTo("CLOSED");
    }

    @Test
    void theDepartmentModeDecidesWhoStartsSessions() {
        AttendancePolicyService.PolicyRequest programme = new AttendancePolicyService.PolicyRequest("PROGRAMME",
                null, null, null, null, null, null, null, null, null, null, null, null);
        policies.saveSystem(sa, programme);

        assertThatThrownBy(() -> openCourse("PROJECTOR", 60, false)).hasMessageContaining("by programme");
        assertThatThrownBy(() -> attendance.open(lecturer, new AttendanceService.OpenRequest("PROGRAMME", null, cs.getId(), 200, null,
                LAT, LNG, 8.0, null, 60, null, null, false))).isInstanceOf(AccessDeniedException.class);

        Map<String, Object> s = attendance.open(hod, new AttendanceService.OpenRequest("PROGRAMME", null, cs.getId(), 200, null,
                LAT, LNG, 8.0, null, 60, "Assembly", null, false));
        Long id = (Long) s.get("id");
        assertThat(checkIn(outsider, id, (String) attendance.codeView(hod, id).get("code"), 5, 10, "outsider-phone-00000004").ok())
                .as("every Level 200 CS student is on a programme list").isTrue();

        // The HOD switches their own department back to course attendance
        policies.saveDepartment(hod, cmp.getId(), new AttendancePolicyService.PolicyRequest("COURSE",
                null, null, null, null, null, null, null, null, null, null, null, null));
        attendance.end(hod, id);
        assertThat(openCourse("PROJECTOR", 60, false)).isNotNull();
    }

    @Test
    void attendanceSwitchedOffBlocksStartingAndCheckingIn() {
        settings.updateSetting("FEATURE_ATTENDANCE", "false");
        assertThatThrownBy(() -> openCourse("PROJECTOR", 60, false)).isInstanceOf(AccessDeniedException.class);
        assertThat(attendance.openForStudent(ama).get("enabled")).isEqualTo(false);
    }

    @Test
    void theSecondCheckAndEndingTheClassRecordAbsentees() {
        Long id = openCourse("PROJECTOR", 60, true);
        assertThat(attendance.detail(lecturer, id).get("secondCheckPlanned")).isEqualTo(true);
        assertThat(checkIn(ama, id, code(id), 5, 10, AMA_PHONE).ok()).isTrue();
        assertThat(checkIn(kofi, id, code(id), 5, 10, KOFI_PHONE).ok()).isTrue();
        // Esi never checks in

        clock.plusSeconds(16 * 60);
        attendance.startSecondCheck(lecturer, id);
        Map<String, Object> view = attendance.codeView(lecturer, id);
        assertThat(view.get("phase")).isEqualTo("SECOND_CHECK");
        assertThat(checkIn(ama, id, (String) view.get("code"), 8, 10, AMA_PHONE).message()).contains("Second check done");
        // Kofi left after the first check; Esi can't use the second check without the first
        assertThat(checkIn(esi, id, (String) view.get("code"), 5, 10, ESI_PHONE).reason()).isEqualTo("NO_FIRST_CHECK");

        attendance.end(lecturer, id);
        Map<Long, AttendanceRecord> byStudent = new HashMap<>();
        records.forSession(id).forEach(r -> byStudent.put(r.getStudent().getId(), r));
        assertThat(byStudent.get(ama.getId()).getStatus().name()).isEqualTo("PRESENT");
        assertThat(byStudent.get(kofi.getId()).getStatus().name()).isEqualTo("ABSENT");
        assertThat(byStudent.get(kofi.getId()).hasFlag(AttendanceRecord.FLAG_MISSED_SECOND_CHECK)).isTrue();
        assertThat(byStudent.get(esi.getId()).getStatus().name()).isEqualTo("ABSENT");
        assertThat(byStudent.get(esi.getId()).getMethod()).isEqualTo(AttendanceRecord.METHOD_AUTO);

        // The lecturer excuses Esi; Ama's summary shows 100 %
        attendance.mark(lecturer, id, esi.getId(), "EXCUSED", "Hospital");
        @SuppressWarnings("unchecked")
        Map<String, Object> amaCourse = ((List<Map<String, Object>>) attendance.studentSummary(ama).get("summary")).get(0);
        assertThat(amaCourse.get("held")).isEqualTo(1);
        assertThat(amaCourse.get("percent")).isEqualTo(100.0);
        @SuppressWarnings("unchecked")
        Map<String, Object> esiCourse = ((List<Map<String, Object>>) attendance.studentSummary(esi).get("summary")).get(0);
        assertThat(esiCourse.get("excused")).isEqualTo(1);
        assertThat(esiCourse.get("percent")).as("excused meetings are left out").isNull();
    }

    @Test
    void scheduledFinishEndsTheClassOnItsOwn() {
        Long id = openCourse("PROJECTOR", 30, false);
        checkIn(ama, id, code(id), 5, 10, AMA_PHONE);
        assertThat(attendance.dueSessionIds()).doesNotContain(id);
        clock.plusSeconds(31 * 60);
        assertThat(attendance.dueSessionIds()).contains(id);
        assertThat(attendance.finalizeIfDue(id)).isTrue();
        assertThat(attendance.detail(lecturer, id).get("live")).isEqualTo(false);
        assertThat(records.forSession(id)).hasSize(3);
    }

    @Test
    void readAloudCheckInsWaitForTheLecturer() {
        Long id = openCourse("READ_ALOUD", 120, false);
        String c = code(id);
        clock.plusSeconds(60);
        assertThat(code(id)).as("a read-aloud code lasts 90 s").isEqualTo(c);
        assertThat(checkIn(ama, id, c, 5, 10, AMA_PHONE).status()).isEqualTo("PENDING");
        clock.plusSeconds(10 * 60);
        assertThat(checkIn(kofi, id, code(id), 5, 10, KOFI_PHONE).status()).isEqualTo("PENDING");

        attendance.approvePending(lecturer, id);
        Map<Long, String> st = new HashMap<>();
        records.forSession(id).forEach(r -> st.put(r.getStudent().getId(), r.getStatus().name()));
        assertThat(st.get(ama.getId())).isEqualTo("PRESENT");
        assertThat(st.get(kofi.getId())).isEqualTo("LATE");
    }

    @Test
    void onlyOneLiveSessionPerClassAndOtherLecturersAreKeptOut() {
        Long id = openCourse("PROJECTOR", 120, false);
        assertThatThrownBy(() -> openCourse("PROJECTOR", 120, false)).hasMessageContaining("already running");
        User other = user("lect2", Role.LECTURER, null);
        assertThatThrownBy(() -> attendance.detail(other, id)).isInstanceOf(AccessDeniedException.class);
        assertThat(attendance.detail(hod, id).get("id")).isEqualTo(id);
    }

    @Test
    void aLecturerReadingThatIsTooRoughIsRefused() {
        assertThatThrownBy(() -> attendance.open(lecturer, new AttendanceService.OpenRequest("COURSE", course.getCid(), null, null, null,
                LAT, LNG, 80.0, null, 60, null, null, false))).hasMessageContaining("±80 m");
    }

    /** One finished course meeting where only {@code present} check in (each on their own phone). */
    private Long meeting(User... present) {
        Long id = openCourse("PROJECTOR", 60, false);
        Map<User, String> phones = Map.of(ama, AMA_PHONE, kofi, KOFI_PHONE, esi, ESI_PHONE);
        for (User u : present) assertThat(checkIn(u, id, code(id), 5, 10, phones.get(u)).ok()).isTrue();
        attendance.end(lecturer, id);
        clock.plusSeconds(3600);
        return id;
    }

    private void minimum(boolean on, int percent) {
        policies.saveSystem(sa, new AttendancePolicyService.PolicyRequest(null, null, null, null, null, null, null, null, null, null, null, on, percent));
    }

    private com.exam.model.exam.Quiz quizFor(Category c) {
        com.exam.model.exam.Quiz q = new com.exam.model.exam.Quiz();
        q.setTitle("Mid-semester");
        q.setQuizTime("30");
        q.setQuizpassword("");
        q.setCategory(c);
        return quizzes.save(q);
    }

    @Autowired AttendanceMinimum minimum;
    @Autowired QuizRepository quizzes;

    @Test
    void belowTheCourseMinimumBlocksTheCoursesQuizzesUntilAbsencesAreExcused() {
        com.exam.model.exam.Quiz quiz = quizFor(course);
        meeting(ama, kofi);
        meeting(ama);
        Long third = meeting(kofi);
        Long fourth = meeting();
        // Ama 2 of 4 = 50 %, Kofi 2 of 4 = 50 %, Esi 0 of 4

        assertThat(minimum.blockReason(ama, quiz)).as("no minimum switched on yet").isEmpty();
        minimum(true, 75);
        assertThat(minimum.blockReason(ama, quiz)).get(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .contains("CSC 201").contains("50%").contains("2 of 4").contains("75%");
        assertThat(minimum.blockReason(esi, quiz)).isPresent();

        // The lecturer excuses Ama's two absences: 2 of 2 counted
        attendance.mark(lecturer, third, ama.getId(), "EXCUSED", "Sick note");
        attendance.mark(lecturer, fourth, ama.getId(), "EXCUSED", "Sick note");
        assertThat(minimum.blockReason(ama, quiz)).isEmpty();

        // A course with no meetings yet never blocks
        Category other = new Category();
        other.setTitle("Ethics");
        other.setCourseCode("CSC 209");
        other.setPrograms(new HashSet<>(Set.of(cs)));
        other.setUser(lecturer);
        other = categories.save(other);
        assertThat(minimum.blockReason(esi, quizFor(other))).isEmpty();

        // The student's page shows the minimum and that they are below it
        @SuppressWarnings("unchecked")
        Map<String, Object> esiCourse = ((List<Map<String, Object>>) attendance.studentSummary(esi).get("summary")).get(0);
        assertThat(esiCourse.get("minimumPercent")).isEqualTo(75);
        assertThat(esiCourse.get("belowMinimum")).isEqualTo(true);

        minimum(false, 75);
        assertThat(minimum.blockReason(esi, quiz)).isEmpty();
    }

    @Test
    void belowTheProgrammeMinimumBlocksEveryQuiz() {
        policies.saveSystem(sa, new AttendancePolicyService.PolicyRequest("PROGRAMME", null, null, null, null, null, null, null, null, null, null, true, 60));
        Map<String, Object> s = attendance.open(hod, new AttendanceService.OpenRequest("PROGRAMME", null, cs.getId(), 200, null,
                LAT, LNG, 8.0, null, 60, "Assembly", null, false));
        Long id = (Long) s.get("id");
        assertThat(checkIn(ama, id, (String) attendance.codeView(hod, id).get("code"), 5, 10, AMA_PHONE).ok()).isTrue();
        attendance.end(hod, id);

        Category other = new Category();
        other.setTitle("Ethics");
        other.setCourseCode("CSC 209");
        other = categories.save(other);   // not even in the programme: programme attendance still applies
        assertThat(minimum.blockReason(ama, quizFor(other))).isEmpty();
        assertThat(minimum.blockReason(kofi, quizFor(other))).get(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .contains("Computer Science").contains("0%").contains("60%");
    }

    // ── Staff scanning ───────────────────────────────────────────────────────

    private Long openScanned() {
        Map<String, Object> s = attendance.open(lecturer, new AttendanceService.OpenRequest("COURSE", course.getCid(), null, null, null,
                LAT, LNG, 8.0, null, 120, "Scanned class", null, null, "STAFF_SCAN"));
        return (Long) s.get("id");
    }

    /** The code the student's phone would show now, after it was given its key. */
    private String liveCode(AttendanceService.QrKeyResult key, long stepOffset) {
        byte[] k = java.util.Base64.getUrlDecoder().decode(key.key());
        return AttendanceQr.token(k, key.sessionId(), key.studentId(), AttendanceQr.step(clock.millis()) + stepOffset);
    }

    private AttendanceService.QrKeyResult key(User u, Long id, double metresNorth, String phone) {
        return attendance.qrKey(u, new AttendanceService.QrKeyRequest(id, LAT + metresNorth * M, LNG, 8.0, phone));
    }

    @Test
    void staffScanTheStudentsLiveCodeAndScreenshotsFail() {
        Long id = openScanned();
        assertThat(attendance.detail(lecturer, id).get("method")).isEqualTo("STAFF_SCAN");
        assertThat(checkIn(ama, id, code(id), 5, 10, AMA_PHONE).reason()).as("no typed codes in a scanned class").isEqualTo("SCANNED");
        assertThatThrownBy(() -> attendance.startSecondCheck(lecturer, id)).hasMessageContaining("no second check");

        // The phone and location rules apply before a student gets their code
        assertThat(key(kofi, id, 150, KOFI_PHONE).reason()).isEqualTo("TOO_FAR");
        assertThat(key(outsider, id, 5, "outsider-phone-00000004").reason()).isEqualTo("NOT_LISTED");
        AttendanceService.QrKeyResult ama1 = key(ama, id, 5, AMA_PHONE);
        assertThat(ama1.ok()).isTrue();
        assertThat(key(kofi, id, 5, AMA_PHONE).reason()).as("Ama's phone can't show Kofi's code").isEqualTo("PHONE_TAKEN");

        clock.plusSeconds(20 * 60);   // scanning order is not arrival order: nobody is marked late
        AttendanceService.ScanResult scanned = attendance.scanByStaff(lecturer, id, liveCode(ama1, 0));
        assertThat(scanned.outcome()).isEqualTo("RECORDED");
        assertThat(scanned.name()).isEqualTo("10001 Test");
        assertThat(attendance.scanByStaff(lecturer, id, liveCode(ama1, 0)).outcome()).isEqualTo("ALREADY");

        // A screenshot from 30 s ago, a tampered code, another class's code
        AttendanceService.QrKeyResult kofiKey = key(kofi, id, 5, KOFI_PHONE);
        AttendanceService.ScanResult old = attendance.scanByStaff(lecturer, id, liveCode(kofiKey, -3));
        assertThat(old.outcome()).isEqualTo("EXPIRED");
        assertThat(old.name()).as("the scanner still sees whose code it was").isEqualTo("10002 Test");
        String good = liveCode(kofiKey, 0);
        String forged = good.substring(0, good.length() - 1) + (good.endsWith("A") ? "B" : "A");
        assertThat(attendance.scanByStaff(lecturer, id, forged).outcome()).isEqualTo("REFUSED");
        assertThat(attendance.scanByStaff(lecturer, id, "hello").message()).contains("isn't a student's attendance code");

        attendance.end(lecturer, id);
        Map<Long, AttendanceRecord> byStudent = new HashMap<>();
        records.forSession(id).forEach(r -> byStudent.put(r.getStudent().getId(), r));
        assertThat(byStudent.get(ama.getId()).getStatus().name()).isEqualTo("PRESENT");
        assertThat(byStudent.get(ama.getId()).getMethod()).isEqualTo(AttendanceRecord.METHOD_SCAN);
        assertThat(byStudent.get(ama.getId()).getReviewedBy()).isEqualTo("lect1 Test");
        assertThat(byStudent.get(kofi.getId()).getStatus().name()).as("showed only a screenshot").isEqualTo("ABSENT");
    }

    @Test
    void aHelpersLinkScansUntilCancelled() {
        Long id = openScanned();
        Map<String, Object> helper = attendance.addHelper(lecturer, id, "Kojo (TA)");
        String link = (String) helper.get("token");
        assertThat(link).isNotBlank();
        assertThat(attendance.helperView(link).get("helper")).isEqualTo("Kojo (TA)");

        // The helper's own location isn't needed; the student's phone still had to be in the room for its code
        assertThat(key(esi, id, 200, ESI_PHONE).reason()).isEqualTo("TOO_FAR");
        AttendanceService.QrKeyResult esiKey = key(esi, id, 5, ESI_PHONE);
        AttendanceService.ScanResult scanned = attendance.scanByHelper(link, new AttendanceService.HelperScanRequest(liveCode(esiKey, 0)));
        assertThat(scanned.outcome()).isEqualTo("RECORDED");
        AttendanceRecord r = records.findBySession_IdAndStudent_Id(id, esi.getId()).orElseThrow();
        assertThat(r.getReviewedBy()).isEqualTo("Kojo (TA) (helper)");

        attendance.revokeHelper(lecturer, id, (Long) helper.get("id"));
        assertThatThrownBy(() -> attendance.helperView(link)).hasMessageContaining("cancelled");
        assertThatThrownBy(() -> attendance.helperView("not-a-real-link")).hasMessageContaining("isn't valid");

        Long typed = openCourseAfter(id);
        assertThatThrownBy(() -> attendance.addHelper(lecturer, typed, "TA")).hasMessageContaining("Helpers are for QR classes");
    }

    private Long openCourseAfter(Long previous) {
        attendance.end(lecturer, previous);
        return openCourse("PROJECTOR", 60, false);
    }

    @Test
    void studentsScanTheClassQrWithTheSameChecksAsATypedCode() {
        Long id = (Long) attendance.open(lecturer, new AttendanceService.OpenRequest("COURSE", course.getCid(), null, null, null,
                LAT, LNG, 8.0, "READ_ALOUD", 60, null, null, false, "STUDENT_SCAN")).get("id");
        Map<String, Object> view = attendance.codeView(lecturer, id);
        assertThat(view.get("method")).isEqualTo("STUDENT_SCAN");
        assertThat(view.get("codeSeconds")).as("a QR is always on a screen, never read aloud").isEqualTo(15);
        String qr = (String) view.get("qr");
        assertThat(qr).isEqualTo("AQ1." + id + ".1." + view.get("code"));

        // The student's phone reads the QR and checks in with its code: present, from inside the room only
        String scannedCode = qr.substring(qr.lastIndexOf('.') + 1);
        assertThat(checkIn(kofi, id, scannedCode, 150, 8, KOFI_PHONE).reason()).isEqualTo("TOO_FAR");
        assertThat(checkIn(ama, id, scannedCode, 5, 8, AMA_PHONE).status()).isEqualTo("PRESENT");
        // A photo of the QR sent on: dead like a forwarded typed code
        clock.plusSeconds(40);
        assertThat(checkIn(esi, id, scannedCode, 5, 8, ESI_PHONE).reason()).isEqualTo("EXPIRED_CODE");

        // A helper can hold up the same QR code (they don't scan anyone in this mode)
        String link = (String) attendance.addHelper(lecturer, id, "TA").get("token");
        Map<String, Object> helper = attendance.helperView(link);
        assertThat(helper.get("qr")).isEqualTo(attendance.codeView(lecturer, id).get("qr"));
        AttendanceService.ScanResult scan = attendance.scanByHelper(link, new AttendanceService.HelperScanRequest("A1.1.2.3.xxxxxxxxxxxxxxxx"));
        assertThat(scan.ok()).isFalse();
    }

    // ── Report-card days ─────────────────────────────────────────────────────

    @Autowired AttendanceDays attendanceDays;
    @Autowired AttendanceSessionRepository sessionRepo;

    @Test
    void reportCardDaysCountDatesNotMeetings() {
        meeting(ama, kofi);          // day 1, first lesson
        meeting(kofi);               // day 1, second lesson: Ama missed it but was in school that day
        clock.plusSeconds(86_400);
        meeting();                   // day 2: nobody checked in
        Long year = academicSessions.current().getId();

        AttendanceDays.TermDays term1 = attendanceDays.forTerm(year, cs.getId(), 200, 1, List.of());
        assertThat(term1.meetings()).isEqualTo(3);
        assertThat(term1.classDays()).isEqualTo(2);
        assertThat(term1.byStudent().get(ama.getId())).isEqualTo(new AttendanceDays.Days(1, 2));
        assertThat(term1.byStudent().get(kofi.getId())).isEqualTo(new AttendanceDays.Days(1, 2));
        assertThat(term1.byStudent().get(esi.getId())).isEqualTo(new AttendanceDays.Days(0, 2));

        assertThat(attendanceDays.forTerm(year, cs.getId(), 200, 2, List.of()).byStudent()).as("another term").isEmpty();
        assertThat(attendanceDays.forTerm(year, cs.getId(), 200, 1, List.of(course.getCid())).meetings()).as("the sheet's subjects").isEqualTo(3);
    }

    @Test
    void programmeMeetingsRecordTheTermTheirStudentsAreIn() {
        for (User s : List.of(ama, kofi, esi, outsider)) { s.setCurrentSemester(2); users.save(s); }
        policies.saveSystem(sa, new AttendancePolicyService.PolicyRequest("PROGRAMME", null, null, null, null, null, null, null, null, null, null, null, null));
        Long id = (Long) attendance.open(hod, new AttendanceService.OpenRequest("PROGRAMME", null, cs.getId(), 200, null,
                LAT, LNG, 8.0, null, 60, "Roll call", null, false)).get("id");
        assertThat(sessionRepo.findById(id).orElseThrow().getSemester()).isEqualTo(2);

        // An older meeting without a term gets one from its students
        com.exam.model.attendance.AttendanceSession old = sessionRepo.findById(id).orElseThrow();
        old.setSemester(null);
        sessionRepo.save(old);
        assertThat(attendance.backfillProgrammeTerms()).isEqualTo(1);
        assertThat(sessionRepo.findById(id).orElseThrow().getSemester()).isEqualTo(2);
    }

    @Test
    void scheduleStartsTodaysClass() {
        Map<String, Object> sc = schedules.create(lecturer, new ClassScheduleService.ScheduleRequest("COURSE", course.getCid(), null, null,
                "WEDNESDAY", "08:00", "10:00", null));   // 14 Oct 2026 is a Wednesday
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> today = (List<Map<String, Object>>) attendance.options(lecturer).get("today");
        assertThat(today).extracting(m -> m.get("id")).containsExactly(sc.get("id"));
        Map<String, Object> s = attendance.open(lecturer, new AttendanceService.OpenRequest(null, null, null, null, null,
                LAT, LNG, 8.0, null, null, null, (Long) sc.get("id"), false));
        assertThat(s.get("endsAt")).isEqualTo(LocalDateTime.of(2026, 10, 14, 10, 0));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> after = (List<Map<String, Object>>) attendance.options(lecturer).get("today");
        assertThat(after.get(0).get("startedToday")).isEqualTo(true);
    }
}
