package com.exam.service.boarding;

import com.exam.model.Gender;
import com.exam.model.Residency;
import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.boarding.HostelRoom;
import com.exam.model.boarding.RoomAllocation;
import com.exam.model.boarding.ResidencyMode;
import com.exam.repository.*;
import com.exam.service.SystemSettingService;
import com.exam.service.academic.AcademicSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Boarding on H2: the residency modes, building hostels / blocks / floors / rooms, who fits which
 * room (gender, level, closed, full, beds), moves and swaps with history, what each kind of boarding
 * staff may do, and the guards that stop a change from stranding a student.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.datasource.url=jdbc:h2:mem:boarding;MODE=MySQL;NON_KEYWORDS=USER,LEVEL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.show-sql=false",
})
@Import({BoardingService.class, HostelService.class, BoardingSettings.class, BoardingAccess.class,
        SystemSettingService.class, AcademicSessionService.class, com.exam.service.comms.CurrentUserService.class})
class BoardingJpaTest {

    @Autowired BoardingService boarding;
    @Autowired HostelService hostels;
    @Autowired BoardingSettings settings;
    @Autowired UserRepository users;
    @Autowired RoomAllocationRepository allocations;
    @Autowired HostelRoomRepository rooms;

    User sa, housemaster, otherTeacher, kofi, kwame, yaw, ama, esi;
    Long boysHouse, girlsHouse;

    @BeforeEach
    void setUp() {
        settings.setMode(ResidencyMode.BOTH);
        sa = user("sa", Role.SUPER_ADMIN, null, null);
        housemaster = user("hm1", Role.LECTURER, null, null);
        otherTeacher = user("t2", Role.LECTURER, null, null);
        kofi = user("s1", Role.NORMAL, Gender.MALE, Residency.BOARDING);
        kwame = user("s2", Role.NORMAL, Gender.MALE, Residency.BOARDING);
        yaw = user("s3", Role.NORMAL, Gender.MALE, Residency.BOARDING);
        ama = user("s4", Role.NORMAL, Gender.FEMALE, Residency.BOARDING);
        esi = user("s5", Role.NORMAL, null, Residency.DAY);

        boysHouse = id(hostels.saveHostel(sa, null, new HostelService.HostelRequest("Aggrey House", "AGH", "BOYS", 3, null, null)));
        girlsHouse = id(hostels.saveHostel(sa, null, new HostelService.HostelRequest("Kwegyir House", null, "GIRLS", 1, null, null)));
    }

    private User user(String username, Role role, Gender gender, Residency residency) {
        User u = new User();
        u.setUsername(username);
        u.setEmail(username + "@example.com");
        u.setFirstname(username);
        u.setLastname("Test");
        u.setPassword("x");
        u.setRole(role);
        u.setEnabled(true);
        u.setGender(gender);
        u.setResidency(residency);
        if (role == Role.NORMAL) u.setCurrentLevel(1);
        return users.save(u);
    }

    private static Long id(Map<String, Object> m) { return ((Number) m.get("id")).longValue(); }

    private Long room(Long hostel, int floor, String name, int beds) {
        return id(hostels.saveRoom(sa, hostel, null, new HostelService.RoomRequest(null, floor, name, beds, null, "OPEN", null, null)));
    }

    private Long assign(User actor, User student, Long room, Integer bed) {
        return ((Number) boarding.assign(actor, new BoardingService.AssignRequest(student.getId(), room, bed, false, null)).get("allocationId")).longValue();
    }

    @Test
    void dayOnlyHidesBoardingAndBothFillsResidencyFromThePreviousMode() {
        settings.setMode(ResidencyMode.DAY);
        assertThatThrownBy(() -> hostels.overview(sa, false)).isInstanceOf(BoardingSettings.BoardingOffException.class);
        assertThat(boarding.myRoom(kofi).get("residency")).isEqualTo("DAY");

        User unknown = user("s9", Role.NORMAL, Gender.MALE, null);
        settings.setMode(ResidencyMode.BOARDING);
        assertThat(settings.isBoarder(esi)).isTrue();          // everyone boards in a boarding school
        settings.setMode(ResidencyMode.BOTH);                  // from boarding-only: unrecorded students are boarders
        assertThat(users.findById(unknown.getId()).orElseThrow().getResidency()).isEqualTo(Residency.BOARDING);
        assertThat(users.findById(esi.getId()).orElseThrow().getResidency()).isEqualTo(Residency.DAY);   // recorded ones kept
    }

