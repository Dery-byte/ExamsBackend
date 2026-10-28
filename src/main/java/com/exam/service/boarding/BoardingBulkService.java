package com.exam.service.boarding;

import com.exam.model.academic.SystemMode;
import com.exam.model.Gender;
import com.exam.model.Residency;
import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.boarding.*;
import com.exam.repository.*;
import com.exam.service.comms.CurrentUserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Boarding work done many students at a time: importing a room list or a list of rooms from a
 * spreadsheet, giving rooms from the student import, auto-allocating waiting boarders, and clearing
 * rooms for a new year. Each one is checked first (nothing saved) and shown as a preview; committing
 * locks the rooms involved and runs the same checks again before anything is written.
 */
@Service
public class BoardingBulkService {

    public static final int MAX_ROWS = 3000;

    @Autowired private HostelRepository hostels;
    @Autowired private HostelBlockRepository blocks;
    @Autowired private HostelRoomRepository rooms;
    @Autowired private RoomAllocationRepository allocations;
    @Autowired private UserRepository users;
    @Autowired private BoardingService boarding;
    @Autowired private HostelService hostelService;
    @Autowired private BoardingSettings settings;
    @Autowired private BoardingAccess access;

    // ── Rooms as a plan is worked through ────────────────────────────────────

    /** Who is in each room while a file or plan is worked through, so rows later in it see the beds earlier rows took. */
    final class Board {
        private record Occ(Long studentId, Integer bed, Integer level, Long programId) {}

        private final Map<Long, HostelRoom> byId = new HashMap<>();
        private final Map<Long, List<Occ>> in = new HashMap<>();
        private final Map<Long, Long> roomOf = new HashMap<>();

        Board(Collection<HostelRoom> rs) {
            rs.forEach(r -> { byId.put(r.getId(), r); in.put(r.getId(), new ArrayList<>()); });
            for (RoomAllocation a : allocations.findAllCurrent()) {
                User s = a.getStudent();
                roomOf.put(s.getId(), a.getRoom().getId());
                List<Occ> list = in.get(a.getRoom().getId());
                if (list != null) list.add(new Occ(s.getId(), a.getBed(), s.getCurrentLevel(), s.getProgram() != null ? s.getProgram().getId() : null));
            }
        }

        Long roomOf(Long studentId) { return roomOf.get(studentId); }

        void leave(Long studentId) {
            Long r = roomOf.remove(studentId);
            if (r != null && in.containsKey(r)) in.get(r).removeIf(o -> o.studentId().equals(studentId));
        }

        int free(HostelRoom r) { return r.getCapacity() - in.get(r.getId()).size(); }

        /** Beds are the only thing checked here; {@link BoardingService#placeProblem} covers the rest. */
        String bedProblem(HostelRoom r, Integer bed) {
            List<Occ> occ = in.get(r.getId());
            if (occ.size() >= r.getCapacity())
                return BoardingViews.roomName(r) + " in " + r.getHostel().getName() + " is full (" + r.getCapacity() + " of " + r.getCapacity() + " beds taken).";
            if (bed != null) {
                if (bed < 1 || bed > r.getCapacity()) return "Choose a bed from 1 to " + r.getCapacity() + " for " + BoardingViews.roomName(r) + ".";
                if (occ.stream().anyMatch(o -> bed.equals(o.bed()))) return "Bed " + bed + " in " + BoardingViews.roomName(r) + " is already taken.";
            }
            return null;
        }

        Integer lowestFreeBed(HostelRoom r) {
            Set<Integer> used = in.get(r.getId()).stream().map(Occ::bed).filter(Objects::nonNull).collect(Collectors.toSet());
            for (int b = 1; b <= r.getCapacity(); b++) if (!used.contains(b)) return b;
            return null;
        }

        long sameLevel(HostelRoom r, Integer level) { return in.get(r.getId()).stream().filter(o -> Objects.equals(o.level(), level)).count(); }
        long otherLevels(HostelRoom r, Integer level) { return in.get(r.getId()).stream().filter(o -> !Objects.equals(o.level(), level)).count(); }
        long sameProgramme(HostelRoom r, Long programId) { return in.get(r.getId()).stream().filter(o -> Objects.equals(o.programId(), programId)).count(); }
        long otherProgrammes(HostelRoom r, Long programId) { return in.get(r.getId()).stream().filter(o -> !Objects.equals(o.programId(), programId)).count(); }
        int count(HostelRoom r) { return in.get(r.getId()).size(); }

