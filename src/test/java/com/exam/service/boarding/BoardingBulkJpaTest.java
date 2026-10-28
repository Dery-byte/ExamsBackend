package com.exam.service.boarding;

import com.exam.model.Gender;
import com.exam.model.Residency;
import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.boarding.HostelRoom;
import com.exam.model.boarding.ResidencyMode;
import com.exam.model.boarding.RoomAllocation;
import com.exam.model.exam.Department;
import com.exam.model.exam.Program;
import com.exam.repository.*;
import com.exam.service.SystemSettingService;
import com.exam.service.academic.AcademicSessionService;
import com.exam.service.comms.AnnouncementService;
import com.exam.service.reports.BoardingReports;
import com.exam.service.reports.ReportFilters;
import com.exam.service.reports.ReportResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Boarding Phase 2 on H2: the room-list and rooms imports, rooms from the student import,
 * auto-allocation (fill / spread / keeping levels together), clearing rooms for a new year, the
 * boarding reports and their house lock, announcements to one house / boarders / day students,
 * and the line printed on report cards.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.datasource.url=jdbc:h2:mem:boardingbulk;MODE=MySQL;NON_KEYWORDS=USER,LEVEL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.show-sql=false",
})
@Import({BoardingService.class, HostelService.class, BoardingSettings.class, BoardingAccess.class, BoardingBulkService.class,
        SystemSettingService.class, AcademicSessionService.class, com.exam.service.comms.CurrentUserService.class,
        BoardingReports.class, com.exam.service.reports.ReportSupport.class, com.exam.service.academic.InstitutionService.class,
        com.exam.service.academic.ThemeService.class, com.exam.service.admin.MaintenanceService.class,
        AnnouncementService.class, com.exam.service.features.FeatureService.class, com.exam.service.admin.ImportService.class})
class BoardingBulkJpaTest {

    @MockBean com.exam.service.comms.NotificationService notifications;
    @MockBean com.exam.service.admin.CredentialEmailService credentialEmails;
    @MockBean PasswordEncoder passwordEncoder;

    @Autowired BoardingService boarding;
    @Autowired BoardingBulkService bulk;
    @Autowired HostelService hostels;
    @Autowired BoardingSettings settings;
    @Autowired BoardingAccess access;
    @Autowired BoardingReports reports;
    @Autowired AnnouncementService announcements;
    @Autowired com.exam.service.admin.ImportService importer;
    @Autowired UserRepository users;
    @Autowired RoomAllocationRepository allocations;
    @Autowired HostelRoomRepository rooms;
    @Autowired DepartmentRepository departments;
    @Autowired ProgramRepository programs;

    User sa, hm;
    Program sci;
    Long boys, girls;
    int next = 1;

    @BeforeEach
    void setUp() {
        settings.setMode(ResidencyMode.BOTH);
        Department d = new Department();
        d.setName("Science");
        d.setCode("SCI");
        d = departments.save(d);
        sci = new Program();
        sci.setName("General Science");
        sci.setCode("GSC");
        sci.setDurationYears(3);
        sci.setDepartment(d);
        sci.setEnabled(true);
        sci = programs.save(sci);
        sa = staff("sa", Role.SUPER_ADMIN);
        hm = staff("hm", Role.LECTURER);
        boys = id(hostels.saveHostel(sa, null, new HostelService.HostelRequest("Aggrey House", "AGH", "BOYS", 2, null, null)));
        girls = id(hostels.saveHostel(sa, null, new HostelService.HostelRequest("Kwegyir House", "KWH", "GIRLS", 1, null, null)));
    }

    private User staff(String username, Role role) {
        User u = new User();
        u.setUsername(username);
        u.setEmail(username + "@x.com");
        u.setFirstname(username);
        u.setLastname("Staff");
        u.setPassword("x");
        u.setRole(role);
        u.setEnabled(true);
        return users.save(u);
    }

    private User student(Gender g, int level, Residency r) {
        User u = new User();
        int n = next++;
        u.setUsername("S" + n);
        u.setEmail("s" + n + "@x.com");
        u.setFirstname("Student" + n);
        u.setLastname("Test");
        u.setPassword("x");
        u.setRole(Role.NORMAL);
        u.setEnabled(true);
        u.setGender(g);
        u.setResidency(r);
        u.setCurrentLevel(level);
        u.setProgram(sci);
        return users.save(u);
    }