    @Test
    void genderLevelClosedAndFullRoomsAreRefused() {
        Long r1 = room(boysHouse, 1, "101", 2);
        Long g1 = room(girlsHouse, 0, "1", 4);

        assertThatThrownBy(() -> assign(sa, ama, r1, null)).hasMessageContaining("for boys only");
        assertThatThrownBy(() -> assign(sa, kofi, g1, null)).hasMessageContaining("for girls only");

        User noGender = user("s7", Role.NORMAL, null, Residency.BOARDING);
        assertThatThrownBy(() -> assign(sa, noGender, r1, null)).hasMessageContaining("Record s7 Test's gender first");

        assign(sa, kofi, r1, 1);
        assertThatThrownBy(() -> assign(sa, kwame, r1, 1)).hasMessageContaining("Bed 1 is taken by s1 Test");
        assertThatThrownBy(() -> assign(sa, kwame, r1, 3)).hasMessageContaining("bed from 1 to 2");
        assign(sa, kwame, r1, 2);
        assertThatThrownBy(() -> assign(sa, yaw, r1, null)).hasMessageContaining("is full (2 of 2 beds taken)");
        assertThatThrownBy(() -> assign(sa, kofi, room(boysHouse, 0, "1", 4), null)).hasMessageContaining("already lives in");

        Long closed = id(hostels.saveRoom(sa, boysHouse, null, new HostelService.RoomRequest(null, 0, "Sick bay", 2, null, "CLOSED", null, null)));
        assertThatThrownBy(() -> assign(sa, yaw, closed, null)).hasMessageContaining("is closed");

        Long formTwo = id(hostels.saveRoom(sa, boysHouse, null, new HostelService.RoomRequest(null, 2, "201", 4, null, "OPEN", null, "2, 3")));
        assertThatThrownBy(() -> assign(sa, yaw, formTwo, null)).hasMessageContaining("takes only Level 2, 3");
    }

    @Test
    void dayStudentsMustBeMadeBoardersAndBecomingDayEndsTheStay() {
        Long r = room(girlsHouse, 0, "1", 4);
        esi.setGender(Gender.FEMALE);
        users.save(esi);
        assertThatThrownBy(() -> assign(sa, esi, r, null)).hasMessageContaining("is a day student");
        boarding.assign(sa, new BoardingService.AssignRequest(esi.getId(), r, null, true, null));
        assertThat(users.findById(esi.getId()).orElseThrow().getResidency()).isEqualTo(Residency.BOARDING);

        boarding.updateStudent(sa, esi.getId(), new BoardingService.StudentRequest(null, "DAY"));
        RoomAllocation last = allocations.historyOfStudent(esi.getId()).get(0);
        assertThat(last.isCurrent()).isFalse();
        assertThat(last.getEndReason()).isEqualTo(RoomAllocation.EndReason.BECAME_DAY);

        // A gender change that no longer fits the room is refused
        assign(sa, ama, r, null);
        assertThatThrownBy(() -> boarding.updateStudent(sa, ama.getId(), new BoardingService.StudentRequest("MALE", null)))
                .hasMessageContaining("for girls only. Move them first");
    }