        void take(User s, HostelRoom r, Integer bed) {
            in.get(r.getId()).add(new Occ(s.getId(), bed, s.getCurrentLevel(), s.getProgram() != null ? s.getProgram().getId() : null));
            if (s.getId() != null) roomOf.put(s.getId(), r.getId());
        }
    }

    /** Rooms found by house, block and room name as typed in a spreadsheet. */
    final class Finder {
        private final Map<String, Hostel> house = new HashMap<>();
        private final Map<Long, List<HostelRoom>> roomsOf = new HashMap<>();
        private final Map<Long, List<HostelBlock>> blocksOf = new HashMap<>();

        Finder() {
            for (Hostel h : hostels.findAll()) {
                if (!h.isActive()) continue;
                house.put(h.getName().trim().toLowerCase(), h);
                if (h.getCode() != null) house.putIfAbsent(h.getCode().trim().toLowerCase(), h);
            }
            for (HostelRoom r : rooms.findAllInUse()) roomsOf.computeIfAbsent(r.getHostel().getId(), k -> new ArrayList<>()).add(r);
            for (HostelBlock b : blocks.findAll()) if (b.isActive()) blocksOf.computeIfAbsent(b.getHostel().getId(), k -> new ArrayList<>()).add(b);
        }

        Hostel house(String name) { return name == null ? null : house.get(name.trim().toLowerCase()); }

        HostelBlock block(Hostel h, String name) {
            if (name == null) return null;
            String n = name.trim().toLowerCase();
            return blocksOf.getOrDefault(h.getId(), List.of()).stream()
                    .filter(b -> b.getName().toLowerCase().equals(n) || b.getName().toLowerCase().equals("block " + n)).findFirst().orElse(null);
        }

        /** The room, or a message saying why it can't be found. */
        Object room(String houseName, String blockName, String roomName) {
            String word = settings.word();
            if (houseName == null) return word + " is missing.";
            Hostel h = house(houseName);
            if (h == null) return word + " '" + houseName + "' not found (use its name or code).";
            if (roomName == null) return "Room is missing.";
            HostelBlock b = null;
            if (blockName != null) {
                b = block(h, blockName);
                if (b == null) return h.getName() + " has no block called '" + blockName + "'.";
            }
            String n = roomName.trim().toLowerCase().replaceFirst("^room\\s+", "");
            HostelBlock fb = b;
            List<HostelRoom> match = roomsOf.getOrDefault(h.getId(), List.of()).stream()
                    .filter(r -> r.getName().toLowerCase().equals(n) || r.getName().toLowerCase().equals(roomName.trim().toLowerCase()))
                    .filter(r -> fb == null || (r.getBlock() != null && r.getBlock().getId().equals(fb.getId())))
                    .toList();
            if (match.isEmpty()) return h.getName() + (b != null ? " · " + b.getName() : "") + " has no room '" + roomName + "'.";
            if (match.size() > 1) return "Several blocks of " + h.getName() + " have a room '" + roomName + "'. Fill in the Block column.";
            return match.get(0);
        }
    }

    // ── Spreadsheet imports ──────────────────────────────────────────────────

    private record Row(int number, String summary, List<String> errors, Object plan) {}

    /** type = allocations (who sleeps where) | rooms (the rooms themselves). One transaction, so a commit is all or nothing. */
    @Transactional
    public Map<String, Object> runImport(User actor, String type, List<Map<String, String>> raw, boolean commit) {
        settings.requireOn();
        access.requireStaff(actor);
        if (raw == null || raw.isEmpty()) throw new IllegalArgumentException("The file has no data rows.");
        if (raw.size() > MAX_ROWS) throw new IllegalArgumentException("Import at most " + MAX_ROWS + " rows at a time.");
        List<Map<String, String>> rows = raw.stream().map(BoardingBulkService::normaliseKeys).toList();
        return switch (type) {
            case "allocations" -> commit ? commitAllocations(actor, rows) : report("allocations", allocationRows(actor, rows), false, 0);
            case "rooms" -> commit ? commitRooms(actor, rows) : report("rooms", roomRows(actor, rows), false, 0);
            default -> throw new IllegalArgumentException("Unknown import type: " + type);
        };
    }