    private static Long id(Map<String, Object> m) { return ((Number) m.get("id")).longValue(); }

    private Long room(Long hostel, int floor, String name, int beds) {
        return id(hostels.saveRoom(sa, hostel, null, new HostelService.RoomRequest(null, floor, name, beds, null, "OPEN", null, null)));
    }

    private static Map<String, String> row(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> r) { return (List<Map<String, Object>>) r.get("rows"); }

    private Long roomOf(User s) { return allocations.findCurrent(s.getId()).map(a -> a.getRoom().getId()).orElse(null); }

    // ── Imports ──────────────────────────────────────────────────────────────

    @Test
    void roomListImportChecksEveryRowAndCanSwapPeopleInOneFile() {
        Long r1 = room(boys, 0, "1", 1), r2 = room(boys, 0, "2", 1);
        User a = student(Gender.MALE, 1, Residency.BOARDING), b = student(Gender.MALE, 1, Residency.BOARDING);
        User girl = student(Gender.FEMALE, 1, Residency.BOARDING), day = student(Gender.MALE, 1, Residency.DAY);
        boarding.assign(sa, new BoardingService.AssignRequest(a.getId(), r1, null, false, null));
        boarding.assign(sa, new BoardingService.AssignRequest(b.getId(), r2, null, false, null));

        List<Map<String, String>> file = List.of(
                row("Student ID", a.getUsername(), "House", "Aggrey House", "Room", "2"),
                row("Student ID", b.getUsername(), "House", "AGH", "Room", "Room 1"),          // the swap: both rooms are full
                row("Student ID", girl.getUsername(), "House", "Aggrey House", "Room", "1"),
                row("Student ID", "NOPE", "House", "Aggrey House", "Room", "1"),
                row("Student ID", day.getUsername(), "House", "Mystery", "Room", "1"));
        Map<String, Object> check = bulk.runImport(sa, "allocations", file, false);
        List<Map<String, Object>> rs = rows(check);
        assertThat(rs.get(0).get("ok")).isEqualTo(true);
        assertThat(rs.get(1).get("ok")).isEqualTo(true);
        assertThat(rs.get(2).get("errors").toString()).contains("for boys only");
        assertThat(rs.get(3).get("errors").toString()).contains("No student with ID NOPE");
        assertThat(rs.get(4).get("errors").toString()).contains("'Mystery' not found");
        assertThat(roomOf(a)).isEqualTo(r1);                           // nothing saved by a check

        Map<String, Object> done = bulk.runImport(sa, "allocations", file, true);
        assertThat(done.get("created")).isEqualTo(2);
        assertThat(roomOf(a)).isEqualTo(r2);
        assertThat(roomOf(b)).isEqualTo(r1);
        assertThat(allocations.historyOfStudent(a.getId()).get(1).getEndReason()).isEqualTo(RoomAllocation.EndReason.MOVED);
    }

    @Test
    void aRowThatCannotMoveKeepsItsBedForEveryoneElse() {
        Long full = room(boys, 0, "1", 1), small = room(boys, 0, "2", 1);
        User a = student(Gender.MALE, 1, Residency.BOARDING), b = student(Gender.MALE, 1, Residency.BOARDING), c = student(Gender.MALE, 1, Residency.BOARDING);
        boarding.assign(sa, new BoardingService.AssignRequest(a.getId(), full, null, false, null));
        boarding.assign(sa, new BoardingService.AssignRequest(c.getId(), small, null, false, null));
        // a wants room 2 (taken by c, who stays): a fails, so b can't have a's bed either
        List<Map<String, Object>> rs = rows(bulk.runImport(sa, "allocations", List.of(
                row("studentid", a.getUsername(), "house", "Aggrey House", "room", "2"),
                row("studentid", b.getUsername(), "house", "Aggrey House", "room", "1")), false));
        assertThat(rs.get(0).get("errors").toString()).contains("is full");
        assertThat(rs.get(1).get("errors").toString()).contains("is full");
    }

