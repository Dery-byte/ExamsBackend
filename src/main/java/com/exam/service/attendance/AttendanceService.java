package com.exam.service.attendance;

import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.academic.AcademicSession;
import com.exam.model.attendance.*;
import com.exam.model.exam.Category;
import com.exam.model.exam.Department;
import com.exam.model.exam.Program;
import com.exam.model.features.Feature;
import com.exam.repository.*;
import com.exam.service.academic.AcademicSessionService;
import com.exam.service.comms.CurrentUserService;
import com.exam.service.features.FeatureService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Class attendance: starting a meeting, students checking in, the lecturer's live controls, and
 * finalising the meeting (absentees, missed second checks).
 * <p>
 * A check-in must pass every check, or it is refused; a student who is really in the room and
 * still can't check in goes to the lecturer, who marks them by hand:
 * <ol>
 *   <li>the meeting is live and the student is on its class list;</li>
 *   <li>they haven't used up their wrong/expired code attempts;</li>
 *   <li>the code is the one on the screen now (or just replaced, within the typing allowance);</li>
 *   <li>they use their own linked phone, and that phone hasn't checked in anyone else;</li>
 *   <li>their location is precise enough and, accuracy included, within the room's distance.</li>
 * </ol>
 * Business refusals are returned (not thrown) so a counted wrong code is saved.
 */
@Service
public class AttendanceService {

    @Autowired private AttendanceSessionRepository sessions;
    @Autowired private AttendanceRecordRepository records;
    @Autowired private AttendanceStrikeRepository strikes;
    @Autowired private AttendanceDeviceRepository devices;
    @Autowired private ClassroomRepository classrooms;
    @Autowired private ClassScheduleRepository schedules;
    @Autowired private CategoryRepository categories;
    @Autowired private ProgramRepository programs;
    @Autowired private UserRepository users;
    @Autowired private AcademicSessionService academicSessions;
    @Autowired private AttendancePolicyService policyService;
    @Autowired private FeatureService features;
    @Autowired private AttendanceMinimum minimum;
    @Autowired private AttendanceScannerRepository scanners;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int DEFAULT_DURATION_MINUTES = 120;
    /**
     * A reading this rough isn't a position at all: the device (usually a computer without GPS or with
     * Wi-Fi location off) is guessing from its internet connection. Told apart so the message says so.
     */
    static final int GUESSED_LOCATION_METERS = 1000;

    /** "20 km" / "1.5 km" for a guessed location's error. */
    static String approx(int meters) {
        double km = meters / 1000.0;
        return (km >= 10 ? String.valueOf(Math.round(km)) : String.valueOf(Math.round(km * 10) / 10.0)) + " km";
    }

    /** Scanned meetings keep check-in open at least this long (scanning a full class takes time). */
    static final int SCAN_WINDOW_MINUTES = 30;
    /** Helper scanner links open at once for one class. */
    static final int MAX_HELPERS = 10;

    /** The second check never comes in the first or last few minutes of class. */
    private static final int SECOND_CHECK_MARGIN_MINUTES = 5;

    private Clock clock = Clock.systemDefaultZone();

    /** Tests move time forward. */
    void setClock(Clock clock) { this.clock = clock; }

    LocalDateTime now() { return LocalDateTime.now(clock); }

    // ══ Starting a meeting ═══════════════════════════════════════════════════

    public record OpenRequest(String scope, Long courseId, Long programId, Integer level, Long classroomId,
                              Double latitude, Double longitude, Double accuracy, String codeDisplay,
                              Integer durationMinutes, String topic, Long scheduleId, Boolean secondCheck, String method) {

        /** Without the method (callers from before scanning existed): students type the code. */
        public OpenRequest(String scope, Long courseId, Long programId, Integer level, Long classroomId,
                           Double latitude, Double longitude, Double accuracy, String codeDisplay,
                           Integer durationMinutes, String topic, Long scheduleId, Boolean secondCheck) {
            this(scope, courseId, programId, level, classroomId, latitude, longitude, accuracy, codeDisplay,
                    durationMinutes, topic, scheduleId, secondCheck, null);
        }
    }

    @Transactional
    public Map<String, Object> open(User actor, OpenRequest req) {
        requireStaffFeature(actor);
        if (req == null) throw new IllegalArgumentException("Nothing to start.");
        LocalDateTime now = now();

        AttendanceMode scope;
        Category course = null;
        Program program = null;
        Integer level = req.level();
        Long classroomId = req.classroomId();
        LocalDateTime endsAt = null;
        ClassSchedule schedule = null;

        if (req.scheduleId() != null) {
            schedule = schedules.findById(req.scheduleId()).orElseThrow(() -> new IllegalArgumentException("That class time no longer exists."));
            scope = schedule.getScope();
            course = schedule.getCourse();
            program = schedule.getProgram();
            level = schedule.getLevel();
            if (classroomId == null && schedule.getClassroom() != null) classroomId = schedule.getClassroom().getId();
            LocalDateTime scheduledEnd = now.toLocalDate().atTime(schedule.getEndTime());
            long minutes = Duration.between(schedule.getStartTime(), schedule.getEndTime()).toMinutes();
            endsAt = scheduledEnd.isAfter(now.plusMinutes(15)) ? scheduledEnd : now.plusMinutes(Math.max(30, minutes));
        } else {
            scope = parseScope(req.scope());
            if (scope == AttendanceMode.COURSE) {
                if (req.courseId() == null) throw new IllegalArgumentException("Choose the course.");
                course = categories.findById(req.courseId()).orElseThrow(() -> new IllegalArgumentException("Course not found."));
            } else {
                if (req.programId() == null) throw new IllegalArgumentException("Choose the programme.");
                program = programs.findById(req.programId()).orElseThrow(() -> new IllegalArgumentException("Programme not found."));
            }
        }

        Department dept;
        if (scope == AttendanceMode.COURSE) {
            if (course == null) throw new IllegalArgumentException("Choose the course.");
            if (!AttendanceAccess.canOpenCourse(actor, course)) throw new AccessDeniedException("You don't teach this course.");
            dept = AttendanceAccess.departmentOf(course);
            level = null;
        } else {
            if (program == null) throw new IllegalArgumentException("Choose the programme.");
            if (!AttendanceAccess.canOpenProgramme(actor, program))
                throw new AccessDeniedException("Only the HOD or the Super Admin starts attendance for a programme.");
            dept = program.getDepartment();
            if (level != null && !program.getConfiguredLevels().contains(level))
                throw new IllegalArgumentException("Level " + level + " is not part of " + program.getName() + ".");
        }

        if (!features.isOnForDepartment(Feature.ATTENDANCE, dept))
            throw new AccessDeniedException("Class attendance is switched off for " + (dept != null ? dept.getName() : "this course") + ".");
        AttendancePolicy policy = policyService.effective(dept);
        if (policy.getMode() != scope) {
            throw new IllegalArgumentException(policy.getMode() == AttendanceMode.PROGRAMME
                    ? (dept != null ? dept.getName() : "This department") + " takes attendance by programme, so sessions are started by the HOD for a programme, not per course."
                    : (dept != null ? dept.getName() : "This department") + " takes attendance per course, so sessions are started from a course.");
        }

        for (AttendanceSession live : sessions.findByClosedAtIsNull()) {
            if (sameClass(live, scope, course, program, level))
                throw new IllegalArgumentException("A session for this class is already running (meeting " + live.getMeetingNumber() + "). Open it from the list.");
        }

        // ── Place ──
        double lat, lng;
        int radius;
        String placeName;
        if (classroomId != null) {
            Classroom room = classrooms.findById(classroomId).orElseThrow(() -> new IllegalArgumentException("Classroom not found."));
            if (room.getDepartment() != null && actor.getRole() != Role.SUPER_ADMIN
                    && !AttendanceAccess.sameDepartment(room.getDepartment(), actor.getDepartment())
                    && !AttendanceAccess.sameDepartment(room.getDepartment(), dept))
                throw new AccessDeniedException("That classroom belongs to another department.");
            lat = room.getLatitude();
            lng = room.getLongitude();
            radius = room.getRadiusMeters();
            placeName = room.getName() + (room.getBuilding() != null && !room.getBuilding().isBlank() ? ", " + room.getBuilding() : "");
        } else {
            if (!AttendanceGeo.validPoint(req.latitude(), req.longitude()) || req.accuracy() == null || req.accuracy() <= 0)
                throw new IllegalArgumentException("Your location couldn't be read. Allow location for this site, or pick a saved classroom.");
            int acc = (int) Math.ceil(req.accuracy());
            if (acc > GUESSED_LOCATION_METERS)
                throw new IllegalArgumentException("This device can't find where it is: it is only guessing from the internet connection (about "
                        + approx(acc) + " off). Start the session on a phone with location (GPS) on, or pick a saved classroom.");
            if (acc > policy.getMaxAccuracyMeters())
                throw new IllegalArgumentException("Your location reading is only accurate to ±" + acc + " m; it needs to be ±"
                        + policy.getMaxAccuracyMeters() + " m or better. Try again on a phone with GPS on, or pick a saved classroom.");
            lat = req.latitude();
            lng = req.longitude();
            radius = policy.getRadiusMeters();
            placeName = "Where the session was started";
        }

        // ── Timeline ──
        if (endsAt == null) {
            int duration = req.durationMinutes() != null ? req.durationMinutes() : DEFAULT_DURATION_MINUTES;
            if (duration < 15 || duration > 360) throw new IllegalArgumentException("Class length must be between 15 minutes and 6 hours.");
            endsAt = now.plusMinutes(duration);
        }
        AttendanceMethod method = parseMethod(req.method());
        boolean scanned = method == AttendanceMethod.STAFF_SCAN;
        CodeDisplay display = scanned ? CodeDisplay.PROJECTOR : parseDisplay(req.codeDisplay());

        AttendanceSession s = new AttendanceSession();
        s.setMethod(method);
        s.setScope(scope);
        s.setCourse(course);
        s.setProgram(program);
        s.setLevel(level);
        s.setDepartment(dept);
        AcademicSession academic = academicSessions.current();
        s.setAcademicSession(academic);
        // The term: a subject's own; for a programme meeting, the term most of its students are in
        s.setSemester(course != null ? course.getSemester() : majorityTerm(roster(s)));
        s.setMeetingNumber(1 + (int) (course != null
                ? sessions.countByCourse_CidAndAcademicSession_Id(course.getCid(), academic.getId())
                : sessions.countProgrammeMeetings(program.getId(), level, academic.getId())));
        s.setTopic(trim(req.topic(), 200));
        s.setOpenedBy(actor);
        s.setScheduleId(schedule != null ? schedule.getId() : null);
        s.setCodeDisplay(display);
        s.setSecret(AttendanceCodes.newSecret());
        s.setCodeSeconds(display == CodeDisplay.PROJECTOR ? policy.getCodeSeconds() : policy.getReadAloudCodeSeconds());
        s.setGraceSeconds(policy.getGraceSeconds());
        s.setMaxFailedAttempts(policy.getMaxFailedAttempts());
        s.setClassroomId(classroomId);
        s.setPlaceName(placeName);
        s.setLatitude(lat);
        s.setLongitude(lng);
        s.setRadiusMeters(radius);
        s.setMaxAccuracyMeters(policy.getMaxAccuracyMeters());
        s.setOpenedAt(now);
        s.setEndsAt(endsAt);
        s.setSecondCheckMinutes(policy.getSecondCheckMinutes());
        if (scanned) {
            // Scanning a full class takes a while and the order is the scanner's, not arrival order:
            // a longer window, nobody marked late, and no second check (the scan itself is the proof)
            s.setCheckInClosesAt(min(now.plusMinutes(Math.max(policy.getCheckInMinutes(), SCAN_WINDOW_MINUTES)), endsAt));
            s.setLateAfter(endsAt);
        } else {
            s.setCheckInClosesAt(min(now.plusMinutes(policy.getCheckInMinutes()), endsAt));
            s.setLateAfter(now.plusMinutes(policy.getLateAfterMinutes()));
            boolean second = req.secondCheck() != null ? req.secondCheck() : policy.isSecondCheck();
            if (second) planSecondCheck(s, s.getCheckInClosesAt());
        }
        return detail(actor, sessions.save(s).getId());
    }

