package com.exam.service.reports;

import com.exam.model.Gender;
import com.exam.model.Residency;
import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.boarding.Hostel;
import com.exam.model.boarding.HostelRoom;
import com.exam.model.boarding.ResidencyMode;
import com.exam.model.boarding.RoomAllocation;
import com.exam.repository.HostelRepository;
import com.exam.repository.HostelRoomRepository;
import com.exam.repository.RoomAllocationRepository;
import com.exam.repository.UserRepository;
import com.exam.service.boarding.BoardingAccess;
import com.exam.service.boarding.BoardingSettings;
import com.exam.service.boarding.BoardingViews;
import com.exam.service.boarding.HostelService;
import com.exam.service.comms.CurrentUserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

import static com.exam.service.reports.ReportResult.*;
import static com.exam.service.reports.ReportSupport.*;

/**
 * Boarding reports: who sleeps where (door lists), how full each house and floor is, boarders still
 * waiting for a room, day / boarding numbers per class, and room changes over a period.
 * Housemasters see only the houses they look after; the head of boarding and the Super Admin see all.
 */
@Service
@Transactional(readOnly = true)
public class BoardingReports {

    /** Gender choices offered as the waiting list's "status" filter. */
    public static final List<String> GENDER_CHOICES = List.of("MALE", "FEMALE", "NONE");

    @Autowired private ReportSupport support;
    @Autowired private HostelRepository hostels;
    @Autowired private HostelRoomRepository rooms;
    @Autowired private RoomAllocationRepository allocations;
    @Autowired private UserRepository users;
    @Autowired private BoardingSettings settings;
    @Autowired private BoardingAccess access;

    /** The houses this viewer may report on, narrowed to the chosen one. */
    private List<Hostel> houses(ReportFilters f, User viewer, boolean archivedToo) {
        boolean head = viewer.getRole() == Role.SUPER_ADMIN || access.isHead(viewer);
        Set<Long> mine = head ? null : access.houseHostelIds(viewer);
        if (f.hostelId() != null && mine != null && !mine.contains(f.hostelId()))
            throw new AccessDeniedException("You don't look after that " + settings.word().toLowerCase() + ".");
        return hostels.findAllByOrderByNameAsc().stream()
                .filter(h -> archivedToo || h.isActive())
                .filter(h -> mine == null || mine.contains(h.getId()))
                .filter(h -> f.hostelId() == null || h.getId().equals(f.hostelId()))
                .toList();
    }

    private void houseScope(ReportResult r, ReportFilters f, List<Hostel> hs, User viewer) {
        String word = settings.word();
        if (f.hostelId() != null) r.scope(word + ": " + (hs.isEmpty() ? "?" : hs.get(0).getName()));
        else r.scope(word + ": " + (viewer.getRole() == Role.SUPER_ADMIN || access.isHead(viewer) ? "All " + settings.plural().toLowerCase()
                : "The " + settings.plural().toLowerCase() + " you look after"));
    }

    private String levelText(Integer level) {
        // Schools: a class is one year; the column is dropped when it has nothing to say
        return level == null || support.isSchool() ? null : support.terms().get("level") + " " + level;
    }

    private static String genderText(Gender g) {
        return g == Gender.MALE ? "Male" : g == Gender.FEMALE ? "Female" : "Not recorded";
    }

    private String floorText(HostelRoom r) {
        return BoardingViews.floorsOf(r) > 1 ? settings.floorLabel(r.getFloor()) : null;
    }

    private boolean studentMatches(User s, ReportFilters f, Lookups l) {
        if (f.level() != null && !f.level().equals(s.getCurrentLevel())) return false;
        if (f.programId() != null && (s.getProgram() == null || !f.programId().equals(s.getProgram().getId()))) return false;
        if (f.departmentId() != null) {
            Long dept = s.getProgram() != null ? l.deptOfProgram(s.getProgram().getId()) : s.getDepartment() != null ? s.getDepartment().getId() : null;
            if (!f.departmentId().equals(dept)) return false;
        }
        return true;
    }