    @Test
    void roomsImportUnderstandsFloorsAndRefusesDuplicates() {
        room(boys, 0, "G1", 4);
        Map<String, Object> check = bulk.runImport(sa, "rooms", List.of(
                row("House", "Aggrey House", "Floor", "Ground", "Room", "G2", "Beds", "6"),
                row("House", "Aggrey House", "Floor", "First floor", "Room", "101", "Beds", "8", "For", "boys"),
                row("House", "Aggrey House", "Floor", "1", "Room", "102", "Beds", "8"),
                row("House", "Aggrey House", "Floor", "Third floor", "Room", "301", "Beds", "8"),
                row("House", "Aggrey House", "Room", "G1", "Beds", "4"),
                row("House", "Kwegyir House", "Block", "Annex", "Room", "A1", "Beds", "4")), false);
        List<Map<String, Object>> rs = rows(check);
        assertThat(rs.subList(0, 3)).allMatch(r -> Boolean.TRUE.equals(r.get("ok")));
        assertThat(rs.get(3).get("errors").toString()).contains("doesn't exist");
        assertThat(rs.get(4).get("errors").toString()).contains("already has room G1");
        assertThat(rs.get(5).get("errors").toString()).contains("no block called 'Annex'");
        assertThatThrownBy(() -> bulk.runImport(hm, "rooms", List.of(row("House", "Aggrey House", "Room", "X", "Beds", "2")), false))
                .isInstanceOf(AccessDeniedException.class);

        bulk.runImport(sa, "rooms", List.of(row("House", "Aggrey House", "Floor", "First floor", "Room", "101", "Beds", "8")), true);
        HostelRoom made = rooms.findForHostel(boys).stream().filter(x -> x.getName().equals("101")).findFirst().orElseThrow();
        assertThat(made.getFloor()).isEqualTo(1);
        assertThat(made.getCapacity()).isEqualTo(8);
    }

    @Test
    void theStudentImportCanGiveRoomsAndCountsBedsAcrossRows() {
        room(boys, 0, "1", 1);
        org.mockito.Mockito.when(passwordEncoder.encode(org.mockito.ArgumentMatchers.anyString())).thenReturn("hash");
        List<Map<String, String>> file = List.of(
                row("First Name", "Kofi", "Last Name", "A", "Email", "k1@x.com", "Student ID", "N1", "Program", "GSC", "Level", "100", "Gender", "Male", "House", "Aggrey House", "Room", "1"),
                row("First Name", "Kwame", "Last Name", "B", "Email", "k2@x.com", "Student ID", "N2", "Program", "GSC", "Level", "100", "Gender", "M", "House", "Aggrey House", "Room", "1"),
                row("First Name", "Esi", "Last Name", "C", "Email", "k3@x.com", "Student ID", "N3", "Program", "GSC", "Level", "100", "Gender", "F", "Residency", "Day"));
        List<Map<String, Object>> rs = rows(importer.run(sa, "students", file, false));
        assertThat((List<?>) rs.get(0).get("errors")).isEmpty();
        assertThat(rs.get(1).get("errors").toString()).contains("is full");
        assertThat((List<?>) rs.get(2).get("errors")).isEmpty();

        importer.run(sa, "students", List.of(file.get(0), file.get(2)), true);
        User kofi = users.findByUsername("N1").orElseThrow();
        assertThat(kofi.getGender()).isEqualTo(Gender.MALE);
        assertThat(kofi.getResidency()).isEqualTo(Residency.BOARDING);   // a room makes them a boarder
        assertThat(roomOf(kofi)).isNotNull();
        assertThat(users.findByUsername("N3").orElseThrow().getResidency()).isEqualTo(Residency.DAY);
        // an HOD without boarding duties can't hand out rooms through the import
        User hod = staff("hod", Role.ADMIN);
        hod.setDepartment(departments.findAll().get(0));
        users.save(hod);
        List<Map<String, Object>> byHod = rows(importer.run(hod, "students", List.of(row("First Name", "Yaw", "Last Name", "D", "Email", "y@x.com",
                "Student ID", "N4", "Program", "GSC", "Level", "100", "Gender", "M", "House", "Aggrey House", "Room", "1")), false));
        assertThat(byHod.get(0).get("errors").toString()).contains("Only boarding staff");
    }