    @Test
    void moveAndSwapKeepHistoryAndFreeTheBeds() {
        Long r1 = room(boysHouse, 0, "1", 2), r2 = room(boysHouse, 1, "101", 1);
        Long a = assign(sa, kofi, r1, 1);
        Long b = assign(sa, kwame, r2, 1);

        boarding.swap(sa, new BoardingService.SwapRequest(a, b));
        assertThat(allocations.findCurrent(kofi.getId()).orElseThrow().getRoom().getId()).isEqualTo(r2);
        assertThat(allocations.findCurrent(kwame.getId()).orElseThrow().getRoom().getId()).isEqualTo(r1);
        assertThat(allocations.findCurrent(kwame.getId()).orElseThrow().getBed()).isEqualTo(1);

        Long kofiNow = allocations.findCurrent(kofi.getId()).orElseThrow().getId();
        boarding.move(sa, kofiNow, new BoardingService.MoveRequest(r1, 2, "Moved nearer the prefect"));
        List<RoomAllocation> history = allocations.historyOfStudent(kofi.getId());
        assertThat(history).hasSize(3);
        assertThat(history.stream().filter(RoomAllocation::isCurrent)).hasSize(1);
        assertThat(history.stream().map(RoomAllocation::getEndReason).filter(Objects::nonNull))
                .containsExactlyInAnyOrder(RoomAllocation.EndReason.SWAPPED, RoomAllocation.EndReason.MOVED);
        assign(sa, yaw, r2, 1);               // the bed kofi left is free again

        Map<String, Object> place = (Map<String, Object>) boarding.myRoom(kofi).get("place");
        assertThat((List<String>) place.get("path")).containsExactly("Aggrey House", "Ground floor", "Room 1", "Bed 2");
        assertThat((List<?>) boarding.myRoom(kofi).get("roommates")).hasSize(1);
    }

    @Test
    void houseStaffManageOnlyTheirOwnHostelAndHeadsManageEverything() {
        Long boys = room(boysHouse, 0, "1", 4), girls = room(girlsHouse, 0, "1", 4);
        assertThatThrownBy(() -> hostels.overview(housemaster, false)).isInstanceOf(AccessDeniedException.class);

        boarding.addStaff(sa, new BoardingService.StaffRequest(housemaster.getId(), "HOUSE", boysHouse, "Housemaster"));
        assign(housemaster, kofi, boys, null);
        assertThatThrownBy(() -> assign(housemaster, ama, girls, null)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> hostels.saveRoom(housemaster, boysHouse, null, new HostelService.RoomRequest(null, 0, "2", 4, null, null, null, null)))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(boarding.find(housemaster, "s1", null)).hasSize(1);
        assertThat(boarding.flagsFor(housemaster)).containsEntry("staff", true).containsEntry("head", false);

        boarding.addStaff(sa, new BoardingService.StaffRequest(otherTeacher.getId(), "HEAD", null, "Senior Housemaster"));
        assign(otherTeacher, ama, girls, null);
        assertThatThrownBy(() -> boarding.addStaff(sa, new BoardingService.StaffRequest(otherTeacher.getId(), "HEAD", null, null)))
                .hasMessageContaining("already has this duty");
        assertThatThrownBy(() -> boarding.addStaff(sa, new BoardingService.StaffRequest(kofi.getId(), "HEAD", null, null)))
                .hasMessageContaining("Only teaching staff");
    }

    @Test
    void changesCannotStrandStudents() {
        Long top = room(boysHouse, 2, "301", 3);
        assign(sa, kofi, top, 3);
        assign(sa, kwame, top, null);

        assertThatThrownBy(() -> hostels.saveRoom(sa, null, top, new HostelService.RoomRequest(null, 2, "301", 1, null, "OPEN", null, null)))
                .hasMessageContaining("needs at least 2 beds");
        assertThatThrownBy(() -> hostels.saveRoom(sa, null, top, new HostelService.RoomRequest(null, 2, "301", 2, null, "OPEN", null, null)))
                .hasMessageContaining("Bed 3 is in use");
        assertThatThrownBy(() -> hostels.saveHostel(sa, boysHouse, new HostelService.HostelRequest("Aggrey House", null, "BOYS", 2, null, null)))
                .hasMessageContaining("floors you are removing");
        assertThatThrownBy(() -> hostels.saveHostel(sa, boysHouse, new HostelService.HostelRequest("Aggrey House", null, "GIRLS", 3, null, null)))
                .hasMessageContaining("no longer for them");
        assertThatThrownBy(() -> hostels.deleteHostel(sa, boysHouse)).hasMessageContaining("2 students live in Aggrey House");
        assertThatThrownBy(() -> hostels.deleteRoom(sa, top)).hasMessageContaining("Move them first");
        assertThatThrownBy(() -> room(boysHouse, 3, "401", 2)).hasMessageContaining("has 3 floors");
        assertThatThrownBy(() -> room(boysHouse, 2, "301", 2)).hasMessageContaining("already a room called 301");

        // Once empty, a room with history is archived (name free again); one never used is deleted
        allocations.findCurrentInRoom(top).forEach(a -> boarding.vacate(sa, a.getId(), null));
        assertThat(hostels.deleteRoom(sa, top)).containsEntry("archived", true);
        room(boysHouse, 2, "301", 2);
        Long unused = room(boysHouse, 0, "G1", 2);
        assertThat(hostels.deleteRoom(sa, unused)).containsEntry("archived", false);
    }