    private static final Comparator<RoomAllocation> BY_PLACE = Comparator
            .comparing((RoomAllocation a) -> a.getRoom().getHostel().getName().toLowerCase())
            .thenComparing(a -> a.getRoom(), HostelService.ROOM_ORDER)
            .thenComparing(a -> a.getBed() == null ? Integer.MAX_VALUE : a.getBed())
            .thenComparing(a -> CurrentUserService.displayName(a.getStudent()).toLowerCase());

    // ── Roster (door lists) ──────────────────────────────────────────────────

    public ReportResult roster(ReportFilters f, User viewer) {
        Map<String, String> t = support.terms();
        Lookups l = support.lookups();
        List<Hostel> hs = houses(f, viewer, false);
        Set<Long> ids = hs.stream().map(Hostel::getId).collect(Collectors.toSet());
        String word = settings.word();
        ReportResult r = new ReportResult("boarding-roster", word + " roster",
                "Every boarder in each room, in building order (" + word.toLowerCase() + ", block, floor, room, bed): print it as door lists or a roll-call sheet.");
        houseScope(r, f, hs, viewer);
        support.placeScope(r, f, l);

        List<RoomAllocation> stays = allocations.findAllCurrent().stream()
                .filter(a -> ids.contains(a.getRoom().getHostel().getId()) && studentMatches(a.getStudent(), f, l))
                .sorted(BY_PLACE).toList();
        // Room first: it stays pinned while the table scrolls sideways on a phone (a door list is read room by room)
        Table tb = r.table("roster", "Who sleeps where").text("room", "Room").col("bed", "Bed", INT).text("name", "Name")
                .text("studentId", t.get("studentId")).text("level", t.get("level")).text("program", t.get("program")).text("gender", "Gender")
                .text("house", word).text("block", "Block").text("floor", "Floor").col("since", "Since", DATE)
                .emptyText("Nobody lives in these rooms yet.");
        for (RoomAllocation a : stays) {
            User s = a.getStudent();
            HostelRoom room = a.getRoom();
            tb.add(BoardingViews.roomName(room), a.getBed(), name(s), s.getUsername(), levelText(s.getCurrentLevel()),
                    s.getProgram() != null ? s.getProgram().getName() : null, genderText(s.getGender()),
                    room.getHostel().getName(), room.getBlock() != null ? room.getBlock().getName() : null, floorText(room), day(a.getStartedAt()));
        }
        r.stat("Boarders listed", stays.size());
        r.stat("Rooms in use", stays.stream().map(a -> a.getRoom().getId()).distinct().count());
        r.stat(settings.plural(), hs.size());
        r.note("Students are listed by " + word.toLowerCase() + ", block, floor and room, in the order rooms are numbered.");
        return r;
    }

    // ── Occupancy ────────────────────────────────────────────────────────────