    private static Map<String, Object> report(String type, List<Row> checked, boolean committed, int created) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", type);
        out.put("committed", committed);
        out.put("total", checked.size());
        out.put("valid", checked.stream().filter(r -> r.errors().isEmpty()).count());
        out.put("created", created);
        out.put("rows", checked.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("row", r.number());
            m.put("summary", r.summary());
            m.put("ok", r.errors().isEmpty());
            m.put("errors", r.errors());
            return m;
        }).toList());
        return out;
    }

    private record Placement(User student, HostelRoom room, Integer bed, boolean unchanged, boolean makeBoarder) {}

    /** Who sleeps where: Student ID, House, Block, Room, Bed. Students already in a room are moved. */
    private List<Row> allocationRows(User actor, List<Map<String, String>> rows) {
        Map<String, User> byId = new HashMap<>();
        users.findByRole(Role.NORMAL).forEach(u -> { if (u.getUsername() != null) byId.put(u.getUsername().toLowerCase(), u); });
        Finder finder = new Finder();
        Map<Long, String> placeNow = allocations.findAllCurrent().stream()
                .collect(Collectors.toMap(a -> a.getStudent().getId(), a -> a.getRoom().getId() + ":" + a.getBed(), (a, b) -> a));
        Map<Long, Long> hostelNow = allocations.findAllCurrent().stream()
                .collect(Collectors.toMap(a -> a.getStudent().getId(), a -> a.getRoom().getHostel().getId(), (a, b) -> a));

        // First pass: who and where, without beds
        record Basic(int number, Map<String, String> d, User s, HostelRoom room, Integer bed, List<String> errors) {}
        List<Basic> basics = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            Map<String, String> d = rows.get(i);
            List<String> errors = new ArrayList<>();
            String id = opt(d, "studentid", "username", "indexnumber", "admissionno", "admissionnumber", "id");
            User s = null;
            if (id == null) errors.add("Student ID is missing.");
            else {
                s = byId.get(id.toLowerCase());
                if (s == null) errors.add("No student with ID " + id + ".");
                else if (!s.isEnabled()) errors.add(name(s) + "'s account is deactivated.");
                else if (!seen.add(s.getId())) errors.add(id + " appears twice in the file.");
            }
            Object found = finder.room(house(d), opt(d, "block", "wing"), opt(d, "room", "roomno", "roomnumber", "roomname"));
            HostelRoom room = found instanceof HostelRoom r ? r : null;
            if (found instanceof String msg) errors.add(msg);
            Integer bed = intValue(d, errors, "Bed", "bed", "bedno", "bednumber");
            if (room != null && !access.canManageHostel(actor, room.getHostel().getId()))
                errors.add("You don't look after " + room.getHostel().getName() + ".");
            if (s != null && hostelNow.containsKey(s.getId()) && !access.canManageHostel(actor, hostelNow.get(s.getId())))
                errors.add(name(s) + " lives in a " + settings.word().toLowerCase() + " you don't look after.");
            if (s != null && room != null && errors.isEmpty()) {
                if (!settings.isBoarder(s) && settings.mode() != ResidencyMode.BOTH) errors.add(name(s) + " is not a boarder.");
                String p = boarding.placeProblem(s, room);
                if (p != null) errors.add(p);
            }
            basics.add(new Basic(i + 2, d, s, room, bed, errors));
        }

        // Second pass: beds, with everyone who moves out of their room first (so a file can swap people).
        // A row that then fails keeps its student where they are, so the plan is worked again without them moving.
        Set<Long> stay = new HashSet<>();
        Map<Long, String> whyStay = new HashMap<>();
        for (int attempt = 0; attempt < 10; attempt++) {
            Board board = new Board(rooms.findAllInUse());
            for (Basic b : basics) if (b.errors().isEmpty() && !stay.contains(b.s().getId())) board.leave(b.s().getId());
            List<Row> out = new ArrayList<>();
            Set<Long> failed = new HashSet<>();
            for (Basic b : basics) {
                List<String> errors = new ArrayList<>(b.errors());
                Placement plan = null;
                String summary = (b.s() != null ? name(b.s()) + " (" + b.s().getUsername() + ")" : opt(b.d(), "studentid", "username", "id"))
                        + (b.room() != null ? " → " + b.room().getHostel().getName() + " · " + BoardingViews.roomName(b.room()) + (b.bed() != null ? " · Bed " + b.bed() : "") : "");
                if (errors.isEmpty()) {
                    boolean unchanged = (b.room().getId() + ":" + b.bed()).equals(placeNow.get(b.s().getId()))
                            || (b.bed() == null && placeNow.getOrDefault(b.s().getId(), "").startsWith(b.room().getId() + ":"));
                    Long sid = b.s().getId();
                    if (unchanged) { if (!stay.contains(sid)) board.take(b.s(), b.room(), b.bed()); }   // a staying student never left
                    else if (stay.contains(sid)) errors.add(whyStay.get(sid));
                    else {
                        String p = board.bedProblem(b.room(), b.bed());
                        if (p != null) { errors.add(p); failed.add(sid); whyStay.put(sid, p); }
                        else board.take(b.s(), b.room(), b.bed());
                    }
                    if (errors.isEmpty()) {
                        boolean makeBoarder = !settings.isBoarder(b.s());
                        plan = new Placement(b.s(), b.room(), b.bed(), unchanged, makeBoarder);
                        if (unchanged) summary += " (already there)";
                        else if (placeNow.containsKey(b.s().getId())) summary += " (moves from their current room)";
                        if (makeBoarder) summary += " (becomes a boarder)";
                    }
                }
                out.add(new Row(b.number(), summary, errors, plan));
            }
            if (failed.isEmpty() || !stay.addAll(failed)) return out;
        }
        throw new IllegalArgumentException("The file has too many rows competing for the same beds. Split it up and try again.");
    }

    private Map<String, Object> commitAllocations(User actor, List<Map<String, String>> rows) {
        lockRooms(rooms.findAllInUse());
        List<Row> checked = allocationRows(actor, rows);
        List<Placement> plans = checked.stream().map(r -> (Placement) r.plan()).filter(p -> p != null && !p.unchanged()).toList();
        for (Placement p : plans) {
            allocations.findCurrent(p.student().getId()).ifPresent(a -> boarding.end(a, RoomAllocation.EndReason.MOVED, actor));
            if (p.makeBoarder()) { p.student().setResidency(Residency.BOARDING); users.save(p.student()); }
        }
        allocations.flush();
        for (Placement p : plans) boarding.start(p.student(), p.room(), p.bed(), "Imported", actor);
        return report("allocations", checked, true, plans.size());
    }

    private record RoomPlan(Hostel hostel, HostelService.RoomRequest req) {}

    /** The rooms themselves: House, Block, Floor, Room, Beds, For, Label, Levels. */
    private List<Row> roomRows(User actor, List<Map<String, String>> rows) {
        access.requireHead(actor);
        Finder finder = new Finder();
        Set<String> existing = new HashSet<>(), seen = new HashSet<>();
        rooms.findAllInUse().forEach(r -> existing.add(r.getHostel().getId() + "|" + r.getNameKey()));
        List<Row> out = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Map<String, String> d = rows.get(i);
            List<String> errors = new ArrayList<>();
            String houseName = house(d);
            Hostel h = houseName == null ? null : finder.house(houseName);
            if (houseName == null) errors.add(settings.word() + " is missing.");
            else if (h == null) errors.add(settings.word() + " '" + houseName + "' not found. Add it on the Boarding page first.");
            String blockName = opt(d, "block", "wing");
            HostelBlock b = h == null || blockName == null ? null : finder.block(h, blockName);
            if (h != null && blockName != null && b == null) errors.add(h.getName() + " has no block called '" + blockName + "'. Add it first.");
            int floors = b != null ? b.getFloors() : h != null ? h.getFloors() : 1;
            Integer floor = floorValue(opt(d, "floor", "storey", "level"), floors, errors);
            String roomName = opt(d, "room", "roomno", "roomnumber", "roomname");
            if (roomName == null) errors.add("Room is missing.");
            Integer beds = intValue(d, errors, "Beds", "beds", "capacity", "bedspaces", "numberofbeds");
            if (beds == null) errors.add("Beds is missing.");
            else if (beds < 1 || beds > HostelService.MAX_BEDS) errors.add("Beds must be between 1 and " + HostelService.MAX_BEDS + ".");
            String gender = genderValue(opt(d, "for", "gender", "sex"), errors);
            if (h != null && roomName != null) {
                String key = h.getId() + "|" + HostelRoom.keyFor(b, roomName);
                if (existing.contains(key)) errors.add(h.getName() + " already has room " + roomName + (b != null ? " in " + b.getName() : "") + ".");
                else if (!seen.add(key)) errors.add("Room " + roomName + " appears twice in the file.");
            }
            String summary = (h != null ? h.getName() : Objects.toString(houseName, "")) + (b != null ? " · " + b.getName() : "")
                    + (floor != null && floors > 1 ? " · " + settings.floorLabel(floor) : "") + (roomName != null ? " · " + roomName : "")
                    + (beds != null ? " (" + beds + " beds)" : "");
            out.add(new Row(i + 2, summary, errors, errors.isEmpty()
                    ? new RoomPlan(h, new HostelService.RoomRequest(b != null ? b.getId() : null, floor, roomName, beds, gender, "OPEN",
                    opt(d, "label", "note", "use"), opt(d, "levels", "forms", "classes", "years"))) : null));
        }
        return out;
    }

    private Map<String, Object> commitRooms(User actor, List<Map<String, String>> rows) {
        List<Row> checked = roomRows(actor, rows);
        int created = 0;
        for (Row r : checked) {
            if (!(r.plan() instanceof RoomPlan p)) continue;
            hostelService.saveRoom(actor, p.hostel().getId(), null, p.req());
            created++;
        }
        return report("rooms", checked, true, created);
    }

    // ── Rooms from the student import ────────────────────────────────────────

    /** A place chosen in the student import, given once the account exists. */
    public record NewPlace(HostelRoom room, Integer bed) {}

    /** Checks the House / Block / Room / Bed columns of student import rows, remembering beds taken by earlier rows. */
    public final class StudentImportRooms {
        private final User actor;
        private Finder finder;
        private Board board;

        StudentImportRooms(User actor) { this.actor = actor; }

        public boolean anyColumn(Map<String, String> d) {
            return house(d) != null || opt(d, "block", "wing") != null || opt(d, "room", "roomno", "roomnumber") != null || opt(d, "bed", "bedno") != null;
        }

        /** The place, or null (problems added to {@code errors}). The student isn't saved yet: {@code candidate} carries gender and level. */
        public NewPlace check(Map<String, String> d, User candidate, List<String> errors) {
            if (!anyColumn(d)) return null;
            if (!settings.on()) { errors.add("This institution has no boarders, so leave the " + settings.word() + " and Room columns empty."); return null; }
            if (candidate.getResidency() == Residency.DAY && settings.mode() == ResidencyMode.BOTH) { errors.add("A day student can't be given a room."); return null; }
            if (finder == null) { finder = new Finder(); board = new Board(rooms.findAllInUse()); }
            Object found = finder.room(house(d), opt(d, "block", "wing"), opt(d, "room", "roomno", "roomnumber", "roomname"));
            if (found instanceof String msg) { errors.add(msg); return null; }
            HostelRoom room = (HostelRoom) found;
            if (!access.canManageHostel(actor, room.getHostel().getId())) {
                errors.add("Only boarding staff who look after " + room.getHostel().getName() + " can give its rooms. Leave the room columns empty and give rooms on the Boarding page.");
                return null;
            }
            Integer bed = intValue(d, errors, "Bed", "bed", "bedno", "bednumber");
            String p = boarding.placeProblem(candidate, room);
            if (p == null) p = board.bedProblem(room, bed);
            if (p != null) { errors.add(p); return null; }
            board.take(candidate, room, bed);
            return new NewPlace(room, bed);
        }
    }

    public StudentImportRooms studentImportRooms(User actor) { return new StudentImportRooms(actor); }

    /** After the imported account is saved: its room (rechecked under the room lock). */
    @Transactional
    public void placeNew(User student, NewPlace place, User actor) {
        HostelRoom room = rooms.lockById(place.room().getId()).orElseThrow(() -> new IllegalArgumentException("That room no longer exists."));
        String why = boarding.whyNot(student, room, place.bed(), Set.of());
        if (why != null) throw new IllegalArgumentException(why);
        if (settings.mode() == ResidencyMode.BOTH && student.getResidency() != Residency.BOARDING) {
            student.setResidency(Residency.BOARDING);
            users.save(student);
        }
        boarding.start(student, room, place.bed(), "Imported with the student", actor);
    }

    // ── Auto-allocate ────────────────────────────────────────────────────────

    /**
     * order: FILL (fill each room before the next) or SPREAD (keep rooms evenly filled).
     * arrange: ANY, SAME_LEVEL (keep each level together), MIX_LEVELS or MIX_PROGRAMMES (spread classmates across rooms).
     */
    public record AutoRequest(List<Long> hostelIds, String gender, List<Integer> levels, List<Long> programIds,
                              String order, String arrange, Boolean numberBeds) {}

    public Map<String, Object> autoPreview(User actor, AutoRequest req) {
        settings.requireOn();
        access.requireStaff(actor);
        return plan(actor, req, false);
    }

    @Transactional
    public Map<String, Object> autoCommit(User actor, AutoRequest req) {
        settings.requireOn();
        access.requireStaff(actor);
        lockRooms(autoRooms(actor, req));
        return plan(actor, req, true);
    }

    private List<HostelRoom> autoRooms(User actor, AutoRequest req) {
        Set<Long> chosen = req == null || req.hostelIds() == null ? Set.of() : new HashSet<>(req.hostelIds());
        for (Long id : chosen) if (!access.canManageHostel(actor, id)) throw new AccessDeniedException("You don't look after one of the chosen " + settings.plural().toLowerCase() + ".");
        return rooms.findAllInUse().stream()
                .filter(r -> r.getStatus() == HostelRoom.Status.OPEN && (r.getBlock() == null || r.getBlock().isActive()))
                .filter(r -> chosen.isEmpty() ? access.canManageHostel(actor, r.getHostel().getId()) : chosen.contains(r.getHostel().getId()))
                .sorted(Comparator.comparing((HostelRoom r) -> r.getHostel().getName().toLowerCase()).thenComparing(HostelService.ROOM_ORDER))
                .toList();
    }

    private Map<String, Object> plan(User actor, AutoRequest req, boolean commit) {
        if (req == null) throw new IllegalArgumentException("Choose who to give rooms to.");
        List<HostelRoom> rs = autoRooms(actor, req);
        Board board = new Board(rs);
        Set<Integer> levels = req.levels() == null ? Set.of() : new HashSet<>(req.levels());
        Set<Long> programmes = req.programIds() == null ? Set.of() : new HashSet<>(req.programIds());
        String g = req.gender() == null ? "" : req.gender().toUpperCase();
        List<User> students = users.findByRole(Role.NORMAL).stream()
                .filter(s -> s.isEnabled() && settings.isBoarder(s) && board.roomOf(s.getId()) == null)
                .filter(s -> g.isEmpty() || ("NONE".equals(g) ? s.getGender() == null : s.getGender() != null && s.getGender().name().equals(g)))
                .filter(s -> levels.isEmpty() || levels.contains(s.getCurrentLevel()))
                .filter(s -> programmes.isEmpty() || (s.getProgram() != null && programmes.contains(s.getProgram().getId())))
                .sorted(Comparator.comparing((User s) -> s.getCurrentLevel() == null ? Integer.MAX_VALUE : s.getCurrentLevel())
                        .thenComparing(s -> s.getProgram() == null ? "~" : s.getProgram().getName())
                        .thenComparing(s -> CurrentUserService.displayName(s).toLowerCase()))
                .toList();
        if (students.size() > MAX_ROWS) throw new IllegalArgumentException("That is " + students.size() + " students. Narrow it to " + MAX_ROWS + " or fewer.");

        boolean spread = "SPREAD".equalsIgnoreCase(req.order());
        String arrange = req.arrange() == null ? "ANY" : req.arrange().toUpperCase();
        // Schools: the Super Admin's setting decides, whatever the request asks (a class is one year: classmates = same class)
        if (SystemMode.current().isSchool()) arrange = settings.classmatesTogether() ? "SAME_CLASS" : "MIX_PROGRAMMES";
        boolean beds = !Boolean.FALSE.equals(req.numberBeds());
        Map<Long, Integer> order = new HashMap<>();
        for (int i = 0; i < rs.size(); i++) order.put(rs.get(i).getId(), i);
        // Which rooms each kind of student may use (gender + levels), worked out once per kind
        Map<String, List<HostelRoom>> fitting = new HashMap<>();

        List<Map<String, Object>> placed = new ArrayList<>(), unplaced = new ArrayList<>();
        Map<String, Integer> perHouse = new TreeMap<>();
        for (User s : students) {
            String kind = s.getGender() + "|" + s.getCurrentLevel();
            List<HostelRoom> ok = fitting.computeIfAbsent(kind, k -> rs.stream().filter(r -> boarding.placeProblem(s, r) == null).toList());
            List<HostelRoom> open = ok.stream().filter(r -> board.free(r) > 0).toList();
            if (open.isEmpty()) {
                Map<String, Object> m = BoardingViews.student(s);
                m.put("reason", ok.isEmpty()
                        ? (rs.isEmpty() ? "There are no open rooms in the chosen " + settings.plural().toLowerCase() + "."
                            : s.getGender() == null ? "Gender not recorded: only mixed rooms fit, and there are none here."
                            : "No room here is for " + (s.getGender() == Gender.MALE ? "boys" : "girls") + (s.getCurrentLevel() != null ? " of this level" : "") + ".")
                        : "Every room that fits is full.");
                unplaced.add(m);
                continue;
            }
            Comparator<HostelRoom> rank = switch (arrange) {
                case "SAME_LEVEL" -> Comparator.comparingInt((HostelRoom r) -> board.otherLevels(r, s.getCurrentLevel()) > 0 ? 2 : board.sameLevel(r, s.getCurrentLevel()) > 0 ? 0 : 1);
                case "MIX_LEVELS" -> Comparator.comparingLong((HostelRoom r) -> board.sameLevel(r, s.getCurrentLevel()));
                // A room of their own class first, then an empty one, then one with other classes
                case "SAME_CLASS" -> Comparator.comparingInt((HostelRoom r) -> {
                    Long pid = s.getProgram() != null ? s.getProgram().getId() : null;
                    return board.otherProgrammes(r, pid) > 0 ? 2 : board.sameProgramme(r, pid) > 0 ? 0 : 1;
                });
                case "MIX_PROGRAMMES" -> Comparator.comparingLong((HostelRoom r) -> board.sameProgramme(r, s.getProgram() != null ? s.getProgram().getId() : null));
                default -> Comparator.comparingInt((HostelRoom r) -> 0);
            };
            rank = spread
                    ? rank.thenComparingDouble(r -> (double) board.count(r) / r.getCapacity()).thenComparingInt(r -> order.get(r.getId()))
                    : rank.thenComparingInt(r -> order.get(r.getId()));
            HostelRoom room = open.stream().min(rank).orElseThrow();
            Integer bed = beds ? board.lowestFreeBed(room) : null;
            board.take(s, room, bed);
            perHouse.merge(room.getHostel().getName(), 1, Integer::sum);
            if (commit) boarding.start(s, room, bed, "Auto-allocated", actor);
            Map<String, Object> m = BoardingViews.student(s);
            m.put("place", BoardingViews.place(room, bed, settings));
            placed.add(m);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("committed", commit);
        out.put("students", students.size());
        out.put("placed", placed);
        out.put("unplaced", unplaced);
        out.put("perHouse", perHouse.entrySet().stream().map(e -> Map.of("house", e.getKey(), "count", e.getValue())).toList());
        out.put("freeBedsLeft", rs.stream().mapToInt(board::free).sum());
        out.put("message", commit ? placed.size() + " student" + (placed.size() == 1 ? "" : "s") + " given a room"
                + (unplaced.isEmpty() ? "." : "; " + unplaced.size() + " still waiting.") : null);
        return out;
    }

    // ── New academic year ────────────────────────────────────────────────────

    /** Which current stays to end: one house and/or level and/or programme (nothing = everyone). */
    public record ClearRequest(Long hostelId, Integer level, Long programId, String note) {}

    @Transactional
    public Map<String, Object> clear(User actor, ClearRequest req, boolean commit) {
        settings.requireOn();
        access.requireHead(actor);
        ClearRequest r = req == null ? new ClearRequest(null, null, null, null) : req;
        List<RoomAllocation> stays = allocations.findAllCurrent().stream()
                .filter(a -> r.hostelId() == null || a.getRoom().getHostel().getId().equals(r.hostelId()))
                .filter(a -> r.level() == null || r.level().equals(a.getStudent().getCurrentLevel()))
                .filter(a -> r.programId() == null || (a.getStudent().getProgram() != null && r.programId().equals(a.getStudent().getProgram().getId())))
                .toList();
        Map<String, Long> perHouse = stays.stream().collect(Collectors.groupingBy(a -> a.getRoom().getHostel().getName(), TreeMap::new, Collectors.counting()));
        if (commit) {
            String note = BoardingViews.clean(r.note(), 200, "The note");
            for (RoomAllocation a : stays) {
                if (note != null) a.setNote(note);
                boarding.end(a, RoomAllocation.EndReason.NEW_YEAR, actor);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("committed", commit);
        out.put("count", stays.size());
        out.put("perHouse", perHouse.entrySet().stream().map(e -> Map.of("house", e.getKey(), "count", e.getValue())).toList());
        out.put("message", commit ? stays.size() + " student" + (stays.size() == 1 ? "" : "s") + " taken out of their rooms. They are waiting for a room again." : null);
        return out;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void lockRooms(Collection<HostelRoom> rs) {
        rs.stream().map(HostelRoom::getId).sorted().forEach(id -> rooms.lockById(id));
    }

    private String house(Map<String, String> d) {
        String word = settings.word().toLowerCase().replaceAll("[^a-z0-9]", "");
        String v = opt(d, "house", "hostel", "hall", "dormitory", "dorm", word);
        return v;
    }

    /** "Ground", "G", "First floor", "2nd", "Floor 3" or a number (0 = ground floor; with "Floor 1, 2 …" names, 1 = the lowest). */
    private Integer floorValue(String raw, int floors, List<String> errors) {
        if (raw == null) return 0;
        String v = raw.trim().toLowerCase();
        for (int f = 0; f < floors; f++) if (settings.floorLabel(f).toLowerCase().equals(v)) return f;
        if (v.equals("g") || v.startsWith("ground")) return 0;
        // "2nd floor" → "2", "Floor 3" → "3", "third floor" → "third" (ordinal endings only come off numbers)
        String digits = v.replaceAll("^floor\\s*", "").replaceAll("\\s*floor$", "").replaceAll("(?<=\\d)(st|nd|rd|th)$", "").replace(".0", "").trim();
        Integer n = null;
        try { n = Integer.parseInt(digits); } catch (NumberFormatException ignored) { /* below */ }
        if (n == null) {
            List<String> words = List.of("first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth", "tenth");
            int i = words.indexOf(digits);
            if (i >= 0) n = i + 1;
        }
        if (n == null) { errors.add("Floor '" + raw + "' is not understood. Use Ground, First floor, 2 …"); return null; }
        int f = v.startsWith("floor") && !settings.groundFloor() ? n - 1 : settings.groundFloor() ? n : n - 1;
        if (f < 0 || f >= floors) { errors.add("Floor '" + raw + "' doesn't exist: the building has " + floors + " floor" + (floors == 1 ? "" : "s") + "."); return null; }
        return f;
    }

    private static String genderValue(String raw, List<String> errors) {
        if (raw == null) return null;
        String v = raw.trim().toLowerCase();
        if (v.startsWith("boy") || v.equals("male") || v.equals("m")) return "BOYS";
        if (v.startsWith("girl") || v.equals("female") || v.equals("f")) return "GIRLS";
        if (v.startsWith("mix") || v.equals("both")) return "MIXED";
        errors.add("For must be Boys, Girls or Mixed (or empty = as the building).");
        return null;
    }

    private static Integer intValue(Map<String, String> d, List<String> errors, String label, String... keys) {
        String v = opt(d, keys);
        if (v == null) return null;
        String clean = v.trim().toLowerCase().replace("bed", "").trim();
        if (clean.endsWith(".0")) clean = clean.substring(0, clean.length() - 2);
        try { return Integer.parseInt(clean); }
        catch (NumberFormatException e) { errors.add(label + " '" + v + "' is not a number."); return null; }
    }

    private static String opt(Map<String, String> d, String... keys) {
        for (String k : keys) if (d.get(k) != null) return d.get(k);
        return null;
    }

    /** "First Name" / "first_name" / "FIRSTNAME" → "firstname"; values trimmed (as the student import does). */
    static Map<String, String> normaliseKeys(Map<String, String> row) {
        Map<String, String> m = new HashMap<>();
        row.forEach((k, v) -> {
            if (k == null) return;
            String key = k.toLowerCase().replaceAll("[^a-z0-9]", "");
            String val = v == null ? null : v.trim();
            if (!key.isEmpty() && val != null && !val.isEmpty()) m.putIfAbsent(key, val);
        });
        return m;
    }

    private static String name(User u) { return CurrentUserService.displayName(u); }
}