    // ── Auto-allocate and the new year ───────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void autoAllocateFillsOrSpreadsAndRespectsGender() {
        Long r1 = room(boys, 0, "1", 3), r2 = room(boys, 0, "2", 3);
        room(girls, 0, "1", 2);
        for (int i = 0; i < 4; i++) student(Gender.MALE, 1, Residency.BOARDING);
        student(Gender.FEMALE, 2, Residency.BOARDING);
        User noGender = student(null, 1, Residency.BOARDING);
        student(Gender.MALE, 1, Residency.DAY);                     // not a boarder: never placed

        Map<String, Object> fill = bulk.autoPreview(sa, new BoardingBulkService.AutoRequest(null, null, null, null, "FILL", "ANY", true));
        List<Map<String, Object>> placed = (List<Map<String, Object>>) fill.get("placed");
        assertThat(placed).hasSize(5);
        assertThat(placed.stream().filter(p -> ((Map<String, Object>) p.get("place")).get("roomId").equals(r1))).hasSize(3);
        assertThat((List<Map<String, Object>>) fill.get("unplaced")).singleElement()
                .satisfies(u -> assertThat(u.get("reason").toString()).contains("Gender not recorded"));
        assertThat(allocations.findAllCurrent()).isEmpty();          // a preview saves nothing

        Map<String, Object> spread = bulk.autoPreview(sa, new BoardingBulkService.AutoRequest(List.of(boys), "MALE", null, null, "SPREAD", "ANY", true));
        List<Map<String, Object>> sp = (List<Map<String, Object>>) spread.get("placed");
        assertThat(sp.stream().filter(p -> ((Map<String, Object>) p.get("place")).get("roomId").equals(r2))).hasSize(2);