    public ReportResult occupancy(ReportFilters f, User viewer) {
        List<Hostel> hs = houses(f, viewer, false);
        Set<Long> ids = hs.stream().map(Hostel::getId).collect(Collectors.toSet());
        String word = settings.word();
        ReportResult r = new ReportResult("boarding-occupancy", "Beds and occupancy",
                "Beds, occupied beds and free beds per " + word.toLowerCase() + " and per floor, with closed rooms.");
        houseScope(r, f, hs, viewer);

        List<HostelRoom> rs = rooms.findAllInUse().stream().filter(x -> ids.contains(x.getHostel().getId())).toList();
        Map<Long, Long> in = allocations.findAllCurrent().stream().collect(Collectors.groupingBy(a -> a.getRoom().getId(), Collectors.counting()));

        Table houses = r.table("houses", settings.plural()).text("house", word).text("for", "For").col("rooms", "Rooms", INT).col("closed", "Closed rooms", INT)
                .col("beds", "Beds (open rooms)", INT).col("occupied", "Living there", INT).col("free", "Free beds", INT).col("full", "Full", PERCENT)
                .chart("house", "occupied").emptyText("No " + settings.plural().toLowerCase() + " yet.");
        int totalBeds = 0, totalFree = 0;
        long totalIn = 0;
        for (Hostel h : hs) {
            List<HostelRoom> mine = rs.stream().filter(x -> x.getHostel().getId().equals(h.getId())).toList();
            int beds = 0, free = 0, closed = 0;
            long living = 0;
            for (HostelRoom x : mine) {
                long n = in.getOrDefault(x.getId(), 0L);
                living += n;
                if (x.getStatus() == HostelRoom.Status.CLOSED) { closed++; continue; }
                beds += x.getCapacity();
                free += Math.max(0, x.getCapacity() - (int) n);
            }
            totalBeds += beds; totalFree += free; totalIn += living;
            houses.add(h.getName(), h.getGender().label(), mine.size(), closed, beds, living, free, pct(beds - free, beds));
        }

        Table floors = r.table("floors", "By block and floor").text("house", word).text("block", "Block").text("floor", "Floor")
                .col("rooms", "Rooms", INT).col("beds", "Beds (open rooms)", INT).col("occupied", "Living there", INT).col("free", "Free beds", INT);
        Map<String, List<HostelRoom>> byFloor = rs.stream().sorted(Comparator.comparing((HostelRoom x) -> x.getHostel().getName().toLowerCase())
                        .thenComparing(HostelService.ROOM_ORDER))
                .collect(Collectors.groupingBy(x -> x.getHostel().getId() + "|" + (x.getBlock() == null ? "-" : x.getBlock().getId()) + "|" + x.getFloor(),
                        LinkedHashMap::new, Collectors.toList()));
        for (List<HostelRoom> g : byFloor.values()) {
            HostelRoom first = g.get(0);
            int beds = 0, free = 0;
            long living = 0;
            for (HostelRoom x : g) {
                long n = in.getOrDefault(x.getId(), 0L);
                living += n;
                if (x.getStatus() == HostelRoom.Status.CLOSED) continue;
                beds += x.getCapacity();
                free += Math.max(0, x.getCapacity() - (int) n);
            }
            floors.add(first.getHostel().getName(), first.getBlock() != null ? first.getBlock().getName() : null,
                    floorText(first) != null ? floorText(first) : "Single storey", g.size(), beds, living, free);
        }
        r.stat("Beds", totalBeds);
        r.stat("Living there", totalIn);
        r.stat("Free beds", totalFree, null, totalFree == 0 && totalBeds > 0 ? "warn" : null);
        r.stat("Full", pct(totalBeds - totalFree, totalBeds) == null ? "—" : round1(pct(totalBeds - totalFree, totalBeds)) + "%");
        r.note("Closed rooms are not counted in beds or free beds; anyone still living in one is counted as living there.");
        return r;
    }

    // ── Waiting for a room ───────────────────────────────────────────────────