    /** Picks a random time for the second check, after check-in closes and before class ends; none if the class is too short. */
    private static void planSecondCheck(AttendanceSession s, LocalDateTime after) {
        LocalDateTime earliest = after.plusMinutes(SECOND_CHECK_MARGIN_MINUTES);
        LocalDateTime latest = s.getEndsAt().minusMinutes(SECOND_CHECK_MARGIN_MINUTES + (long) s.getSecondCheckMinutes());
        if (!latest.isAfter(earliest)) {
            s.setSecondCheckOpensAt(null);
            s.setSecondCheckClosesAt(null);
            return;
        }
        long span = Duration.between(earliest, latest).getSeconds();
        LocalDateTime at = earliest.plusSeconds((long) (RANDOM.nextDouble() * span));
        s.setSecondCheckOpensAt(at);
        s.setSecondCheckClosesAt(at.plusMinutes(s.getSecondCheckMinutes()));
    }

    private static boolean sameClass(AttendanceSession s, AttendanceMode scope, Category course, Program program, Integer level) {
        if (s.getScope() != scope) return false;
        if (scope == AttendanceMode.COURSE) return s.getCourse() != null && course != null && s.getCourse().getCid().equals(course.getCid());
        return s.getProgram() != null && program != null && s.getProgram().getId().equals(program.getId()) && Objects.equals(s.getLevel(), level);
    }

    // ══ Phase and codes ══════════════════════════════════════════════════════

    /** CHECK_IN (check 1 open), SECOND_CHECK (check 2 open), IN_CLASS (no check open) or ENDED. */
    public record Phase(String name, int check, LocalDateTime closesAt) {}

    Phase phase(AttendanceSession s, LocalDateTime now) {
        if (s.getClosedAt() != null) return new Phase("ENDED", 0, null);
        if (now.isBefore(s.getCheckInClosesAt())) return new Phase("CHECK_IN", 1, s.getCheckInClosesAt());
        if (s.getSecondCheckOpensAt() != null && !now.isBefore(s.getSecondCheckOpensAt()) && now.isBefore(s.getSecondCheckClosesAt()))
            return new Phase("SECOND_CHECK", 2, s.getSecondCheckClosesAt());
        return new Phase("IN_CLASS", 0, null);
    }

    private static long elapsed(AttendanceSession s, int check, LocalDateTime now) {
        LocalDateTime base = check == 2 ? s.getSecondCheckOpensAt() : s.getOpenedAt();
        return Duration.between(base, now).getSeconds();
    }

    /** What the projector screen shows: the current code, its countdown and live counts. Polled every couple of seconds. */
    @Transactional(readOnly = true)
    public Map<String, Object> codeView(User actor, Long sessionId) {
        AttendanceSession s = load(sessionId);
        AttendanceAccess.requireManage(actor, s);
        LocalDateTime now = now();
        Phase p = phase(s, now);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sessionId", s.getId());
        m.put("title", title(s));
        m.put("meetingNumber", s.getMeetingNumber());
        m.put("placeName", s.getPlaceName());
        m.put("codeDisplay", s.getCodeDisplay().name());
        m.put("method", s.getMethod().name());
        m.put("phase", p.name());
        m.put("check", p.check());
        if (s.isScanned() && p.closesAt() != null) m.put("phaseSecondsLeft", Math.max(0, Duration.between(now, p.closesAt()).getSeconds()));
        if (p.check() > 0 && !s.isScanned()) {
            long e = elapsed(s, p.check(), now);
            m.put("code", AttendanceCodes.code(s.getSecret(), p.check(), AttendanceCodes.step(e, s.getCodeSeconds())));
            m.put("codeSeconds", s.getCodeSeconds());
            m.put("secondsLeft", AttendanceCodes.secondsLeft(e, s.getCodeSeconds()));
            m.put("phaseSecondsLeft", Math.max(0, Duration.between(now, p.closesAt()).getSeconds()));
        }
        m.put("serverTime", now);
        m.putAll(liveCounts(s));
        return m;
    }

