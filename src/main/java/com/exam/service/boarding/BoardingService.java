package com.exam.service.boarding;

import com.exam.model.Gender;
import com.exam.model.Residency;
import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.academic.AcademicSession;
import com.exam.model.academic.SystemMode;
import com.exam.model.boarding.*;
import com.exam.repository.*;
import com.exam.service.academic.AcademicSessionService;
import com.exam.service.comms.CurrentUserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Who lives where: giving a boarder a room (and bed), moving, swapping and vacating, the boarders
 * still waiting for a room, finding any student's room, a student's own "My room", and the
 * boarding staff list. Every stay is kept as history; the rules for who fits where are in
 * {@link #whyNot}.
 */
@Service
public class BoardingService {

    @Autowired private HostelRoomRepository rooms;
    @Autowired private RoomAllocationRepository allocations;
    @Autowired private BoardingStaffRepository staff;
    @Autowired private HostelRepository hostels;
    @Autowired private UserRepository users;
    @Autowired private BoardingSettings settings;
    @Autowired private BoardingAccess access;
    @Autowired private AcademicSessionService academicSessions;

    // ── Giving, moving, swapping and vacating rooms ─────────────────────────

    /** {@code makeBoarder}: the student is a day student (or not recorded) and staff confirmed making them a boarder. */
    public record AssignRequest(Long studentId, Long roomId, Integer bed, Boolean makeBoarder, String note) {}
    public record MoveRequest(Long roomId, Integer bed, String note) {}
    public record SwapRequest(Long firstAllocationId, Long secondAllocationId) {}
    public record VacateRequest(String note) {}

    @Transactional
    public Map<String, Object> assign(User actor, AssignRequest req) {
        settings.requireOn();
        if (req == null || req.studentId() == null || req.roomId() == null) throw new IllegalArgumentException("Choose a student and a room.");
        HostelRoom room = rooms.lockById(req.roomId()).orElseThrow(() -> new IllegalArgumentException("That room no longer exists."));
        access.requireHostel(actor, room.getHostel().getId());
        User student = studentOrThrow(req.studentId());
        if (!student.isEnabled()) throw new IllegalArgumentException(name(student) + "'s account is deactivated.");

        if (!settings.isBoarder(student)) {
            if (settings.mode() != ResidencyMode.BOTH) throw new IllegalArgumentException(name(student) + " is not a boarder.");
            if (!Boolean.TRUE.equals(req.makeBoarder()))
                throw new IllegalArgumentException(name(student) + (student.getResidency() == Residency.DAY
                        ? " is a day student. Make them a boarder first." : " has no residency recorded. Make them a boarder first."));
            student.setResidency(Residency.BOARDING);
            users.save(student);
        }
        allocations.findCurrent(student.getId()).ifPresent(a -> {
            throw new IllegalArgumentException(name(student) + " already lives in " + BoardingViews.place(a, settings).get("short")
                    + ". Move them instead.");
        });
        String why = whyNot(student, room, req.bed(), Set.of());
        if (why != null) throw new IllegalArgumentException(why);
        RoomAllocation a = start(student, room, req.bed(), req.note(), actor);
        return BoardingViews.place(a, settings);
    }

    @Transactional
    public Map<String, Object> move(User actor, Long allocationId, MoveRequest req) {
        settings.requireOn();
        if (req == null || req.roomId() == null) throw new IllegalArgumentException("Choose the room to move to.");
        RoomAllocation a = currentOrThrow(allocationId);
        Long fromId = a.getRoom().getId();
        HostelRoom to = lockInOrder(fromId, req.roomId()).get(req.roomId());
        access.requireHostel(actor, a.getRoom().getHostel().getId());
        access.requireHostel(actor, to.getHostel().getId());
        if (fromId.equals(to.getId()) && Objects.equals(a.getBed(), req.bed()))
            throw new IllegalArgumentException(name(a.getStudent()) + " is already there.");
        String why = whyNot(a.getStudent(), to, req.bed(), Set.of(a.getId()));
        if (why != null) throw new IllegalArgumentException(why);
        end(a, RoomAllocation.EndReason.MOVED, actor);
        allocations.flush();          // free the student's and bed's unique keys before the new stay is inserted
        return BoardingViews.place(start(a.getStudent(), to, req.bed(), req.note(), actor), settings);
    }

    /** Two students change places (rooms and beds). */
    @Transactional
    public Map<String, Object> swap(User actor, SwapRequest req) {
        settings.requireOn();
        if (req == null || req.firstAllocationId() == null || req.secondAllocationId() == null || req.firstAllocationId().equals(req.secondAllocationId()))
            throw new IllegalArgumentException("Choose two different students to swap.");
        RoomAllocation a = currentOrThrow(req.firstAllocationId()), b = currentOrThrow(req.secondAllocationId());
        Map<Long, HostelRoom> locked = lockInOrder(a.getRoom().getId(), b.getRoom().getId());
        HostelRoom ra = locked.get(a.getRoom().getId()), rb = locked.get(b.getRoom().getId());
        access.requireHostel(actor, ra.getHostel().getId());
        access.requireHostel(actor, rb.getHostel().getId());
        if (ra.getId().equals(rb.getId()) && Objects.equals(a.getBed(), b.getBed()))
            throw new IllegalArgumentException("They share the same room and have no bed numbers, so there is nothing to swap.");
        Set<Long> both = Set.of(a.getId(), b.getId());
        String why = whyNot(a.getStudent(), rb, b.getBed(), both);
        if (why == null) why = whyNot(b.getStudent(), ra, a.getBed(), both);
        if (why != null) throw new IllegalArgumentException(why);
        Integer bedA = a.getBed(), bedB = b.getBed();
        end(a, RoomAllocation.EndReason.SWAPPED, actor);
        end(b, RoomAllocation.EndReason.SWAPPED, actor);
        allocations.flush();
        RoomAllocation na = start(a.getStudent(), rb, bedB, "Swapped with " + name(b.getStudent()), actor);
        RoomAllocation nb = start(b.getStudent(), ra, bedA, "Swapped with " + name(a.getStudent()), actor);
        return Map.of("first", BoardingViews.place(na, settings), "second", BoardingViews.place(nb, settings));
    }

    @Transactional
    public Map<String, Object> vacate(User actor, Long allocationId, VacateRequest req) {
        settings.requireOn();
        RoomAllocation a = currentOrThrow(allocationId);
        access.requireHostel(actor, a.getRoom().getHostel().getId());
        String note = req == null ? null : BoardingViews.clean(req.note(), 200, "The note");
        if (note != null) a.setNote(a.getNote() == null ? note : BoardingViews.clean(a.getNote() + " · " + note, 200, "The note"));
        end(a, RoomAllocation.EndReason.VACATED, actor);
        return Map.of("message", name(a.getStudent()) + " has left " + BoardingViews.place(a, settings).get("short") + ".");
    }

    /**
     * Why this student can't have this room (and bed) now, or null when they can. {@code ignoring}:
     * stays that are ending in the same change (a move or swap), so their beds count as free.
     */
    String whyNot(User student, HostelRoom room, Integer bed, Set<Long> ignoring) {
        String problem = placeProblem(student, room);
        if (problem != null) return problem;
        String place = BoardingViews.roomName(room) + " in " + room.getHostel().getName();
        List<RoomAllocation> in = allocations.findCurrentInRoom(room.getId()).stream()
                .filter(x -> !ignoring.contains(x.getId())).toList();
        if (in.size() >= room.getCapacity())
            return place + " is full (" + room.getCapacity() + " of " + room.getCapacity() + " beds taken).";
        if (bed != null) {
            if (bed < 1 || bed > room.getCapacity()) return "Choose a bed from 1 to " + room.getCapacity() + ".";
            Optional<RoomAllocation> taken = in.stream().filter(x -> bed.equals(x.getBed())).findFirst();
            if (taken.isPresent()) return "Bed " + bed + " is taken by " + name(taken.get().getStudent()) + ".";
        }
        return null;
    }

    /** Why this student can never have this room as it is set up (archived, closed, gender, levels), ignoring beds; null = they could. */
    String placeProblem(User student, HostelRoom room) {
        String place = BoardingViews.roomName(room) + " in " + room.getHostel().getName();
        if (!room.isActive() || !room.getHostel().isActive() || (room.getBlock() != null && !room.getBlock().isActive()))
            return place + " is archived.";
        if (room.getStatus() == HostelRoom.Status.CLOSED) return place + " is closed.";
        HostelGender g = room.effectiveGender();
        if (!g.admits(student.getGender()))
            return student.getGender() == null
                    ? "Record " + name(student) + "'s gender first: " + place + " is for " + g.label() + " only."
                    : place + " is for " + g.label() + " only.";
        Set<Integer> levels = BoardingViews.levels(room.effectiveLevels());
        // Schools: a class is one year (level 1), so a room's levels say nothing there
        if (!levels.isEmpty() && !SystemMode.current().isSchool() && (student.getCurrentLevel() == null || !levels.contains(student.getCurrentLevel()))) {
            String word = SystemMode.current().terms().getOrDefault("level", "Level");
            return place + " takes only " + word + " " + levels.stream().map(String::valueOf).collect(Collectors.joining(", ")) + ".";
        }
        return null;
    }

    RoomAllocation start(User student, HostelRoom room, Integer bed, String note, User actor) {
        RoomAllocation a = new RoomAllocation();
        a.setStudent(student);
        a.setRoom(room);
        a.setBed(bed);
        a.setStartedAt(LocalDateTime.now());
        a.setSession(currentSession());
        a.setAssignedBy(actor == null ? "System" : CurrentUserService.displayName(actor));
        a.setNote(BoardingViews.clean(note, 200, "The note"));
        a.setCurrentStudentId(student.getId());
        a.setCurrentBedKey(RoomAllocation.bedKey(room.getId(), bed));
        return allocations.save(a);
    }

    void end(RoomAllocation a, RoomAllocation.EndReason reason, User actor) {
        endAs(a, reason, actor == null ? "System" : CurrentUserService.displayName(actor));
    }

    private void endAs(RoomAllocation a, RoomAllocation.EndReason reason, String by) {
        a.setEndedAt(LocalDateTime.now());
        a.setEndReason(reason);
        a.setEndedBy(by);
        a.setCurrentStudentId(null);
        a.setCurrentBedKey(null);
        allocations.save(a);
    }

    /** Locks both rooms, lower id first, so two crossing moves can't deadlock. */
    private Map<Long, HostelRoom> lockInOrder(Long x, Long y) {
        Map<Long, HostelRoom> out = new HashMap<>();
        for (Long id : new TreeSet<>(List.of(x, y)))
            out.put(id, rooms.lockById(id).orElseThrow(() -> new IllegalArgumentException("That room no longer exists.")));
        return out;
    }

    private AcademicSession currentSession() {
        try { return academicSessions.current(); } catch (RuntimeException e) { return null; }
    }

    // ── Student details that boarding depends on ────────────────────────────

    public record StudentRequest(String gender, String residency) {}

    /** From the Boarding pages: record a student's gender and (with both day and boarding) residency. */
    @Transactional
    public Map<String, Object> updateStudent(User actor, Long studentId, StudentRequest req) {
        settings.requireOn();
        access.requireStaff(actor);
        if (req == null) throw new IllegalArgumentException("Nothing to save.");
        User s = studentOrThrow(studentId);
        Optional<RoomAllocation> current = allocations.findCurrent(s.getId());
        if (!access.isHead(actor) && current.isPresent() && !access.canManageHostel(actor, current.get().getRoom().getHostel().getId()))
            throw new AccessDeniedException(name(s) + " lives in a " + settings.word().toLowerCase() + " you don't look after.");
        applyStudentDetails(s, req.gender() != null, Gender.parse(req.gender()),
                req.residency() != null, Residency.parse(req.residency()), actor);
        users.save(s);
        return studentRow(s, allocations.findCurrent(s.getId()).orElse(null), actor);
    }

    /**
     * Applies a gender / residency change to a student (not saved): the change must still fit the
     * room they live in, and a boarder who becomes a day student leaves their room. Also used when
     * staff edit the student on the Students page.
     */
    @Transactional
    public void applyStudentDetails(User s, boolean genderGiven, Gender gender, boolean residencyGiven, Residency residency, User actor) {
        Optional<RoomAllocation> current = s.getId() == null ? Optional.empty() : allocations.findCurrent(s.getId());
        if (genderGiven && gender != s.getGender()) {
            if (current.isPresent() && !current.get().getRoom().effectiveGender().admits(gender))
                throw new IllegalArgumentException(name(s) + " lives in " + BoardingViews.place(current.get(), settings).get("short")
                        + ", which is for " + current.get().getRoom().effectiveGender().label() + " only. Move them first.");
            s.setGender(gender);
        }
        if (residencyGiven && residency != s.getResidency()) {
            if (settings.mode() != ResidencyMode.BOTH && settings.on())
                throw new IllegalArgumentException("Every student is a " + (settings.mode() == ResidencyMode.BOARDING ? "boarder" : "day student")
                        + " at this institution, so residency can't be changed per student.");
            s.setResidency(residency);
            if (residency != Residency.BOARDING && current.isPresent())
                end(current.get(), RoomAllocation.EndReason.BECAME_DAY, actor);
        }
    }

    /** A deactivated student (left, graduated, withdrawn) gives up their room; the history stays. */
    @Transactional
    public void onDeactivated(User student, User actor) {
        if (student == null || student.getId() == null) return;
        allocations.findCurrent(student.getId()).ifPresent(a -> end(a, RoomAllocation.EndReason.DEACTIVATED, actor));
    }

    /** Before an account is deleted: its stays and boarding duties go with it. */
    @Transactional
    public void forgetUser(Long userId) {
        if (userId == null) return;
        allocations.deleteForStudent(userId);
        staff.deleteForUser(userId);
    }

    // ── Finding students ─────────────────────────────────────────────────────

    /** Name / ID search over every student, with where each one sleeps; with {@code roomId}, whether they fit that room. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> find(User actor, String q, Long roomId) {
        settings.requireOn();
        access.requireStaff(actor);
        String t = q == null ? "" : q.trim().toLowerCase();
        if (t.length() < 2) return List.of();
        List<User> found = users.searchByRoles(List.of(Role.NORMAL), "%" + t + "%", PageRequest.of(0, 25));
        Map<Long, RoomAllocation> where = found.isEmpty() ? Map.of() : allocations.findCurrentFor(found.stream().map(User::getId).toList())
                .stream().collect(Collectors.toMap(a -> a.getStudent().getId(), a -> a));
        HostelRoom room = roomId == null ? null : rooms.findById(roomId).orElse(null);
        return found.stream().map(s -> {
            RoomAllocation a = where.get(s.getId());
            Map<String, Object> m = studentRow(s, a, actor);
            if (room != null) {
                m.put("fit", a != null ? null : whyNot(s, room, null, Set.of()));
                m.put("needsBoarder", !settings.isBoarder(s));
            }
            return m;
        }).toList();
    }

    /** Boarders with no room yet (active accounts only), with optional filters. */
    @Transactional(readOnly = true)
    public Map<String, Object> awaiting(User actor, String gender, Integer level, Long programId, String q) {
        settings.requireOn();
        access.requireStaff(actor);
        Set<Long> housed = allocations.findAllCurrent().stream().map(a -> a.getStudent().getId()).collect(Collectors.toSet());
        String t = q == null ? "" : q.trim().toLowerCase();
        List<User> waiting = users.findByRole(Role.NORMAL).stream()
                .filter(s -> s.isEnabled() && settings.isBoarder(s) && !housed.contains(s.getId()))
                .sorted(Comparator.comparing((User s) -> s.getCurrentLevel() == null ? Integer.MAX_VALUE : s.getCurrentLevel())
                        .thenComparing(s -> CurrentUserService.displayName(s).toLowerCase()))
                .toList();
        List<User> shown = waiting.stream()
                .filter(s -> gender == null || gender.isBlank() || ("NONE".equalsIgnoreCase(gender) ? s.getGender() == null
                        : s.getGender() != null && s.getGender().name().equalsIgnoreCase(gender)))
                .filter(s -> level == null || level.equals(s.getCurrentLevel()))
                .filter(s -> programId == null || (s.getProgram() != null && programId.equals(s.getProgram().getId())))
                .filter(s -> t.isEmpty() || CurrentUserService.displayName(s).toLowerCase().contains(t)
                        || (s.getUsername() != null && s.getUsername().toLowerCase().contains(t)))
                .toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", waiting.size());
        out.put("matching", shown.size());
        out.put("students", shown.stream().limit(500).map(BoardingViews::student).toList());
        out.put("levels", waiting.stream().map(User::getCurrentLevel).filter(Objects::nonNull).distinct().sorted().toList());
        out.put("programmes", waiting.stream().map(User::getProgram).filter(Objects::nonNull)
                .collect(Collectors.toMap(p -> p.getId(), p -> p.getName(), (x, y) -> x, TreeMap::new))
                .entrySet().stream().map(e -> Map.of("id", e.getKey(), "name", e.getValue())).toList());
        return out;
    }

    private Map<String, Object> studentRow(User s, RoomAllocation a, User actor) {
        Map<String, Object> m = BoardingViews.student(s);
        Residency r = settings.residencyOf(s);
        m.put("residency", r != null ? r.name() : null);
        m.put("place", a != null ? BoardingViews.place(a, settings) : null);
        m.put("canManage", a == null || access.canManageHostel(actor, a.getRoom().getHostel().getId()));
        return m;
    }

    // ── History ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> roomHistory(User actor, Long roomId) {
        settings.requireOn();
        access.requireStaff(actor);
        return allocations.historyOfRoom(roomId).stream().limit(300).map(this::historyRow).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> studentHistory(User actor, Long studentId) {
        settings.requireOn();
        access.requireStaff(actor);
        User s = studentOrThrow(studentId);
        Map<String, Object> out = studentRow(s, allocations.findCurrent(studentId).orElse(null), actor);
        out.put("history", allocations.historyOfStudent(studentId).stream().map(this::historyRow).toList());
        return out;
    }

    private Map<String, Object> historyRow(RoomAllocation a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("student", BoardingViews.student(a.getStudent()));
        m.put("place", BoardingViews.place(a, settings));
        m.put("startedAt", a.getStartedAt());
        m.put("assignedBy", a.getAssignedBy());
        m.put("session", a.getSession() != null ? a.getSession().getName() : null);
        m.put("endedAt", a.getEndedAt());
        m.put("endReason", a.getEndReason() != null ? a.getEndReason().name() : null);
        m.put("endedBy", a.getEndedBy());
        m.put("note", a.getNote());
        m.put("current", a.isCurrent());
        return m;
    }

    // ── The student's own view ───────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Map<String, Object> myRoom(User principal) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", settings.mode().name());
        out.put("word", settings.word());
        if (!settings.on()) { out.put("residency", Residency.DAY.name()); return out; }
        User me = studentOrThrow(principal.getId());
        Residency r = settings.residencyOf(me);
        out.put("residency", r != null ? r.name() : null);
        Optional<RoomAllocation> a = allocations.findCurrent(me.getId());
        out.put("place", a.map(x -> BoardingViews.place(x, settings)).orElse(null));
        if (a.isPresent()) {
            HostelRoom room = a.get().getRoom();
            out.put("capacity", room.getCapacity());
            out.put("roomLabel", room.getLabel());
            if (settings.showRoommates()) {
                out.put("roommates", allocations.findCurrentInRoom(room.getId()).stream()
                        .filter(x -> !x.getStudent().getId().equals(me.getId()))
                        .sorted(Comparator.comparing(x -> CurrentUserService.displayName(x.getStudent())))
                        .map(x -> {
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("name", CurrentUserService.displayName(x.getStudent()));
                            m.put("photo", x.getStudent().getProfilePic());
                            m.put("level", x.getStudent().getCurrentLevel());
                            m.put("programme", x.getStudent().getProgram() != null ? x.getStudent().getProgram().getName() : null);
                            m.put("bed", x.getBed());
                            return m;
                        }).toList());
            }
            Long hostelId = room.getHostel().getId();
            out.put("staff", staff.findAllWithHostel().stream()
                    .filter(s -> s.getHostel() != null && s.getHostel().getId().equals(hostelId))
                    .map(s -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("name", CurrentUserService.displayName(s.getUser()));
                        m.put("title", s.getTitle());
                        return m;
                    }).toList());
        }
        return out;
    }

    /** For the Students pages: each student's residency and, for boarders with a room, where (short form). */
    @Transactional(readOnly = true)
    public Map<Long, Map<String, Object>> residenceOf(Collection<User> students) {
        if (!settings.on() || students.isEmpty()) return Map.of();
        Map<Long, RoomAllocation> where = allocations.findAllCurrent().stream()
                .collect(Collectors.toMap(a -> a.getStudent().getId(), a -> a, (x, y) -> x));
        Map<Long, Map<String, Object>> out = new HashMap<>();
        for (User s : students) {
            Map<String, Object> m = new HashMap<>();
            Residency r = settings.residencyOf(s);
            m.put("residency", r != null ? r.name() : null);
            RoomAllocation a = where.get(s.getId());
            m.put("residence", a != null ? BoardingViews.place(a, settings).get("short") : null);
            out.put(s.getId(), m);
        }
        return out;
    }

    // ── Boarding staff ───────────────────────────────────────────────────────

    public record StaffRequest(Long userId, String duty, Long hostelId, String title) {}

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listStaff(User actor) {
        settings.requireOn();
        access.requireStaff(actor);
        return staff.findAllWithHostel().stream().map(s -> {
            Map<String, Object> m = HostelService.staffBrief(s);
            m.put("hostelId", s.getHostel() != null ? s.getHostel().getId() : null);
            m.put("hostel", s.getHostel() != null ? s.getHostel().getName() : null);
            m.put("role", s.getUser().getRole().name());
            m.put("username", s.getUser().getUsername());
            m.put("photo", s.getUser().getProfilePic());
            m.put("addedBy", s.getAddedBy());
            m.put("addedAt", s.getAddedAt());
            return m;
        }).toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> staffCandidates(User actor, String q) {
        settings.requireOn();
        access.requireHead(actor);
        String t = q == null ? "" : q.trim().toLowerCase();
        if (t.length() < 2) return List.of();
        return users.searchByRoles(List.of(Role.LECTURER, Role.ADMIN), "%" + t + "%", PageRequest.of(0, 20)).stream()
                .filter(User::isEnabled)
                .map(u -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", u.getId());
                    m.put("name", CurrentUserService.displayName(u));
                    m.put("username", u.getUsername());
                    m.put("role", u.getRole().name());
                    m.put("department", u.getDepartment() != null ? u.getDepartment().getName() : null);
                    return m;
                }).toList();
    }

    @Transactional
    public List<Map<String, Object>> addStaff(User actor, StaffRequest req) {
        settings.requireOn();
        access.requireHead(actor);
        if (req == null || req.userId() == null) throw new IllegalArgumentException("Choose a member of staff.");
        User u = users.findById(req.userId()).orElseThrow(() -> new IllegalArgumentException("That account no longer exists."));
        if (u.getRole() != Role.LECTURER && u.getRole() != Role.ADMIN)
            throw new IllegalArgumentException("Only teaching staff and HOD accounts can be given boarding duties.");
        if (!u.isEnabled()) throw new IllegalArgumentException(name(u) + "'s account is deactivated.");
        BoardingStaff.Duty duty = "HEAD".equalsIgnoreCase(req.duty()) ? BoardingStaff.Duty.HEAD : BoardingStaff.Duty.HOUSE;
        BoardingStaff s = new BoardingStaff();
        s.setUser(u);
        s.setDuty(duty);
        if (duty == BoardingStaff.Duty.HOUSE) {
            if (req.hostelId() == null) throw new IllegalArgumentException("Choose the " + settings.word().toLowerCase() + " they look after.");
            Hostel h = hostels.findById(req.hostelId()).filter(Hostel::isActive)
                    .orElseThrow(() -> new IllegalArgumentException("That " + settings.word().toLowerCase() + " no longer exists."));
            s.setHostel(h);
            s.setScopeKey(String.valueOf(h.getId()));
        } else {
            s.setScopeKey("all");
        }
        if (staff.existsByUser_IdAndScopeKey(u.getId(), s.getScopeKey()))
            throw new IllegalArgumentException(name(u) + " already has this duty.");
        s.setTitle(BoardingViews.clean(req.title(), 60, "The title"));
        s.setAddedBy(CurrentUserService.displayName(actor));
        s.setAddedAt(LocalDateTime.now());
        staff.save(s);
        return listStaff(actor);
    }

    @Transactional
    public List<Map<String, Object>> removeStaff(User actor, Long id) {
        settings.requireOn();
        access.requireHead(actor);
        staff.findById(id).ifPresent(staff::delete);
        return listStaff(actor);
    }

    /**
     * The residence line printed on a report card, or null (no boarding, or switched off in the
     * boarding settings): "House: Aggrey House" in a boarding school, "Residency: Boarder · Aggrey
     * House" / "Day student" where there are both. Where the student lives when the card is printed.
     */
    @Transactional(readOnly = true)
    public String[] reportLine(String username) {
        if (!settings.on() || !settings.showOnReport() || username == null) return null;
        User s = users.findByUsername(username).filter(u -> u.getRole() == Role.NORMAL).orElse(null);
        if (s == null) return null;
        Residency r = settings.residencyOf(s);
        String house = allocations.findCurrent(s.getId()).map(a -> a.getRoom().getHostel().getName()).orElse(null);
        if (settings.mode() == ResidencyMode.BOARDING) return new String[]{settings.word(), house != null ? house : "Boarder"};
        if (r == null) return null;
        return new String[]{"Residency", r == Residency.DAY ? "Day student" : "Boarder" + (house != null ? " · " + house : "")};
    }

    /** Who lives in which hostel and who has which boarding duty, for addressing announcements. */
    public record Audience(boolean on, Map<Long, Long> hostelOfStudent, Map<Long, Set<Long>> houseDuties, Set<Long> heads, BoardingSettings settings) {
        public boolean isBoarder(User u) { return on && settings.isBoarder(u); }
        public boolean isDayStudent(User u) { return on && u.getRole() == Role.NORMAL && settings.residencyOf(u) == Residency.DAY; }
        public boolean livesIn(User u, Long hostelId) { return hostelId.equals(hostelOfStudent.get(u.getId())); }
        public boolean staffOf(User u, Long hostelId) {
            return u.getRole() == Role.SUPER_ADMIN || heads.contains(u.getId()) || houseDuties.getOrDefault(u.getId(), Set.of()).contains(hostelId);
        }
    }

    @Transactional(readOnly = true)
    public Audience audience() {
        if (!settings.on()) return new Audience(false, Map.of(), Map.of(), Set.of(), settings);
        Map<Long, Long> where = allocations.findAllCurrent().stream()
                .collect(Collectors.toMap(a -> a.getStudent().getId(), a -> a.getRoom().getHostel().getId(), (x, y) -> x));
        Map<Long, Set<Long>> duties = new HashMap<>();
        Set<Long> heads = new HashSet<>();
        for (BoardingStaff s : staff.findAllWithHostel()) {
            if (s.getDuty() == BoardingStaff.Duty.HEAD) heads.add(s.getUser().getId());
            else if (s.getHostel() != null) duties.computeIfAbsent(s.getUser().getId(), k -> new HashSet<>()).add(s.getHostel().getId());
        }
        return new Audience(true, where, duties, heads, settings);
    }

    /** What the signed-in user's navigation needs. */
    public Map<String, Object> flagsFor(User u) {
        Map<String, Object> m = new LinkedHashMap<>();
        ResidencyMode mode = settings.mode();
        m.put("mode", mode.name());
        m.put("word", settings.word());
        m.put("plural", settings.plural());
        boolean on = mode.hasBoarding();
        m.put("staff", on && access.isBoardingStaff(u));
        m.put("head", on && access.isHead(u));
        return m;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private User studentOrThrow(Long id) {
        return users.findById(id).filter(u -> u.getRole() == Role.NORMAL)
                .orElseThrow(() -> new IllegalArgumentException("That student no longer exists."));
    }

    private RoomAllocation currentOrThrow(Long id) {
        RoomAllocation a = allocations.findById(id).orElseThrow(() -> new IllegalArgumentException("That room record no longer exists."));
        if (!a.isCurrent()) throw new IllegalArgumentException(name(a.getStudent()) + " has already left that room. Refresh the page.");
        return a;
    }

    private static String name(User u) { return CurrentUserService.displayName(u); }
}