    public ReportResult waiting(ReportFilters f, User viewer) {
        Map<String, String> t = support.terms();
        Lookups l = support.lookups();
        ReportResult r = new ReportResult("boarding-waiting", "Boarders waiting for a room",
                "Active boarders who have no room yet, with how to reach them.");
        support.placeScope(r, f, l);
        if (f.hasStatus()) r.scope("Gender: " + genderChoice(f.status()));
        Set<Long> housed = allocations.findAllCurrent().stream().map(a -> a.getStudent().getId()).collect(Collectors.toSet());
        List<User> list = users.findByRole(Role.NORMAL).stream()
                .filter(s -> s.isEnabled() && settings.isBoarder(s) && !housed.contains(s.getId()) && studentMatches(s, f, l))
                .filter(s -> !f.hasStatus() || ("NONE".equals(f.status()) ? s.getGender() == null : s.getGender() != null && s.getGender().name().equals(f.status())))
                .sorted(Comparator.comparing((User s) -> s.getCurrentLevel() == null ? Integer.MAX_VALUE : s.getCurrentLevel())
                        .thenComparing(s -> name(s).toLowerCase()))
                .toList();
        Table tb = r.table("waiting", "Waiting").text("studentId", t.get("studentId")).text("name", "Name").text("level", t.get("level"))
                .text("program", t.get("program")).text("gender", "Gender").text("email", "Email").text("phone", "Phone")
                .emptyText("Every boarder has a room.");
        for (User s : list)
            tb.add(s.getUsername(), name(s), levelText(s.getCurrentLevel()), s.getProgram() != null ? s.getProgram().getName() : null,
                    genderText(s.getGender()), s.getEmail(), s.getPhone());
        r.stat("Waiting", list.size(), null, list.isEmpty() ? "good" : "warn");
        long noGender = list.stream().filter(s -> s.getGender() == null).count();
        if (noGender > 0) r.stat("No gender recorded", noGender, "Can only go into mixed rooms", "warn");
        return r;
    }

    static String genderChoice(String v) {
        return switch (v) { case "MALE" -> "Male"; case "FEMALE" -> "Female"; default -> "Not recorded"; };
    }

    // ── Day and boarding numbers ─────────────────────────────────────────────

    public ReportResult residency(ReportFilters f, User viewer) {
        Map<String, String> t = support.terms();
        Lookups l = support.lookups();
        boolean both = settings.mode() == ResidencyMode.BOTH;
        ReportResult r = new ReportResult("boarding-residency", both ? "Day and boarding numbers" : "Boarders by class",
                (both ? "Day students and boarders" : "Boarders") + " per " + t.get("program").toLowerCase() + " and " + t.get("level").toLowerCase()
                        + ", boys and girls, and how many boarders have a room.");
        support.placeScope(r, f, l);
        Set<Long> housed = allocations.findAllCurrent().stream().map(a -> a.getStudent().getId()).collect(Collectors.toSet());

        record Key(String program, Integer level) {}
        Map<Key, int[]> rows = new TreeMap<>(Comparator.comparing((Key k) -> Objects.toString(k.program(), "~"))
                .thenComparing(k -> k.level() == null ? Integer.MAX_VALUE : k.level()));
        int[] total = new int[8];
        for (User s : users.findByRole(Role.NORMAL)) {
            if (!s.isEnabled() || !studentMatches(s, f, l)) continue;
            int[] c = rows.computeIfAbsent(new Key(s.getProgram() != null ? s.getProgram().getName() : null, s.getCurrentLevel()), k -> new int[8]);
            Residency res = settings.residencyOf(s);
            // 0 students, 1 day, 2 boarders, 3 boy boarders, 4 girl boarders, 5 no gender, 6 housed, 7 not recorded
            int[][] targets = {c, total};
            for (int[] x : targets) {
                x[0]++;
                if (res == Residency.DAY) x[1]++;
                else if (res == Residency.BOARDING) {
                    x[2]++;
                    if (s.getGender() == Gender.MALE) x[3]++; else if (s.getGender() == Gender.FEMALE) x[4]++; else x[5]++;
                    if (housed.contains(s.getId())) x[6]++;
                } else x[7]++;
            }
        }
        Table tb = r.table("classes", "By class").text("program", t.get("program")).text("level", t.get("level")).col("students", t.get("student") + "s", INT);
        if (both) tb.col("day", "Day students", INT);
        tb.col("boarders", "Boarders", INT).col("boys", "Boy boarders", INT).col("girls", "Girl boarders", INT).col("noGender", "Gender not recorded", INT)
                .col("housed", "Have a room", INT).col("waiting", "Waiting for a room", INT);
        if (both) tb.col("unset", "Residency not recorded", INT);
        for (Map.Entry<Key, int[]> e : rows.entrySet()) {
            int[] c = e.getValue();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("program", e.getKey().program());
            row.put("level", levelText(e.getKey().level()));
            row.put("students", c[0]);
            if (both) row.put("day", c[1]);
            row.put("boarders", c[2]); row.put("boys", c[3]); row.put("girls", c[4]); row.put("noGender", c[5]);
            row.put("housed", c[6]); row.put("waiting", c[2] - c[6]);
            if (both) row.put("unset", c[7]);
            tb.add(row);
        }
        r.stat(t.get("student") + "s", total[0]);
        if (both) r.stat("Day students", total[1]);
        r.stat("Boarders", total[2]);
        r.stat("Waiting for a room", total[2] - total[6], null, total[2] - total[6] > 0 ? "warn" : "good");
        if (both && total[7] > 0) r.stat("Residency not recorded", total[7], null, "warn");
        r.note("Active accounts only. " + (both ? "" : "Every student is a boarder at this institution."));
        return r;
    }