    private Map<String, Object> liveCounts(AttendanceSession s) {
        long present = 0, late = 0, pending = 0, second = 0;
        for (Object[] row : records.liveCounts(s.getId())) {
            AttendanceStatus st = (AttendanceStatus) row[0];
            long n = ((Number) row[1]).longValue();
            long n2 = row[2] == null ? 0 : ((Number) row[2]).longValue();
            if (st == AttendanceStatus.PRESENT) present = n;
            else if (st == AttendanceStatus.LATE) late = n;
            else if (st == AttendanceStatus.PENDING) pending = n;
            if (st == AttendanceStatus.PRESENT || st == AttendanceStatus.LATE || st == AttendanceStatus.PENDING) second += n2;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("checkedIn", present + late + pending);
        m.put("pending", pending);
        m.put("secondChecked", second);
        m.put("rosterSize", rosterSize(s));
        m.put("headcount", s.getHeadcount());
        return m;
    }

    // ══ Class lists ══════════════════════════════════════════════════════════

    List<User> roster(AttendanceSession s) {
        if (s.getScope() == AttendanceMode.COURSE)
            return s.getCourse() == null ? List.of() : sessions.courseRoster(s.getCourse().getCid(), s.getAcademicSession().getId());
        return s.getProgram() == null ? List.of() : sessions.programmeRoster(s.getProgram().getId(), s.getLevel());
    }

    private record CachedSize(long at, int size) {}
    private final Map<Long, CachedSize> rosterSizes = new ConcurrentHashMap<>();

    /** Class size for the projector counts; cached briefly because the screen asks every couple of seconds. */
    private int rosterSize(AttendanceSession s) {
        long t = System.currentTimeMillis();
        CachedSize c = rosterSizes.get(s.getId());
        if (c != null && t - c.at() < 30_000) return c.size();
        int size = roster(s).size();
        rosterSizes.put(s.getId(), new CachedSize(t, size));
        if (rosterSizes.size() > 500) rosterSizes.clear();
        return size;
    }

    boolean onRoster(User student, AttendanceSession s) {
        if (student == null || student.getRole() != Role.NORMAL) return false;
        if (s.getScope() == AttendanceMode.COURSE)
            return s.getCourse() != null && sessions.countRegistration(s.getCourse().getCid(), student.getId(), s.getAcademicSession().getId()) > 0;
        return s.getProgram() != null && student.getProgram() != null && s.getProgram().getId().equals(student.getProgram().getId())
                && (s.getLevel() == null || s.getLevel().equals(student.getCurrentLevel()));
    }

    // ══ Student ══════════════════════════════════════════════════════════════

    /** Live meetings this student belongs to, with what they need to do now. */
    @Transactional(readOnly = true)
    public Map<String, Object> openForStudent(User student) {
        Map<String, Object> out = new LinkedHashMap<>();
        boolean on = features.isOnFor(Feature.ATTENDANCE, student);
        out.put("enabled", on);
        if (!on) { out.put("sessions", List.of()); return out; }
        LocalDateTime now = now();
        List<Map<String, Object>> list = new ArrayList<>();
        for (AttendanceSession s : sessions.findByClosedAtIsNull()) {
            if (!onRoster(student, s)) continue;
            Phase p = phase(s, now);
            AttendanceRecord r = records.findBySession_IdAndStudent_Id(s.getId(), student.getId()).orElse(null);
            int failures = strikes.findBySession_IdAndStudent_Id(s.getId(), student.getId()).map(AttendanceStrike::getFailures).orElse(0);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sessionId", s.getId());
            m.put("title", title(s));
            m.put("topic", s.getTopic());
            m.put("meetingNumber", s.getMeetingNumber());
            m.put("lecturer", CurrentUserService.displayName(s.getOpenedBy()));
            m.put("placeName", s.getPlaceName());
            m.put("codeDisplay", s.getCodeDisplay().name());
            m.put("method", s.getMethod().name());
            m.put("phase", p.name());
            m.put("phaseSecondsLeft", p.closesAt() != null ? Math.max(0, Duration.between(now, p.closesAt()).getSeconds()) : null);
            m.put("status", r != null ? r.getStatus().name() : null);
            // Scanned classes: show the QR code while check-in is open and nobody has recorded the student yet
            m.put("needsScan", s.isScanned() && "CHECK_IN".equals(p.name()) && (r == null || !r.getStatus().attended()));
            m.put("manual", r != null && r.getReviewedAt() != null && !AttendanceRecord.METHOD_CODE.equals(r.getMethod()));
            m.put("firstChecked", r != null && r.getFirstCheckAt() != null);
            m.put("secondChecked", r != null && r.getSecondCheckAt() != null);
            m.put("needsCheck", s.isScanned() ? 0 : needsCheck(p, r));
            m.put("attemptsLeft", Math.max(0, s.getMaxFailedAttempts() - failures));
            m.put("locked", failures >= s.getMaxFailedAttempts());
            m.put("maxAccuracyMeters", s.getMaxAccuracyMeters());
            list.add(m);
        }
        out.put("sessions", list);
        return out;
    }

    /** 1 or 2 when the student should type a code now, else 0. */
    private static int needsCheck(Phase p, AttendanceRecord r) {
        boolean staffDecided = r != null && r.getReviewedAt() != null;
        if (p.check() == 1) return r == null ? 1 : 0;
        if (p.check() == 2) return r != null && r.getFirstCheckAt() != null && r.getSecondCheckAt() == null && !staffDecided
                && (r.getStatus() == AttendanceStatus.PRESENT || r.getStatus() == AttendanceStatus.LATE || r.getStatus() == AttendanceStatus.PENDING) ? 2 : 0;
        return 0;
    }

    // ── Checks shared by typed codes and QR keys ────────────────────────────

    /** The phone rule's outcome: a refusal, or the device hash and its link (new or existing). */
    private record PhoneCheck(CheckInResult refusal, String hash, boolean newPhone, AttendanceDevice linked) {}

    /** One phone per student, one student per phone (per meeting and overall). */
    private PhoneCheck checkPhone(User student, AttendanceSession s, String rawDeviceId) {
        String deviceId = rawDeviceId == null ? "" : rawDeviceId.trim();
        if (!deviceId.matches("[A-Za-z0-9_-]{16,128}"))
            return new PhoneCheck(CheckInResult.refused("NO_DEVICE", "This browser can't be identified. Turn off private browsing and try again."), null, false, null);
        String hash = AttendanceCodes.hashDevice(deviceId);
        AttendanceDevice mine = devices.findByUser_Id(student.getId()).orElse(null);
        boolean newPhone = false;
        if (mine == null) {
            if (devices.findByDeviceHash(hash).isPresent())
                return new PhoneCheck(CheckInResult.refused("PHONE_TAKEN", "This phone is already linked to another student's account. Each student checks in on their own phone."), null, false, null);
            newPhone = true;
        } else if (!mine.getDeviceHash().equals(hash)) {
            return new PhoneCheck(CheckInResult.refused("OTHER_PHONE", "Your account is linked to a different phone (or browser). Check in on that one, or ask your lecturer to reset it if you changed phones."), null, false, null);
        }
        if (records.deviceUsedByOther(s.getId(), hash, student.getId()))
            return new PhoneCheck(CheckInResult.refused("PHONE_TAKEN", "This phone has already checked in another student for this class."), null, false, null);
        return new PhoneCheck(null, hash, newPhone, mine);
    }

    /** Links a new phone (on its first successful use) and notes when it was last used. */
    private void linkPhone(PhoneCheck phone, User student, LocalDateTime now) {
        AttendanceDevice d = phone.linked();
        if (d == null) {
            d = new AttendanceDevice();
            d.setUser(student);
            d.setDeviceHash(phone.hash());
            d.setLinkedAt(now);
        }
        d.setLastUsedAt(now);
        devices.save(d);
    }

    /** The location rule's outcome: a refusal, or the distance and accuracy (whole metres). */
    private record PlaceCheck(CheckInResult refusal, int distance, int accuracy) {}

    /** Precise enough, and inside the room's distance with the accuracy included. {@code who}: "You appear" / "This phone appears". */
    private PlaceCheck checkPlace(AttendanceSession s, Double lat, Double lng, Double accuracy, String who) {
        if (!AttendanceGeo.validPoint(lat, lng) || accuracy == null || accuracy <= 0 || !Double.isFinite(accuracy))
            return new PlaceCheck(CheckInResult.refused("NO_LOCATION", "Your location couldn't be read. Turn on location (GPS), allow this site to use it, and try again."), 0, 0);
        int acc = (int) Math.ceil(accuracy);
        if (acc > GUESSED_LOCATION_METERS)
            return new PlaceCheck(CheckInResult.refused("NO_GPS", "This device can't find where it is: it is only guessing from the internet connection (about "
                    + approx(acc) + " off). Use a phone with location (GPS) turned on."), 0, acc);
        if (acc > s.getMaxAccuracyMeters())
            return new PlaceCheck(CheckInResult.refused("IMPRECISE", "Your location is only accurate to ±" + acc + " m (it needs to be ±" + s.getMaxAccuracyMeters()
                    + " m or better). Turn on precise location, wait a few seconds and try again. If you are in class and it keeps failing, see your lecturer."), 0, acc);
        int distance = (int) Math.round(AttendanceGeo.distanceMeters(s.getLatitude(), s.getLongitude(), lat, lng));
        if (distance + acc > s.getRadiusMeters())
            return new PlaceCheck(CheckInResult.refused("TOO_FAR", who + " to be about " + distance + " m from the classroom (±" + acc + " m), outside the "
                    + s.getRadiusMeters() + " m allowed. If you are in class, see your lecturer."), distance, acc);
        return new PlaceCheck(null, distance, acc);
    }

    public record CheckInRequest(Long sessionId, String code, Double latitude, Double longitude, Double accuracy, String deviceId) {}

    /** {@code reason} is a short machine code the page uses to show help (WRONG_CODE, TOO_FAR, OTHER_PHONE …). */
    public record CheckInResult(boolean ok, String status, String message, String reason, Integer attemptsLeft) {
        static CheckInResult refused(String reason, String message) { return new CheckInResult(false, null, message, reason, null); }
    }

    @Transactional
    public CheckInResult checkIn(User student, CheckInRequest req, String ip) {
        if (student == null || student.getRole() != Role.NORMAL) throw new AccessDeniedException("Only students check in.");
        if (!features.isOnFor(Feature.ATTENDANCE, student))
            return CheckInResult.refused("OFF", "Class attendance is switched off.");
        if (req == null || req.sessionId() == null) return CheckInResult.refused("NO_SESSION", "Choose the class you are in.");
        AttendanceSession s = sessions.findById(req.sessionId()).orElse(null);
        if (s == null || !s.isLive()) return CheckInResult.refused("ENDED", "This class has ended.");
        if (!onRoster(student, s)) return CheckInResult.refused("NOT_LISTED", "You're not on the list for this class. If you should be, see your lecturer.");
        if (s.isScanned())
            return CheckInResult.refused("SCANNED", "In this class your lecturer scans your QR code: open your QR code and show it to them.");

        LocalDateTime now = now();
        Phase p = phase(s, now);
        AttendanceRecord r = records.findBySession_IdAndStudent_Id(s.getId(), student.getId()).orElse(null);

        if (r != null && r.getReviewedAt() != null && !AttendanceRecord.METHOD_CODE.equals(r.getMethod()))
            return new CheckInResult(true, r.getStatus().name(), "Your lecturer has already recorded your attendance: " + label(r.getStatus()) + ".", "RECORDED", null);
        if (p.check() == 0) {
            if (r != null && r.getFirstCheckAt() != null)
                return new CheckInResult(true, r.getStatus().name(), "You're checked in (" + label(r.getStatus()) + ").", "DONE", null);
            return CheckInResult.refused("CLOSED", "Check-in for this class is closed. If you are in class, see your lecturer.");
        }
        if (p.check() == 1 && r != null)
            return new CheckInResult(true, r.getStatus().name(), "You're already checked in (" + label(r.getStatus()) + ").", "DONE", null);
        if (p.check() == 2) {
            if (r == null || r.getFirstCheckAt() == null || r.getStatus() == AttendanceStatus.REJECTED || r.getStatus() == AttendanceStatus.ABSENT)
                return CheckInResult.refused("NO_FIRST_CHECK", "You didn't check in at the start of this class, so the second check doesn't apply. If you are in class, see your lecturer.");
            if (r.getSecondCheckAt() != null)
                return new CheckInResult(true, r.getStatus().name(), "You've already done the second check.", "DONE", null);
        }

        // ── Attempts and code ──
        AttendanceStrike strike = strikes.findBySession_IdAndStudent_Id(s.getId(), student.getId()).orElse(null);
        int failures = strike != null ? strike.getFailures() : 0;
        if (failures >= s.getMaxFailedAttempts())
            return new CheckInResult(false, null, "You've used all " + s.getMaxFailedAttempts() + " attempts for this class. Ask your lecturer to unlock you.", "LOCKED", 0);
        AttendanceCodes.Match match = AttendanceCodes.match(s.getSecret(), p.check(), elapsed(s, p.check(), now),
                s.getCodeSeconds(), s.getGraceSeconds(), req.code());
        if (match != AttendanceCodes.Match.VALID) {
            if (strike == null) {
                strike = new AttendanceStrike();
                strike.setSession(s);
                strike.setStudent(student);
            }
            strike.setFailures(failures + 1);
            strike.setLastAt(now);
            strikes.save(strike);
            int left = Math.max(0, s.getMaxFailedAttempts() - strike.getFailures());
            String what = match == AttendanceCodes.Match.EXPIRED ? "That code has expired. Type the code showing now." : "That code isn't right.";
            String tail = left == 0 ? " You've used all your attempts for this class. Ask your lecturer to unlock you."
                    : " " + left + (left == 1 ? " attempt" : " attempts") + " left.";
            return new CheckInResult(false, null, what + tail, left == 0 ? "LOCKED" : match.name() + "_CODE", left);
        }

        // ── Phone and location ──
        PhoneCheck phone = checkPhone(student, s, req.deviceId());
        if (phone.refusal() != null) return phone.refusal();
        PlaceCheck place = checkPlace(s, req.latitude(), req.longitude(), req.accuracy(), "You appear");
        if (place.refusal() != null) return place.refusal();
        int distance = place.distance(), acc = place.accuracy();
        String hash = phone.hash();
        boolean newPhone = phone.newPhone();

        // ── Passed: record it ──
        linkPhone(phone, student, now);

        String ipShort = ip == null ? null : (ip.length() > 64 ? ip.substring(0, 64) : ip);
        if (p.check() == 1) {
            r = new AttendanceRecord();
            r.setSession(s);
            r.setStudent(student);
            r.setMethod(AttendanceRecord.METHOD_CODE);
            boolean late = now.isAfter(s.getLateAfter());
            if (late) r.addFlag(AttendanceRecord.FLAG_LATE);
            if (newPhone) r.addFlag(AttendanceRecord.FLAG_NEW_PHONE);
            r.setStatus(s.getCodeDisplay() == CodeDisplay.READ_ALOUD ? AttendanceStatus.PENDING : late ? AttendanceStatus.LATE : AttendanceStatus.PRESENT);
            r.setFirstCheckAt(now);
            r.setFirstDistanceMeters(distance);
            r.setFirstAccuracyMeters(acc);
            r.setDeviceHash(hash);
            r.setIpAddress(ipShort);
        } else {
            r.setSecondCheckAt(now);
            r.setSecondDistanceMeters(distance);
            r.setSecondAccuracyMeters(acc);
        }
        records.save(r);

        String message = switch (r.getStatus()) {
            case PENDING -> p.check() == 2 ? "Second check done. Your lecturer will confirm your attendance."
                    : "Checked in. Your lecturer will confirm your attendance.";
            case LATE -> p.check() == 2 ? "Second check done." : "Checked in, marked Late.";
            default -> p.check() == 2 ? "Second check done. You're marked Present." : "You're checked in. Present.";
        };
        if (p.check() == 1 && (s.getSecondCheckOpensAt() != null))
            message += " Keep this page handy: a second code will be shown during class.";
        return new CheckInResult(true, r.getStatus().name(), message, "OK", null);
    }

    /** The student's attendance this academic session, per course (or programme), with recent meetings. */
    @Transactional(readOnly = true)
    public Map<String, Object> studentSummary(User student) {
        AcademicSession academic = academicSessions.current();
        List<AttendanceRecord> mine = records.forStudent(student.getId(), academic.getId());
        // A programme's meetings (whatever their level) are one group, as for the quiz minimum
        Map<String, Map<String, Object>> groups = new LinkedHashMap<>();
        Map<String, AttendanceTally> tallies = new HashMap<>();
        for (AttendanceRecord r : mine) {
            AttendanceSession s = r.getSession();
            boolean course = s.getScope() == AttendanceMode.COURSE;
            String key = course ? "C" + (s.getCourse() != null ? s.getCourse().getCid() : 0)
                    : "P" + (s.getProgram() != null ? s.getProgram().getId() : 0);
            groups.computeIfAbsent(key, k -> {
                Map<String, Object> g = new LinkedHashMap<>();
                g.put("key", k);
                g.put("scope", s.getScope().name());
                g.put("title", course ? title(s) : s.getProgram() != null ? s.getProgram().getName() : title(s));
                g.put("minimumPercent", course ? minimum.minimumForCourse(s.getCourse()) : minimum.minimumForProgramme(s.getProgram()));
                return g;
            });
            AttendanceTally t = tallies.computeIfAbsent(key, k -> new AttendanceTally());
            if (!s.isLive()) t.add(r.getStatus());   // a meeting counts once it has ended
        }
        List<Map<String, Object>> summary = new ArrayList<>();
        groups.forEach((k, g) -> {
            AttendanceTally t = tallies.get(k);
            g.put("held", t.held());
            g.put("attended", t.attended());
            g.put("late", t.late());
            g.put("absent", t.absent());
            g.put("excused", t.excused());
            g.put("pending", t.pending());
            g.put("counted", t.counted());
            g.put("percent", t.percent());
            Integer min = (Integer) g.get("minimumPercent");
            g.put("belowMinimum", min != null && t.below(min));
            summary.add(g);
        });
        List<Map<String, Object>> history = mine.stream().limit(40).map(r -> {
            AttendanceSession s = r.getSession();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sessionId", s.getId());
            m.put("title", title(s));
            m.put("meetingNumber", s.getMeetingNumber());
            m.put("topic", s.getTopic());
            m.put("date", s.getOpenedAt());
            m.put("status", r.getStatus().name());
            m.put("live", s.isLive());
            m.put("missedSecondCheck", r.hasFlag(AttendanceRecord.FLAG_MISSED_SECOND_CHECK));
            m.put("note", r.getNote());
            return m;
        }).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("academicSession", academic.getName());
        out.put("summary", summary);
        out.put("history", history);
        return out;
    }

    // ══ Staff: overview ══════════════════════════════════════════════════════

    /** What this staff member can start, with their rooms, today's timetable and live sessions. */
    @Transactional(readOnly = true)
    public Map<String, Object> options(User actor) {
        requireStaffFeature(actor);
        Map<String, Object> out = new LinkedHashMap<>();
        Department own = actor.getDepartment();
        AttendancePolicy ownPolicy = policyService.effective(own);
        out.put("role", actor.getRole().name());
        out.put("department", own != null ? own.getName() : null);
        out.put("mode", ownPolicy.getMode().name());
        out.put("policy", policyService.dto(ownPolicy));

        Map<Long, AttendanceMode> modeCache = new HashMap<>();
        Map<Long, Boolean> onCache = new HashMap<>();
        java.util.function.Function<Department, Boolean> courseMode = d -> {
            Long k = d == null ? -1L : d.getId();
            boolean on = onCache.computeIfAbsent(k, x -> features.isOnForDepartment(Feature.ATTENDANCE, d));
            return on && modeCache.computeIfAbsent(k, x -> policyService.modeFor(d)) == AttendanceMode.COURSE;
        };
        java.util.function.Function<Department, Boolean> programmeMode = d -> {
            Long k = d == null ? -1L : d.getId();
            boolean on = onCache.computeIfAbsent(k, x -> features.isOnForDepartment(Feature.ATTENDANCE, d));
            return on && modeCache.computeIfAbsent(k, x -> policyService.modeFor(d)) == AttendanceMode.PROGRAMME;
        };

        List<Category> courseList = switch (actor.getRole()) {
            case LECTURER -> categories.findByUser_Id(actor.getId());
            case ADMIN -> categories.findAll().stream().filter(c -> com.exam.service.examops.ExamAccess.courseInDepartment(c, own)).toList();
            default -> categories.findAll();
        };
        out.put("courses", courseList.stream()
                .filter(c -> courseMode.apply(AttendanceAccess.departmentOf(c)))
                .sorted(Comparator.comparing(c -> String.valueOf(c.getCourseCode())))
                .map(c -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", c.getCid());
                    m.put("code", c.getCourseCode());
                    m.put("title", c.getTitle());
                    m.put("level", c.getLevel());
                    return m;
                }).toList());

        List<Program> programList = switch (actor.getRole()) {
            case SUPER_ADMIN -> programs.findByEnabledTrue();
            case ADMIN -> own == null ? List.of() : programs.findByDepartment_IdAndEnabledTrue(own.getId());
            default -> List.of();
        };
        out.put("programs", programList.stream()
                .filter(p -> programmeMode.apply(p.getDepartment()))
                .sorted(Comparator.comparing(Program::getName))
                .map(p -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", p.getId());
                    m.put("name", p.getName());
                    m.put("code", p.getCode());
                    m.put("levels", p.getConfiguredLevels());
                    return m;
                }).toList());