    @Test
    void blocksGeneratorAndOccupancy() {
        Long block = id(hostels.saveBlock(sa, girlsHouse, null, new HostelService.BlockRequest("Block B", 4, null)));
        Map<String, Object> made = hostels.generateRooms(sa, girlsHouse,
                new HostelService.GenerateRequest(block, 2, "B2-", 1, 12, true, 8, null, null));
        assertThat((List<String>) made.get("created")).hasSize(12).startsWith("B2-01", "B2-02").endsWith("B2-12");
        Map<String, Object> again = hostels.generateRooms(sa, girlsHouse,
                new HostelService.GenerateRequest(block, 2, "B2-", 11, 13, true, 8, null, null));
        assertThat((List<String>) again.get("skipped")).containsExactly("B2-11", "B2-12");

        Long r = rooms.findForHostel(girlsHouse).stream().filter(x -> x.getName().equals("B2-01")).findFirst().map(HostelRoom::getId).orElseThrow();
        assign(sa, ama, r, 5);
        Map<String, Object> place = (Map<String, Object>) boarding.myRoom(ama).get("place");
        assertThat((List<String>) place.get("path")).containsExactly("Kwegyir House", "Block B", "Second floor", "Room B2-01", "Bed 5");

        Map<String, Object> card = ((List<Map<String, Object>>) hostels.overview(sa, false).get("hostels")).stream()
                .filter(h -> h.get("name").equals("Kwegyir House")).findFirst().orElseThrow();
        assertThat(card).containsEntry("rooms", 13).containsEntry("beds", 104).containsEntry("occupied", 1L).containsEntry("free", 103);
        assertThatThrownBy(() -> hostels.deleteBlock(sa, block)).hasMessageContaining("still has rooms");
    }

    @Test
    void deactivationFreesTheRoomAndWaitingListShowsBoardersWithoutOne() {
        Long r = room(boysHouse, 0, "1", 4);
        assign(sa, kofi, r, null);
        Map<String, Object> waiting = boarding.awaiting(sa, null, null, null, null);
        assertThat(waiting.get("total")).isEqualTo(3);          // kwame, yaw, ama (esi is a day student)
        assertThat(boarding.awaiting(sa, "FEMALE", null, null, null).get("matching")).isEqualTo(1);

        boarding.onDeactivated(kofi, sa);
        assertThat(allocations.findCurrent(kofi.getId())).isEmpty();
        assertThat(allocations.historyOfStudent(kofi.getId()).get(0).getEndReason()).isEqualTo(RoomAllocation.EndReason.DEACTIVATED);
    }

    @Test
    void theDatabaseRefusesASecondCurrentRoomForOneStudent() {
        Long r1 = room(boysHouse, 0, "1", 4), r2 = room(boysHouse, 0, "2", 4);
        assign(sa, kofi, r1, null);
        RoomAllocation dup = new RoomAllocation();
        dup.setStudent(kofi);
        dup.setRoom(rooms.findById(r2).orElseThrow());
        dup.setStartedAt(LocalDateTime.now());
        dup.setCurrentStudentId(kofi.getId());
        assertThatThrownBy(() -> allocations.saveAndFlush(dup)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void wordingAndFloorNames() {
        assertThat(BoardingSettings.plural("Dormitory")).isEqualTo("Dormitories");
        assertThat(BoardingSettings.plural("House")).isEqualTo("Houses");
        assertThat(BoardingSettings.floorLabel(0, true)).isEqualTo("Ground floor");
        assertThat(BoardingSettings.floorLabel(12, true)).isEqualTo("12th floor");
        assertThat(BoardingSettings.floorLabel(0, false)).isEqualTo("Floor 1");
        assertThat(HostelService.natural("2", "10")).isNegative();
        assertThat(HostelService.natural("B2-09", "B2-10")).isNegative();
    }
}