    // ── Room changes ─────────────────────────────────────────────────────────

    public ReportResult moves(ReportFilters f, User viewer) {
        Map<String, String> t = support.terms();
        Lookups l = support.lookups();
        List<Hostel> hs = houses(f, viewer, true);
        Set<Long> ids = hs.stream().map(Hostel::getId).collect(Collectors.toSet());
        Period p = support.period(f);
        ReportResult r = new ReportResult("boarding-moves", "Room changes",
                "Every time a student was given a room or left one in the period: moves, swaps, day students, leavers, and who made the change.");
        support.periodScope(r, p);
        houseScope(r, f, hs, viewer);

        record Event(java.time.LocalDateTime at, RoomAllocation a, boolean in) {}
        List<Event> events = new ArrayList<>();
        for (RoomAllocation a : allocations.findAll()) {
            if (!ids.contains(a.getRoom().getHostel().getId()) || !studentMatches(a.getStudent(), f, l)) continue;
            if (p.contains(a.getStartedAt())) events.add(new Event(a.getStartedAt(), a, true));
            if (a.getEndedAt() != null && p.contains(a.getEndedAt())) events.add(new Event(a.getEndedAt(), a, false));
        }
        events.sort(Comparator.comparing(Event::at).reversed());
        Table tb = r.table("changes", "Changes").col("when", "When", DATETIME).text("studentId", t.get("studentId")).text("name", "Name")
                .text("event", "What happened").text("place", "Room").text("reason", "Why").text("by", "By").text("note", "Note")
                .emptyText("No room changes in this period.");
        int in = 0, out = 0;
        for (Event e : events) {
            RoomAllocation a = e.a();
            String place = a.getRoom().getHostel().getName() + " · " + BoardingViews.roomName(a.getRoom()) + (a.getBed() != null ? " · Bed " + a.getBed() : "");
            if (e.in()) in++; else out++;
            tb.add(minute(e.at()), a.getStudent().getUsername(), name(a.getStudent()), e.in() ? "Given a room" : "Left the room", place,
                    e.in() ? null : reasonText(a.getEndReason()), e.in() ? a.getAssignedBy() : a.getEndedBy(), a.getNote());
        }
        r.stat("Given a room", in);
        r.stat("Left a room", out);
        r.note("A move or swap shows twice: leaving the old room and being given the new one.");
        return r;
    }

    static String reasonText(RoomAllocation.EndReason reason) {
        if (reason == null) return null;
        return switch (reason) {
            case MOVED -> "Moved to another room";
            case SWAPPED -> "Swapped rooms";
            case BECAME_DAY -> "Became a day student";
            case DEACTIVATED -> "Account deactivated";
            case VACATED -> "Taken out of the room";
            case NEW_YEAR -> "Rooms cleared for the new year";
        };
    }
}