        LocalDate today = now().toLocalDate();
        LocalDateTime startOfDay = today.atStartOfDay();
        out.put("today", schedules.findAllByOrderByDayOfWeekAscStartTimeAsc().stream()
                .filter(sc -> sc.getDayOfWeek() == today.getDayOfWeek())
                .filter(sc -> canManageSchedule(actor, sc))
                .map(sc -> {
                    Map<String, Object> m = ClassScheduleService.dto(sc);
                    m.put("startedToday", sessions.existsByScheduleIdAndOpenedAtGreaterThanEqual(sc.getId(), startOfDay));
                    return m;
                }).toList());

        out.put("live", visibleSessions(actor, 200).stream().filter(AttendanceSession::isLive).map(this::summaryDto).toList());
        return out;
    }

    static boolean canManageSchedule(User u, ClassSchedule sc) {
        return sc.getScope() == AttendanceMode.COURSE
                ? AttendanceAccess.canOpenCourse(u, sc.getCourse())
                : AttendanceAccess.canOpenProgramme(u, sc.getProgram());
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(User actor) {
        requireStaffFeature(actor);
        List<AttendanceSession> list = visibleSessions(actor, 100);
        Map<Long, Map<String, Long>> counts = new HashMap<>();
        if (!list.isEmpty()) {
            for (Object[] row : records.statusCounts(list.stream().map(AttendanceSession::getId).toList())) {
                counts.computeIfAbsent((Long) row[0], k -> new HashMap<>()).put(((AttendanceStatus) row[1]).name(), ((Number) row[2]).longValue());
            }
        }
        return list.stream().map(s -> {
            Map<String, Object> m = summaryDto(s);
            m.put("counts", counts.getOrDefault(s.getId(), Map.of()));
            return m;
        }).toList();
    }

    private List<AttendanceSession> visibleSessions(User actor, int limit) {
        PageRequest page = PageRequest.of(0, limit);
        return switch (actor.getRole()) {
            case SUPER_ADMIN -> sessions.recent(page);
            case ADMIN -> actor.getDepartment() == null ? List.of()
                    : sessions.recentInDepartment(actor.getDepartment().getId(), page);
            case LECTURER -> sessions.recentForLecturer(actor.getId(), page).stream().filter(s -> AttendanceAccess.canManage(actor, s)).toList();
            default -> List.of();
        };
    }

    // ══ Staff: one meeting ═══════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public Map<String, Object> detail(User actor, Long sessionId) {
        AttendanceSession s = load(sessionId);
        AttendanceAccess.requireManage(actor, s);
        Map<String, Object> out = summaryDto(s);

        Map<Long, AttendanceRecord> byStudent = new HashMap<>();
        records.forSession(s.getId()).forEach(r -> byStudent.put(r.getStudent().getId(), r));
        Map<Long, Integer> failures = new HashMap<>();
        strikes.forSession(s.getId()).forEach(k -> failures.put(k.getStudent().getId(), k.getFailures()));

        Map<Long, User> people = new LinkedHashMap<>();
        roster(s).forEach(u -> people.put(u.getId(), u));
        byStudent.values().forEach(r -> people.putIfAbsent(r.getStudent().getId(), r.getStudent()));

        Map<String, Long> counts = new LinkedHashMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (User u : people.values()) {
            AttendanceRecord r = byStudent.get(u.getId());
            int f = failures.getOrDefault(u.getId(), 0);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("studentId", u.getId());
            m.put("name", CurrentUserService.displayName(u));
            m.put("indexNo", u.getUsername());
            m.put("status", r != null ? r.getStatus().name() : null);
            m.put("method", r != null ? r.getMethod() : null);
            m.put("firstCheckAt", r != null ? r.getFirstCheckAt() : null);
            m.put("firstDistance", r != null ? r.getFirstDistanceMeters() : null);
            m.put("firstAccuracy", r != null ? r.getFirstAccuracyMeters() : null);
            m.put("secondCheckAt", r != null ? r.getSecondCheckAt() : null);
            m.put("secondDistance", r != null ? r.getSecondDistanceMeters() : null);
            m.put("secondAccuracy", r != null ? r.getSecondAccuracyMeters() : null);
            m.put("flags", r != null && r.getFlags() != null && !r.getFlags().isBlank() ? List.of(r.getFlags().split(",")) : List.of());
            m.put("reviewedBy", r != null ? r.getReviewedBy() : null);
            m.put("note", r != null ? r.getNote() : null);
            m.put("failures", f);
            m.put("locked", f >= s.getMaxFailedAttempts());
            rows.add(m);
            String k = r != null ? r.getStatus().name() : "NOT_YET";
            counts.merge(k, 1L, Long::sum);
        }
        Map<String, Integer> order = Map.of("PENDING", 0, "NOT_YET", 1, "PRESENT", 2, "LATE", 3, "EXCUSED", 4, "ABSENT", 5, "REJECTED", 6);
        rows.sort(Comparator.<Map<String, Object>>comparingInt(m -> order.getOrDefault(m.get("status") == null ? "NOT_YET" : (String) m.get("status"), 9))
                .thenComparing(m -> String.valueOf(m.get("name")).toLowerCase()));
        out.put("students", rows);
        out.put("counts", counts);
        out.put("rosterSize", people.size());
        if (s.isScanned()) out.put("helpers", scanners.forSession(s.getId()).stream().map(AttendanceService::helperDto).toList());
        return out;
    }

    private Map<String, Object> summaryDto(AttendanceSession s) {
        LocalDateTime now = now();
        Phase p = phase(s, now);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("scope", s.getScope().name());
        m.put("title", title(s));
        m.put("courseId", s.getCourse() != null ? s.getCourse().getCid() : null);
        m.put("programId", s.getProgram() != null ? s.getProgram().getId() : null);
        m.put("level", s.getLevel());
        m.put("department", s.getDepartment() != null ? s.getDepartment().getName() : null);
        m.put("meetingNumber", s.getMeetingNumber());
        m.put("topic", s.getTopic());
        m.put("openedBy", CurrentUserService.displayName(s.getOpenedBy()));
        m.put("openedAt", s.getOpenedAt());
        m.put("checkInClosesAt", s.getCheckInClosesAt());
        m.put("lateAfter", s.getLateAfter());
        m.put("endsAt", s.getEndsAt());
        m.put("closedAt", s.getClosedAt());
        m.put("live", s.isLive());
        m.put("phase", p.name());
        // Seconds rather than a clock time, so a browser in another time zone than the server counts down correctly
        m.put("phaseSecondsLeft", p.closesAt() != null ? Math.max(0, Duration.between(now, p.closesAt()).getSeconds()) : null);
        m.put("method", s.getMethod().name());
        m.put("codeDisplay", s.getCodeDisplay().name());
        m.put("codeSeconds", s.getCodeSeconds());
        m.put("placeName", s.getPlaceName());
        m.put("radiusMeters", s.getRadiusMeters());
        m.put("maxAccuracyMeters", s.getMaxAccuracyMeters());
        m.put("maxFailedAttempts", s.getMaxFailedAttempts());
        m.put("headcount", s.getHeadcount());
        // The second check's planned time is never shown, so it can't be passed on
        boolean planned = s.getSecondCheckOpensAt() != null;
        boolean started = planned && !now.isBefore(s.getSecondCheckOpensAt());
        m.put("secondCheckPlanned", planned && !started);
        m.put("secondCheckRunning", "SECOND_CHECK".equals(p.name()));
        m.put("secondCheckDone", started && !"SECOND_CHECK".equals(p.name()));
        m.put("secondCheckMinutes", s.getSecondCheckMinutes());
        return m;
    }

    // ══ Staff: controls ══════════════════════════════════════════════════════

    @Transactional
    public Map<String, Object> closeCheckIn(User actor, Long id) {
        AttendanceSession s = liveForStaff(actor, id);
        LocalDateTime now = now();
        if (now.isBefore(s.getCheckInClosesAt())) s.setCheckInClosesAt(now);
        if (now.isBefore(s.getLateAfter())) s.setLateAfter(now);
        sessions.save(s);
        return detail(actor, id);
    }

    /** Opens check-in again for a few minutes (e.g. a group arrived from another class). */
    @Transactional
    public Map<String, Object> extendCheckIn(User actor, Long id, int minutes) {
        AttendanceSession s = liveForStaff(actor, id);
        if (minutes < 1 || minutes > 30) throw new IllegalArgumentException("Choose between 1 and 30 minutes.");
        LocalDateTime now = now();
        if ("SECOND_CHECK".equals(phase(s, now).name())) throw new IllegalArgumentException("Wait until the second check has closed.");
        LocalDateTime close = min(now.plusMinutes(minutes), s.getEndsAt());
        s.setCheckInClosesAt(close);
        boolean secondPending = s.getSecondCheckOpensAt() != null && now.isBefore(s.getSecondCheckOpensAt());
        if (secondPending && s.getSecondCheckOpensAt().isBefore(close.plusMinutes(SECOND_CHECK_MARGIN_MINUTES))) planSecondCheck(s, close);
        sessions.save(s);
        return detail(actor, id);
    }

    @Transactional
    public Map<String, Object> startSecondCheck(User actor, Long id) {
        AttendanceSession s = liveForStaff(actor, id);
        if (s.isScanned()) throw new IllegalArgumentException("There is no second check when staff scan students: the scan itself shows they were in the room.");
        LocalDateTime now = now();
        Phase p = phase(s, now);
        if ("CHECK_IN".equals(p.name())) throw new IllegalArgumentException("Close check-in first, then start the second check.");
        if ("SECOND_CHECK".equals(p.name())) throw new IllegalArgumentException("The second check is already running.");
        if (s.getSecondCheckOpensAt() != null && !now.isBefore(s.getSecondCheckOpensAt()))
            throw new IllegalArgumentException("The second check has already been done for this class.");
        s.setSecondCheckOpensAt(now);
        s.setSecondCheckClosesAt(now.plusMinutes(Math.max(1, s.getSecondCheckMinutes())));
        if (s.getEndsAt().isBefore(s.getSecondCheckClosesAt().plusMinutes(1))) s.setEndsAt(s.getSecondCheckClosesAt().plusMinutes(1));
        sessions.save(s);
        return detail(actor, id);
    }

    @Transactional
    public Map<String, Object> cancelSecondCheck(User actor, Long id) {
        AttendanceSession s = liveForStaff(actor, id);
        LocalDateTime now = now();
        if (s.getSecondCheckOpensAt() == null || !now.isBefore(s.getSecondCheckOpensAt()))
            throw new IllegalArgumentException("There is no upcoming second check to cancel.");
        s.setSecondCheckOpensAt(null);
        s.setSecondCheckClosesAt(null);
        sessions.save(s);
        return detail(actor, id);
    }

    @Transactional
    public Map<String, Object> setHeadcount(User actor, Long id, Integer headcount) {
        AttendanceSession s = load(id);
        AttendanceAccess.requireManage(actor, s);
        if (headcount != null && (headcount < 0 || headcount > 5000)) throw new IllegalArgumentException("Enter a headcount between 0 and 5000.");
        s.setHeadcount(headcount);
        sessions.save(s);
        return detail(actor, id);
    }

    /** Staff decision for one student: present, late, excused, absent or rejected (also after the meeting ended). */
    @Transactional
    public Map<String, Object> mark(User actor, Long id, Long studentId, String status, String note) {
        AttendanceSession s = load(id);
        AttendanceAccess.requireManage(actor, s);
        AttendanceStatus st;
        try { st = AttendanceStatus.valueOf(String.valueOf(status).trim().toUpperCase()); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Unknown attendance status."); }
        if (st == AttendanceStatus.PENDING) throw new IllegalArgumentException("Choose present, late, excused, absent or rejected.");
        User student = users.findById(studentId).orElseThrow(() -> new IllegalArgumentException("Student not found."));
        AttendanceRecord r = records.findBySession_IdAndStudent_Id(s.getId(), studentId).orElse(null);
        if (r == null) {
            if (!onRoster(student, s)) throw new IllegalArgumentException("This student is not on the list for this class.");
            r = new AttendanceRecord();
            r.setSession(s);
            r.setStudent(student);
            r.setMethod(AttendanceRecord.METHOD_MANUAL);
        } else if (AttendanceRecord.METHOD_AUTO.equals(r.getMethod())) {
            r.setMethod(AttendanceRecord.METHOD_MANUAL);
        }
        r.setStatus(st);
        r.setReviewedBy(CurrentUserService.displayName(actor));
        r.setReviewedAt(now());
        String n = trim(note, 300);
        if (n != null) r.setNote(n);
        records.save(r);
        return detail(actor, id);
    }

    /** Confirms every check-in waiting for review (read-aloud classes). */
    @Transactional
    public Map<String, Object> approvePending(User actor, Long id) {
        AttendanceSession s = load(id);
        AttendanceAccess.requireManage(actor, s);
        String by = CurrentUserService.displayName(actor);
        LocalDateTime now = now();
        List<AttendanceRecord> changed = new ArrayList<>();
        for (AttendanceRecord r : records.forSession(s.getId())) {
            if (r.getStatus() != AttendanceStatus.PENDING) continue;
            r.setStatus(r.hasFlag(AttendanceRecord.FLAG_LATE) ? AttendanceStatus.LATE : AttendanceStatus.PRESENT);
            r.setReviewedBy(by);
            r.setReviewedAt(now);
            changed.add(r);
        }
        records.saveAll(changed);
        return detail(actor, id);
    }

    /** Lets a student who used up their code attempts try again. */
    @Transactional
    public Map<String, Object> unlock(User actor, Long id, Long studentId) {
        AttendanceSession s = load(id);
        AttendanceAccess.requireManage(actor, s);
        strikes.findBySession_IdAndStudent_Id(s.getId(), studentId).ifPresent(strikes::delete);
        return detail(actor, id);
    }

    /** Unlinks a student's phone (they changed phones); their next check-in links the new one. */
    @Transactional
    public Map<String, Object> resetPhone(User actor, Long id, Long studentId) {
        AttendanceSession s = load(id);
        AttendanceAccess.requireManage(actor, s);
        User student = users.findById(studentId).orElseThrow(() -> new IllegalArgumentException("Student not found."));
        if (!onRoster(student, s) && records.findBySession_IdAndStudent_Id(s.getId(), studentId).isEmpty())
            throw new IllegalArgumentException("This student is not on the list for this class.");
        devices.findByUser_Id(studentId).ifPresent(devices::delete);
        return detail(actor, id);
    }

    @Transactional
    public Map<String, Object> end(User actor, Long id) {
        AttendanceSession s = liveForStaff(actor, id);
        finalizeSession(s, now());
        return detail(actor, id);
    }

    /** For a session started by mistake: removes it and everything recorded in it. */
    @Transactional
    public void delete(User actor, Long id) {
        AttendanceSession s = load(id);
        AttendanceAccess.requireManage(actor, s);
        records.deleteBySession(s.getId());
        strikes.deleteBySession(s.getId());
        scanners.deleteBySession(s.getId());
        sessions.delete(s);
        rosterSizes.remove(id);
    }

    // ══ Scanned meetings: the student's QR code, and staff / helper scanning ══

    public record QrKeyRequest(Long sessionId, Double latitude, Double longitude, Double accuracy, String deviceId) {}

    /** ok: the student's key for this meeting and what the phone needs to draw the changing code; else a refusal. */
    public record QrKeyResult(boolean ok, String message, String reason, String key, Long sessionId, Long studentId,
                              Integer stepSeconds, Long serverTime, String name, String indexNo) {
        static QrKeyResult refused(CheckInResult r) {
            return new QrKeyResult(false, r.message(), r.reason(), null, null, null, null, null, null, null);
        }
    }

    /**
     * Gives the student's phone its key for a scanned meeting, after the same phone and location
     * checks as a typed check-in, so the code can't be produced by a phone outside the room.
     */
    @Transactional
    public QrKeyResult qrKey(User student, QrKeyRequest req) {
        if (student == null || student.getRole() != Role.NORMAL) throw new AccessDeniedException("Only students show an attendance QR code.");
        if (!features.isOnFor(Feature.ATTENDANCE, student)) return QrKeyResult.refused(CheckInResult.refused("OFF", "Class attendance is switched off."));
        if (req == null || req.sessionId() == null) return QrKeyResult.refused(CheckInResult.refused("NO_SESSION", "Choose the class you are in."));
        AttendanceSession s = sessions.findById(req.sessionId()).orElse(null);
        if (s == null || !s.isLive()) return QrKeyResult.refused(CheckInResult.refused("ENDED", "This class has ended."));
        if (!s.isScanned()) return QrKeyResult.refused(CheckInResult.refused("TYPED", "In this class you type the code your lecturer shows."));
        if (!onRoster(student, s))
            return QrKeyResult.refused(CheckInResult.refused("NOT_LISTED", "You're not on the list for this class. If you should be, see your lecturer."));
        LocalDateTime now = now();
        if (!"CHECK_IN".equals(phase(s, now).name()))
            return QrKeyResult.refused(CheckInResult.refused("CLOSED", "Check-in for this class is closed. If you are in class, see your lecturer."));
        PhoneCheck phone = checkPhone(student, s, req.deviceId());
        if (phone.refusal() != null) return QrKeyResult.refused(phone.refusal());
        PlaceCheck place = checkPlace(s, req.latitude(), req.longitude(), req.accuracy(), "You appear");
        if (place.refusal() != null) return QrKeyResult.refused(place.refusal());
        linkPhone(phone, student, now);
        return new QrKeyResult(true, "Show this code to your lecturer to be scanned.", "OK",
                AttendanceQr.encodeKey(AttendanceQr.studentKey(s.getSecret(), student.getId())), s.getId(), student.getId(),
                AttendanceQr.STEP_SECONDS, clock.millis(), CurrentUserService.displayName(student), student.getUsername());
    }

    /**
     * What the scanner shows after a scan. outcome: RECORDED, ALREADY, EXPIRED (a photo or screenshot),
     * NOT_LISTED or REFUSED. The student's name and photo are returned whenever the code is genuine,
     * so the scanner can see whether the person holding the phone is that student.
     */
    public record ScanResult(boolean ok, String outcome, String message, Long studentId, String name, String indexNo, String photo, String status) {
        static ScanResult refused(String message) { return new ScanResult(false, "REFUSED", message, null, null, null, null, null); }
    }

    /** The lecturer (or HOD / Super Admin) scanning from their own account. */
    @Transactional
    public ScanResult scanByStaff(User actor, Long sessionId, String code) {
        AttendanceSession s = load(sessionId);
        AttendanceAccess.requireManage(actor, s);
        return scan(s, code, CurrentUserService.displayName(actor));
    }

    public record HelperScanRequest(String code) {}

    /**
     * A helper scanning through their link. The classroom is the one the lecturer set when starting
     * the class; the helper's own location isn't checked (indoor GPS on a helper's phone often can't
     * pass), because every student's code can only be shown from a phone inside that room.
     */
    @Transactional
    public ScanResult scanByHelper(String linkToken, HelperScanRequest req) {
        AttendanceScanner h = helper(linkToken);
        AttendanceSession s = h.getSession();
        ScanResult r = scan(s, req == null ? null : req.code(), h.getLabel() + " (helper)");
        if ("RECORDED".equals(r.outcome())) h.setScans(h.getScans() + 1);
        h.setLastUsedAt(now());
        scanners.save(h);
        return r;
    }

    private ScanResult scan(AttendanceSession s, String code, String scannedBy) {
        if (!s.isLive()) return ScanResult.refused("This class has ended.");
        if (!s.isScanned()) return ScanResult.refused("Students type the code in this class, so there is nothing to scan.");
        LocalDateTime now = now();
        if (!"CHECK_IN".equals(phase(s, now).name())) return ScanResult.refused("Check-in is closed. Reopen it to scan more students.");
        AttendanceQr.Parsed t = AttendanceQr.parse(code);
        if (t == null) return ScanResult.refused("That isn't a student's attendance code.");
        if (t.sessionId() != s.getId()) return ScanResult.refused("That code is for a different class.");
        AttendanceQr.Check c = AttendanceQr.verify(s.getSecret(), t, clock.millis());
        if (c == AttendanceQr.Check.FORGED) return ScanResult.refused("That code isn't genuine.");
        User student = users.findById(t.studentId()).orElse(null);
        if (student == null) return ScanResult.refused("That student no longer exists.");
        String name = CurrentUserService.displayName(student);
        if (c == AttendanceQr.Check.EXPIRED)
            return new ScanResult(false, "EXPIRED", "That code has expired, so it may be a photo or screenshot. Ask " + name
                    + " to show the live code on their own phone.", student.getId(), name, student.getUsername(), student.getProfilePic(), null);
        if (!onRoster(student, s))
            return new ScanResult(false, "NOT_LISTED", name + " is not on the list for this class.", student.getId(), name, student.getUsername(), student.getProfilePic(), null);

        AttendanceRecord r = records.findBySession_IdAndStudent_Id(s.getId(), student.getId()).orElse(null);
        if (r != null && r.getStatus().attended())
            return new ScanResult(true, "ALREADY", name + " is already marked " + label(r.getStatus()).toLowerCase() + ".", student.getId(), name,
                    student.getUsername(), student.getProfilePic(), r.getStatus().name());
        if (r == null) {
            r = new AttendanceRecord();
            r.setSession(s);
            r.setStudent(student);
        }
        r.setStatus(AttendanceStatus.PRESENT);
        r.setMethod(AttendanceRecord.METHOD_SCAN);
        r.setFirstCheckAt(now);
        r.setReviewedBy(scannedBy);
        r.setReviewedAt(now);
        records.save(r);
        return new ScanResult(true, "RECORDED", name + " is present.", student.getId(), name, student.getUsername(), student.getProfilePic(), r.getStatus().name());
    }

    /** A scanner link for a helper (e.g. a teaching assistant). The link itself is returned only now. */
    @Transactional
    public Map<String, Object> addHelper(User actor, Long sessionId, String label) {
        AttendanceSession s = liveForStaff(actor, sessionId);
        if (!s.isScanned()) throw new IllegalArgumentException("Helpers scan QR codes, so they are only for classes where staff scan students.");
        String name = trim(label, 80);
        if (name == null) throw new IllegalArgumentException("Enter the helper's name.");
        if (scanners.forSession(s.getId()).stream().filter(h -> h.getRevokedAt() == null).count() >= MAX_HELPERS)
            throw new IllegalArgumentException("A class can have up to " + MAX_HELPERS + " helpers at a time.");
        String token = AttendanceCodes.newSecret();
        AttendanceScanner h = new AttendanceScanner();
        h.setSession(s);
        h.setLabel(name);
        h.setTokenHash(AttendanceCodes.hashDevice(token));
        h.setCreatedBy(CurrentUserService.displayName(actor));
        h.setCreatedAt(now());
        Map<String, Object> m = helperDto(scanners.save(h));
        m.put("token", token);
        return m;
    }

    @Transactional
    public Map<String, Object> revokeHelper(User actor, Long sessionId, Long helperId) {
        AttendanceSession s = load(sessionId);
        AttendanceAccess.requireManage(actor, s);
        AttendanceScanner h = scanners.findById(helperId).filter(x -> x.getSession().getId().equals(s.getId()))
                .orElseThrow(() -> new IllegalArgumentException("Helper not found."));
        if (h.getRevokedAt() == null) {
            h.setRevokedAt(now());
            scanners.save(h);
        }
        return detail(actor, sessionId);
    }

    /** What a helper's scanner page shows: the class, how long check-in stays open, and the counts. */
    @Transactional(readOnly = true)
    public Map<String, Object> helperView(String linkToken) {
        AttendanceScanner h = helper(linkToken);
        AttendanceSession s = h.getSession();
        LocalDateTime now = now();
        Phase p = phase(s, now);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("helper", h.getLabel());
        m.put("title", title(s));
        m.put("meetingNumber", s.getMeetingNumber());
        m.put("placeName", s.getPlaceName());
        m.put("phase", p.name());
        m.put("phaseSecondsLeft", p.closesAt() != null ? Math.max(0, Duration.between(now, p.closesAt()).getSeconds()) : null);
        m.put("maxAccuracyMeters", s.getMaxAccuracyMeters());
        m.put("myScans", h.getScans());
        m.putAll(liveCounts(s));
        return m;
    }

    private AttendanceScanner helper(String linkToken) {
        String t = linkToken == null ? "" : linkToken.trim();
        if (t.isEmpty() || t.length() > 100) throw new IllegalArgumentException("This scanner link isn't valid.");
        AttendanceScanner h = scanners.findByTokenHash(AttendanceCodes.hashDevice(t))
                .orElseThrow(() -> new IllegalArgumentException("This scanner link isn't valid."));
        if (h.getRevokedAt() != null) throw new IllegalArgumentException("The lecturer has cancelled this scanner link.");
        AttendanceSession s = h.getSession();
        if (!s.isLive()) throw new IllegalArgumentException("This class has ended, so the scanner link no longer works.");
        if (!features.isOnForDepartment(Feature.ATTENDANCE, s.getDepartment())) throw new IllegalArgumentException("Class attendance is switched off.");
        return h;
    }

    static Map<String, Object> helperDto(AttendanceScanner h) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", h.getId());
        m.put("label", h.getLabel());
        m.put("createdBy", h.getCreatedBy());
        m.put("createdAt", h.getCreatedAt());
        m.put("lastUsedAt", h.getLastUsedAt());
        m.put("scans", h.getScans());
        m.put("active", h.getRevokedAt() == null);
        return m;
    }

    // ══ Terms on programme meetings ══════════════════════════════════════════

    /** The term (semester) most of these students are in; students of a class move up together. */
    static Integer majorityTerm(Collection<User> students) {
        return students.stream().map(User::getCurrentSemester).filter(Objects::nonNull)
                .collect(Collectors.groupingBy(t -> t, Collectors.counting()))
                .entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
    }

    /**
     * Programme meetings held before the term was recorded on them: takes the term most of their
     * students were in (from the meeting's own records, else its class list). Run once at start-up.
     */
    @Transactional
    public int backfillProgrammeTerms() {
        int n = 0;
        for (AttendanceSession s : sessions.findByScopeAndSemesterIsNull(AttendanceMode.PROGRAMME)) {
            List<User> students = records.forSession(s.getId()).stream().map(AttendanceRecord::getStudent).toList();
            Integer term = majorityTerm(students.isEmpty() ? roster(s) : students);
            if (term == null) continue;
            s.setSemester(term);
            sessions.save(s);
            n++;
        }
        return n;
    }

    // ══ Finalising ═══════════════════════════════════════════════════════════

    /** Ends meetings whose time is up. Called every minute. */
    @Transactional
    public boolean finalizeIfDue(Long id) {
        AttendanceSession s = sessions.findById(id).orElse(null);
        LocalDateTime now = now();
        if (s == null || !s.isLive() || s.getEndsAt().isAfter(now)) return false;
        finalizeSession(s, now);
        return true;
    }

    public List<Long> dueSessionIds() {
        return sessions.findByClosedAtIsNullAndEndsAtBefore(now()).stream().map(AttendanceSession::getId).toList();
    }

    /**
     * Records everyone on the list who never checked in as Absent, and turns a check-in without the
     * second check into Absent (the lecturer can still change it). Staff decisions are left alone.
     */
    void finalizeSession(AttendanceSession s, LocalDateTime now) {
        if (!s.isLive()) return;
        if (s.getSecondCheckClosesAt() != null && s.getSecondCheckClosesAt().isAfter(now)) {
            if (s.getSecondCheckOpensAt().isAfter(now)) {   // planned but never reached
                s.setSecondCheckOpensAt(null);
                s.setSecondCheckClosesAt(null);
            } else {
                s.setSecondCheckClosesAt(now);
            }
        }
        if (s.getCheckInClosesAt().isAfter(now)) s.setCheckInClosesAt(now);
        s.setClosedAt(now);
        sessions.save(s);

        Map<Long, AttendanceRecord> byStudent = records.forSession(s.getId()).stream()
                .collect(Collectors.toMap(r -> r.getStudent().getId(), r -> r, (a, b) -> a));
        boolean secondHeld = s.getSecondCheckOpensAt() != null;
        List<AttendanceRecord> changed = new ArrayList<>();
        for (AttendanceRecord r : byStudent.values()) {
            boolean decidedByStaff = r.getReviewedAt() != null;
            boolean counted = r.getStatus() == AttendanceStatus.PRESENT || r.getStatus() == AttendanceStatus.LATE || r.getStatus() == AttendanceStatus.PENDING;
            if (secondHeld && !decidedByStaff && AttendanceRecord.METHOD_CODE.equals(r.getMethod()) && counted && r.getSecondCheckAt() == null) {
                r.setStatus(AttendanceStatus.ABSENT);
                r.addFlag(AttendanceRecord.FLAG_MISSED_SECOND_CHECK);
                changed.add(r);
            }
        }
        for (User u : roster(s)) {
            if (byStudent.containsKey(u.getId())) continue;
            AttendanceRecord r = new AttendanceRecord();
            r.setSession(s);
            r.setStudent(u);
            r.setStatus(AttendanceStatus.ABSENT);
            r.setMethod(AttendanceRecord.METHOD_AUTO);
            changed.add(r);
        }
        records.saveAll(changed);
        rosterSizes.remove(s.getId());
    }

    // ══ Helpers ══════════════════════════════════════════════════════════════

    private void requireStaffFeature(User actor) {
        if (!AttendanceAccess.isStaff(actor)) throw new AccessDeniedException("Staff only.");
        features.require(Feature.ATTENDANCE, actor);
    }

    private AttendanceSession load(Long id) {
        if (id == null) throw new IllegalArgumentException("Session not found.");
        return sessions.findById(id).orElseThrow(() -> new IllegalArgumentException("Session not found."));
    }

    private AttendanceSession liveForStaff(User actor, Long id) {
        AttendanceSession s = load(id);
        AttendanceAccess.requireManage(actor, s);
        if (!s.isLive()) throw new IllegalArgumentException("This class has already ended.");
        return s;
    }

    static String title(AttendanceSession s) {
        if (s.getScope() == AttendanceMode.COURSE && s.getCourse() != null) {
            Category c = s.getCourse();
            return ((c.getCourseCode() != null ? c.getCourseCode() + " " : "") + (c.getTitle() != null ? c.getTitle() : "")).trim();
        }
        if (s.getProgram() != null) return s.getProgram().getName() + (s.getLevel() != null ? " · Level " + s.getLevel() : "");
        return "Class";
    }

    private static String label(AttendanceStatus st) {
        return switch (st) {
            case PRESENT -> "Present";
            case LATE -> "Late";
            case PENDING -> "waiting for your lecturer";
            case REJECTED -> "Not accepted";
            case EXCUSED -> "Excused";
            case ABSENT -> "Absent";
        };
    }

    private static AttendanceMode parseScope(String s) {
        if (s == null || s.isBlank()) throw new IllegalArgumentException("Choose a course or a programme.");
        try { return AttendanceMode.valueOf(s.trim().toUpperCase()); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Choose a course or a programme."); }
    }

    private static AttendanceMethod parseMethod(String s) {
        if (s == null || s.isBlank()) return AttendanceMethod.STUDENT_CODE;
        try { return AttendanceMethod.valueOf(s.trim().toUpperCase()); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Choose how attendance is taken: students type a code, or staff scan QR codes."); }
    }

    private static CodeDisplay parseDisplay(String s) {
        if (s == null || s.isBlank()) return CodeDisplay.PROJECTOR;
        try { return CodeDisplay.valueOf(s.trim().toUpperCase()); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Choose projector or read aloud."); }
    }

    private static LocalDateTime min(LocalDateTime a, LocalDateTime b) { return a.isBefore(b) ? a : b; }

    private static String trim(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        return t.length() > max ? t.substring(0, max) : t;
    }
}