        Map<String, Object> done = bulk.autoCommit(sa, new BoardingBulkService.AutoRequest(null, null, null, null, "FILL", "ANY", true));
        assertThat(done.get("message").toString()).contains("5 students given a room; 1 still waiting");
        assertThat(allocations.findCurrentInRoom(r1)).extracting(RoomAllocation::getBed).containsExactlyInAnyOrder(1, 2, 3);
        assertThat(roomOf(noGender)).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void schoolsKeepClassmatesTogetherOrMixThemAsTheSuperAdminDecides() {
        com.exam.model.academic.SystemMode.setCurrent(com.exam.model.academic.SystemMode.SHS);
        try {
            Long r1 = room(boys, 0, "1", 2), r2 = room(boys, 0, "2", 2);
            sci.setName("SHS 1 Science");
            programs.save(sci);
            Program arts = new Program();
            arts.setName("SHS 1 Arts"); arts.setCode("S1A"); arts.setDurationYears(1); arts.setDepartment(sci.getDepartment()); arts.setEnabled(true);
            arts = programs.save(arts);
            User a = student(Gender.MALE, 1, Residency.BOARDING), b = student(Gender.MALE, 1, Residency.BOARDING), c = student(Gender.MALE, 1, Residency.BOARDING);
            b.setProgram(arts); users.save(b);   // two Science, one Arts; the request's own choice is ignored in schools

            java.util.function.Function<Map<String, Object>, Map<Long, Set<Object>>> classesPerRoom = plan -> {
                Map<Long, Set<Object>> out = new HashMap<>();
                for (Map<String, Object> p : (List<Map<String, Object>>) plan.get("placed"))
                    out.computeIfAbsent((Long) ((Map<String, Object>) p.get("place")).get("roomId"), k -> new HashSet<>()).add(p.get("programme"));
                return out;
            };
            Map<Long, Set<Object>> together = classesPerRoom.apply(bulk.autoPreview(sa, new BoardingBulkService.AutoRequest(null, "MALE", null, null, "FILL", "MIX_LEVELS", false)));
            assertThat(together.values()).allMatch(s -> s.size() == 1);   // every room holds one class

            assertThatThrownBy(() -> settings.save(hm, new BoardingSettings.SettingsRequest(null, null, null, null, "MIXED"), access))
                    .isInstanceOf(AccessDeniedException.class);
            settings.save(sa, new BoardingSettings.SettingsRequest(null, null, null, null, "MIXED"), access);
            Map<Long, Set<Object>> mixed = classesPerRoom.apply(bulk.autoPreview(sa, new BoardingBulkService.AutoRequest(null, "MALE", null, null, "FILL", "SAME_LEVEL", false)));
            assertThat(mixed.get(r1)).containsExactlyInAnyOrder("SHS 1 Science", "SHS 1 Arts");
            assertThat(mixed.get(r2)).containsExactly("SHS 1 Science");
        } finally {
            com.exam.model.academic.SystemMode.setCurrent(com.exam.model.academic.SystemMode.UNIVERSITY);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void keepingLevelsTogetherAndHouseStaffLimits() {
        Long r1 = room(boys, 0, "1", 2), r2 = room(boys, 0, "2", 2);
        student(Gender.MALE, 1, Residency.BOARDING);
        student(Gender.MALE, 2, Residency.BOARDING);
        student(Gender.MALE, 1, Residency.BOARDING);
        Map<String, Object> plan = bulk.autoPreview(sa, new BoardingBulkService.AutoRequest(null, "MALE", null, null, "FILL", "SAME_LEVEL", false));
        Map<Long, Set<Object>> levelsPerRoom = new HashMap<>();
        for (Map<String, Object> p : (List<Map<String, Object>>) plan.get("placed"))
            levelsPerRoom.computeIfAbsent((Long) ((Map<String, Object>) p.get("place")).get("roomId"), k -> new HashSet<>()).add(p.get("level"));
        assertThat(levelsPerRoom.get(r1)).containsExactly(1);
        assertThat(levelsPerRoom.get(r2)).containsExactly(2);

        boarding.addStaff(sa, new BoardingService.StaffRequest(hm.getId(), "HOUSE", boys, null));
        assertThatThrownBy(() -> bulk.autoPreview(hm, new BoardingBulkService.AutoRequest(List.of(girls), null, null, null, "FILL", "ANY", true)))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> bulk.clear(hm, null, false)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void clearingForANewYearEndsStaysWithTheirOwnReason() {
        Long r = room(boys, 0, "1", 4);
        User a = student(Gender.MALE, 1, Residency.BOARDING), b = student(Gender.MALE, 3, Residency.BOARDING);
        boarding.assign(sa, new BoardingService.AssignRequest(a.getId(), r, null, false, null));
        boarding.assign(sa, new BoardingService.AssignRequest(b.getId(), r, null, false, null));
        assertThat(bulk.clear(sa, new BoardingBulkService.ClearRequest(null, 3, null, null), false)).containsEntry("count", 1);
        assertThat(roomOf(b)).isNotNull();                           // only a preview

        bulk.clear(sa, new BoardingBulkService.ClearRequest(null, 3, null, "Completed SHS 3"), true);
        assertThat(roomOf(b)).isNull();
        assertThat(roomOf(a)).isEqualTo(r);
        RoomAllocation ended = allocations.historyOfStudent(b.getId()).get(0);
        assertThat(ended.getEndReason()).isEqualTo(RoomAllocation.EndReason.NEW_YEAR);
        assertThat(ended.getNote()).isEqualTo("Completed SHS 3");
    }

    // ── Reports, announcements, report cards ─────────────────────────────────

    @Test
    void reportsListRoomsInOrderAndHouseStaffSeeOnlyTheirHouse() {
        Long r10 = room(boys, 1, "10", 2), r2 = room(boys, 1, "2", 2);
        Long g = room(girls, 0, "1", 2);
        User a = student(Gender.MALE, 1, Residency.BOARDING), b = student(Gender.MALE, 1, Residency.BOARDING), c = student(Gender.FEMALE, 1, Residency.BOARDING);
        student(Gender.FEMALE, 2, Residency.BOARDING);                // waiting
        boarding.assign(sa, new BoardingService.AssignRequest(a.getId(), r10, 1, false, null));
        boarding.assign(sa, new BoardingService.AssignRequest(b.getId(), r2, 2, false, null));
        boarding.assign(sa, new BoardingService.AssignRequest(c.getId(), g, null, false, null));

        ReportResult roster = reports.roster(ReportFilters.none(), sa);
        List<Map<String, Object>> list = roster.getTables().get(0).getRows();
        assertThat(list).extracting(m -> m.get("room")).containsExactly("Room 2", "Room 10", "Room 1");   // Aggrey (room 2 before 10), then Kwegyir
        assertThat(list.get(0).get("floor")).isEqualTo("First floor");

        boarding.addStaff(sa, new BoardingService.StaffRequest(hm.getId(), "HOUSE", boys, null));
        assertThat(reports.roster(ReportFilters.none(), hm).getTables().get(0).getRows()).hasSize(2);
        assertThatThrownBy(() -> reports.roster(ReportFilters.none().withHostel(girls), hm)).isInstanceOf(AccessDeniedException.class);

        ReportResult occ = reports.occupancy(ReportFilters.none(), sa);
        assertThat(occ.getTables().get(0).getRows()).anySatisfy(m -> {
            assertThat(m.get("house")).isEqualTo("Aggrey House");
            assertThat(m).containsEntry("beds", 4).containsEntry("occupied", 2L).containsEntry("free", 2);
        });
        assertThat(reports.waiting(ReportFilters.none(), sa).getTables().get(0).getRows()).hasSize(1);
        Map<String, Object> cls = reports.residency(ReportFilters.none(), sa).getTables().get(0).getRows().get(0);
        assertThat(cls).containsEntry("boarders", 3).containsEntry("housed", 3);
    }

    @Test
    void announcementsReachOneHouseOrBoardersOrDayStudents() {
        Long r = room(boys, 0, "1", 4);
        User inHouse = student(Gender.MALE, 1, Residency.BOARDING), elsewhere = student(Gender.FEMALE, 1, Residency.BOARDING),
                day = student(Gender.MALE, 1, Residency.DAY);
        boarding.assign(sa, new BoardingService.AssignRequest(inHouse.getId(), r, null, false, null));
        boarding.addStaff(sa, new BoardingService.StaffRequest(hm.getId(), "HOUSE", boys, "Housemaster"));

        AnnouncementService.CreateRequest house = new AnnouncementService.CreateRequest();
        house.title = "Prep tonight";
        house.body = "Prep starts at 7 pm.";
        house.audience = "ALL";
        house.hostelId = boys;
        announcements.create(hm, house);                         // a housemaster posts to their own house
        assertThat(announcements.visibleTo(inHouse)).hasSize(1);
        assertThat(announcements.visibleTo(elsewhere)).isEmpty();
        assertThat(announcements.visibleTo(day)).isEmpty();

        house.hostelId = girls;
        assertThatThrownBy(() -> announcements.create(hm, house)).isInstanceOf(AccessDeniedException.class);
        house.hostelId = null;
        assertThatThrownBy(() -> announcements.create(hm, house)).hasMessageContaining("Choose the house");

        AnnouncementService.CreateRequest days = new AnnouncementService.CreateRequest();
        days.title = "Day students";
        days.body = "Closing time changes.";
        days.audience = "STUDENTS";
        days.residency = "DAY";
        announcements.create(sa, days);
        assertThat(announcements.visibleTo(day)).extracting(m -> m.get("title")).containsExactly("Day students");
        assertThat(announcements.visibleTo(elsewhere)).isEmpty();
    }

    @Test
    void reportCardsCarryTheHouseOrDayStudent() {
        Long r = room(boys, 0, "1", 4);
        User a = student(Gender.MALE, 1, Residency.BOARDING), day = student(Gender.MALE, 1, Residency.DAY);
        boarding.assign(sa, new BoardingService.AssignRequest(a.getId(), r, null, false, null));
        assertThat(boarding.reportLine(a.getUsername())).containsExactly("Residency", "Boarder · Aggrey House");
        assertThat(boarding.reportLine(day.getUsername())).containsExactly("Residency", "Day student");
        settings.setMode(ResidencyMode.BOARDING);
        assertThat(boarding.reportLine(a.getUsername())).containsExactly(settings.word(), "Aggrey House");
        settings.save(sa, new BoardingSettings.SettingsRequest(null, null, null, false, null), access);
        assertThat(boarding.reportLine(a.getUsername())).isNull();
    }
}
