package com.exam.service.boarding;

import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.boarding.*;
import com.exam.repository.*;
import com.exam.service.comms.CurrentUserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Hostels, their blocks and rooms: the overview cards, one hostel's floors and rooms with who lives
 * in each, and the head of boarding's changes to the buildings. A change can never strand a student:
 * a room can't shrink below its occupants, a floor or block in use can't go, a hostel, block or room
 * with history is archived instead of deleted, and gender changes must still fit everyone living there.
 */
@Service
public class HostelService {

    public static final int MAX_FLOORS = 40;
    public static final int MAX_BEDS = 200;
    public static final int MAX_GENERATED = 200;

    @Autowired private HostelRepository hostels;
    @Autowired private HostelBlockRepository blocks;
    @Autowired private HostelRoomRepository rooms;
    @Autowired private RoomAllocationRepository allocations;
    @Autowired private BoardingStaffRepository staff;
    @Autowired private UserRepository users;
    @Autowired private AnnouncementRepository announcements;
    @Autowired private BoardingSettings settings;
    @Autowired private BoardingAccess access;

    // ── Reading ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Map<String, Object> overview(User u, boolean archived) {
        settings.requireOn();
        access.requireStaff(u);
        boolean head = access.isHead(u);
        Set<Long> mine = access.houseHostelIds(u);

        Map<Long, List<HostelRoom>> roomsByHostel = rooms.findAll().stream().filter(HostelRoom::isActive)
                .collect(Collectors.groupingBy(r -> r.getHostel().getId()));
        Map<Long, Long> perRoom = allocations.findAllCurrent().stream()
                .collect(Collectors.groupingBy(a -> a.getRoom().getId(), Collectors.counting()));
        Map<Long, List<BoardingStaff>> staffByHostel = staff.findAllWithHostel().stream().filter(s -> s.getHostel() != null)
                .collect(Collectors.groupingBy(s -> s.getHostel().getId()));
        Map<Long, Long> blockCounts = blocks.findAll().stream().filter(HostelBlock::isActive)
                .collect(Collectors.groupingBy(b -> b.getHostel().getId(), Collectors.counting()));

        List<Map<String, Object>> cards = new ArrayList<>();
        for (Hostel h : hostels.findAllByOrderByNameAsc()) {
            if (h.isActive() == archived) continue;
            List<HostelRoom> rs = roomsByHostel.getOrDefault(h.getId(), List.of());
            int beds = 0, free = 0, closed = 0;
            long occupied = 0;
            for (HostelRoom r : rs) {
                long in = perRoom.getOrDefault(r.getId(), 0L);
                occupied += in;
                if (r.getStatus() == HostelRoom.Status.CLOSED) { closed++; continue; }
                beds += r.getCapacity();
                free += Math.max(0, r.getCapacity() - (int) in);
            }
            Map<String, Object> m = hostelInfo(h);
            m.put("blocks", blockCounts.getOrDefault(h.getId(), 0L));
            m.put("rooms", rs.size());
            m.put("closedRooms", closed);
            m.put("beds", beds);
            m.put("occupied", occupied);
            m.put("free", free);
            m.put("canManage", head || mine.contains(h.getId()));
            m.put("staff", staffByHostel.getOrDefault(h.getId(), List.of()).stream().map(HostelService::staffBrief).toList());
            cards.add(m);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("settings", settings.view());
        out.put("head", head);
        out.put("hostels", cards);
        if (!archived) {
            long boarders = 0, awaiting = 0, noGender = 0;
            Set<Long> housed = allocations.findAllCurrent().stream().map(a -> a.getStudent().getId()).collect(Collectors.toSet());
            for (User s : users.findByRole(Role.NORMAL)) {
                if (!s.isEnabled() || !settings.isBoarder(s)) continue;
                boarders++;
                if (!housed.contains(s.getId())) awaiting++;
                if (s.getGender() == null) noGender++;
            }
            out.put("boarders", boarders);
            out.put("housed", housed.size());
            out.put("awaiting", awaiting);
            out.put("noGender", noGender);
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> hostel(User u, Long id) {
        settings.requireOn();
        access.requireStaff(u);
        Hostel h = hostelOrThrow(id);
        Map<String, Object> out = hostelInfo(h);
        out.put("canManage", access.canManageHostel(u, h.getId()));
        out.put("canEdit", access.isHead(u));

        List<HostelBlock> bs = blocks.findByHostel_IdOrderByNameAsc(h.getId()).stream().filter(HostelBlock::isActive).toList();
        out.put("blockList", bs.stream().map(b -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", b.getId());
            m.put("name", b.getName());
            m.put("floors", b.getFloors());
            m.put("gender", b.getGender() != null ? b.getGender().name() : null);
            m.put("floorLabels", floorLabels(b.getFloors()));
            return m;
        }).toList());
        out.put("floorLabels", floorLabels(h.getFloors()));

        Map<Long, List<RoomAllocation>> byRoom = allocations.findCurrentInHostel(h.getId()).stream()
                .collect(Collectors.groupingBy(a -> a.getRoom().getId()));
        List<HostelRoom> rs = rooms.findForHostel(h.getId()).stream().filter(HostelRoom::isActive)
                .sorted(ROOM_ORDER).toList();
        out.put("rooms", rs.stream().map(r -> roomInfo(r, byRoom.getOrDefault(r.getId(), List.of()))).toList());
        out.put("staff", staff.findAllWithHostel().stream()
                .filter(s -> s.getHostel() != null && s.getHostel().getId().equals(h.getId()))
                .map(HostelService::staffBrief).toList());
        out.put("settings", settings.view());
        return out;
    }

    /** Natural order: block, floor, then "2" before "10". */
    public static final Comparator<HostelRoom> ROOM_ORDER = Comparator
            .comparing((HostelRoom r) -> r.getBlock() == null ? "" : r.getBlock().getName().toLowerCase())
            .thenComparingInt(HostelRoom::getFloor)
            .thenComparing(r -> r.getName().toLowerCase(), HostelService::natural);

    public static int natural(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char x = a.charAt(i), y = b.charAt(j);
            if (Character.isDigit(x) && Character.isDigit(y)) {
                int si = i, sj = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
                while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
                String na = a.substring(si, i).replaceFirst("^0+(?=.)", ""), nb = b.substring(sj, j).replaceFirst("^0+(?=.)", "");
                int c = na.length() != nb.length() ? Integer.compare(na.length(), nb.length()) : na.compareTo(nb);
                if (c != 0) return c;
            } else {
                if (x != y) return Character.compare(x, y);
                i++; j++;
            }
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }

    private List<String> floorLabels(int floors) {
        List<String> l = new ArrayList<>();
        for (int f = 0; f < floors; f++) l.add(settings.floorLabel(f));
        return l;
    }

    static Map<String, Object> hostelInfo(Hostel h) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", h.getId());
        m.put("name", h.getName());
        m.put("code", h.getCode());
        m.put("gender", h.getGender().name());
        m.put("floors", h.getFloors());
        m.put("allowedLevels", h.getAllowedLevels());
        m.put("note", h.getNote());
        m.put("active", h.isActive());
        return m;
    }

    Map<String, Object> roomInfo(HostelRoom r, List<RoomAllocation> in) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("blockId", r.getBlock() != null ? r.getBlock().getId() : null);
        m.put("floor", r.getFloor());
        m.put("name", r.getName());
        m.put("display", BoardingViews.roomName(r));
        m.put("capacity", r.getCapacity());
        m.put("gender", r.getGender() != null ? r.getGender().name() : null);
        m.put("effectiveGender", r.effectiveGender().name());
        m.put("status", r.getStatus().name());
        m.put("label", r.getLabel());
        m.put("allowedLevels", r.getAllowedLevels());
        m.put("effectiveLevels", r.effectiveLevels());
        m.put("free", Math.max(0, r.getCapacity() - in.size()));
        m.put("occupants", in.stream()
                .sorted(Comparator.comparing((RoomAllocation a) -> a.getBed() == null ? Integer.MAX_VALUE : a.getBed())
                        .thenComparing(a -> CurrentUserService.displayName(a.getStudent())))
                .map(a -> {
                    Map<String, Object> o = BoardingViews.student(a.getStudent());
                    o.put("allocationId", a.getId());
                    o.put("bed", a.getBed());
                    o.put("since", a.getStartedAt());
                    o.put("note", a.getNote());
                    return o;
                }).toList());
        return m;
    }

    static Map<String, Object> staffBrief(BoardingStaff s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("userId", s.getUser().getId());
        m.put("name", CurrentUserService.displayName(s.getUser()));
        m.put("title", s.getTitle());
        m.put("duty", s.getDuty().name());
        return m;
    }

    // ── Hostels ──────────────────────────────────────────────────────────────

    public record HostelRequest(String name, String code, String gender, Integer floors, String allowedLevels, String note) {}

    @Transactional
    public Map<String, Object> saveHostel(User u, Long id, HostelRequest req) {
        settings.requireOn();
        access.requireHead(u);
        if (req == null) throw new IllegalArgumentException("Nothing to save.");
        String name = BoardingViews.clean(req.name(), 80, "The name");
        if (name == null) throw new IllegalArgumentException("Give the " + settings.word().toLowerCase() + " a name.");
        HostelGender gender = HostelGender.parse(req.gender());
        if (gender == null) throw new IllegalArgumentException("Choose who it is for: boys, girls or mixed.");
        int floors = req.floors() == null ? 1 : req.floors();
        if (floors < 1 || floors > MAX_FLOORS) throw new IllegalArgumentException("Floors must be between 1 and " + MAX_FLOORS + ".");

        Hostel h = id == null ? new Hostel() : hostelOrThrow(id);
        if (id != null && !h.isActive()) throw new IllegalArgumentException("Restore this " + settings.word().toLowerCase() + " before changing it.");
        String key = name.toLowerCase();
        if (!key.equals(h.getNameKey()) && hostels.existsByNameKey(key))
            throw new IllegalArgumentException("There is already a " + settings.word().toLowerCase() + " called " + name + ".");
        if (id != null && floors < h.getFloors()) {
            boolean used = rooms.findForHostel(h.getId()).stream()
                    .anyMatch(r -> r.isActive() && r.getBlock() == null && r.getFloor() >= floors);
            if (used) throw new IllegalArgumentException("Rooms are set up on the floors you are removing. Move or delete them first.");
        }
        h.setName(name);
        h.setNameKey(key);
        h.setCode(BoardingViews.clean(req.code(), 20, "The code"));
        h.setGender(gender);
        h.setFloors(floors);
        h.setAllowedLevels(BoardingViews.normaliseLevels(req.allowedLevels()));
        h.setNote(BoardingViews.clean(req.note(), 300, "The note"));
        if (h.getCreatedAt() == null) h.setCreatedAt(LocalDateTime.now());
        h = hostels.save(h);
        if (id != null) requireOccupantsStillFit(h.getId());
        return hostelInfo(h);
    }

    /** Deleted when it has never housed anyone; archived (hidden, history kept) when it has. */
    @Transactional
    public Map<String, Object> deleteHostel(User u, Long id) {
        settings.requireOn();
        access.requireHead(u);
        Hostel h = hostelOrThrow(id);
        List<RoomAllocation> living = allocations.findCurrentInHostel(id);
        if (!living.isEmpty())
            throw new IllegalArgumentException(living.size() + " student" + (living.size() == 1 ? " lives" : "s live") + " in " + h.getName()
                    + ". Move them to another " + settings.word().toLowerCase() + " first.");
        if (allocations.existsByRoom_Hostel_Id(id) || announcements.existsByHostel_Id(id)) {   // history, or announcements addressed to it
            h.setActive(false);
            h.setNameKey(null);
            hostels.save(h);
            return Map.of("archived", true, "message", h.getName() + " is archived. Its history is kept.");
        }
        staff.deleteForHostel(id);
        rooms.deleteAll(rooms.findForHostel(id));
        blocks.deleteAll(blocks.findByHostel_IdOrderByNameAsc(id));
        hostels.delete(h);
        return Map.of("archived", false, "message", h.getName() + " is deleted.");
    }

    @Transactional
    public Map<String, Object> restoreHostel(User u, Long id) {
        settings.requireOn();
        access.requireHead(u);
        Hostel h = hostelOrThrow(id);
        if (h.isActive()) return hostelInfo(h);
        String key = h.getName().toLowerCase();
        if (hostels.existsByNameKey(key))
            throw new IllegalArgumentException("Another " + settings.word().toLowerCase() + " is now called " + h.getName() + ". Rename that one first.");
        h.setActive(true);
        h.setNameKey(key);
        return hostelInfo(hostels.save(h));
    }

    // ── Blocks ───────────────────────────────────────────────────────────────

    public record BlockRequest(String name, Integer floors, String gender) {}

    @Transactional
    public Map<String, Object> saveBlock(User u, Long hostelId, Long id, BlockRequest req) {
        settings.requireOn();
        access.requireHead(u);
        if (req == null) throw new IllegalArgumentException("Nothing to save.");
        HostelBlock b;
        if (id == null) {
            Hostel h = activeHostel(hostelId);
            b = new HostelBlock();
            b.setHostel(h);
        } else {
            b = blocks.findById(id).filter(HostelBlock::isActive).orElseThrow(() -> new IllegalArgumentException("That block no longer exists."));
        }
        String name = BoardingViews.clean(req.name(), 60, "The block name");
        if (name == null) throw new IllegalArgumentException("Give the block a name, e.g. Block A or East Wing.");
        int floors = req.floors() == null ? 1 : req.floors();
        if (floors < 1 || floors > MAX_FLOORS) throw new IllegalArgumentException("Floors must be between 1 and " + MAX_FLOORS + ".");
        String key = name.toLowerCase();
        if (!key.equals(b.getNameKey()) && blocks.existsByHostel_IdAndNameKey(b.getHostel().getId(), key))
            throw new IllegalArgumentException(b.getHostel().getName() + " already has a block called " + name + ".");
        if (id != null && floors < b.getFloors()) {
            boolean used = rooms.findForHostel(b.getHostel().getId()).stream()
                    .anyMatch(r -> r.isActive() && r.getBlock() != null && r.getBlock().getId().equals(id) && r.getFloor() >= floors);
            if (used) throw new IllegalArgumentException("Rooms are set up on the floors you are removing. Move or delete them first.");
        }
        b.setName(name);
        b.setNameKey(key);
        b.setFloors(floors);
        b.setGender(HostelGender.parse(req.gender()));
        b = blocks.save(b);
        if (id != null) requireOccupantsStillFit(b.getHostel().getId());
        return Map.of("id", b.getId(), "name", b.getName(), "floors", b.getFloors());
    }

    @Transactional
    public Map<String, Object> deleteBlock(User u, Long id) {
        settings.requireOn();
        access.requireHead(u);
        HostelBlock b = blocks.findById(id).orElseThrow(() -> new IllegalArgumentException("That block no longer exists."));
        List<HostelRoom> inBlock = rooms.findForHostel(b.getHostel().getId()).stream()
                .filter(r -> r.getBlock() != null && r.getBlock().getId().equals(id)).toList();
        if (inBlock.stream().anyMatch(HostelRoom::isActive))
            throw new IllegalArgumentException(b.getName() + " still has rooms. Delete or move its rooms first.");
        if (!inBlock.isEmpty()) {           // only archived rooms with history: keep the block for them
            b.setActive(false);
            b.setNameKey(null);
            blocks.save(b);
            return Map.of("archived", true, "message", b.getName() + " is archived. Its history is kept.");
        }
        blocks.delete(b);
        return Map.of("archived", false, "message", b.getName() + " is deleted.");
    }

    // ── Rooms ────────────────────────────────────────────────────────────────

    public record RoomRequest(Long blockId, Integer floor, String name, Integer capacity, String gender, String status,
                              String label, String allowedLevels) {}

    @Transactional
    public Map<String, Object> saveRoom(User u, Long hostelId, Long id, RoomRequest req) {
        settings.requireOn();
        access.requireHead(u);
        if (req == null) throw new IllegalArgumentException("Nothing to save.");
        HostelRoom r;
        if (id == null) {
            r = new HostelRoom();
            r.setHostel(activeHostel(hostelId));
        } else {
            r = rooms.lockById(id).filter(HostelRoom::isActive).orElseThrow(() -> new IllegalArgumentException("That room no longer exists."));
        }
        HostelBlock block = blockFor(r.getHostel(), req.blockId());
        int floor = req.floor() == null ? 0 : req.floor();
        int floors = block != null ? block.getFloors() : r.getHostel().getFloors();
        if (floor < 0 || floor >= floors)
            throw new IllegalArgumentException((block != null ? block.getName() : r.getHostel().getName()) + " has "
                    + floors + " floor" + (floors == 1 ? "" : "s") + ". Choose one of them.");
        String name = BoardingViews.clean(req.name(), 30, "The room name");
        if (name == null) throw new IllegalArgumentException("Give the room a name or number.");
        int capacity = req.capacity() == null ? 0 : req.capacity();
        if (capacity < 1 || capacity > MAX_BEDS) throw new IllegalArgumentException("Beds must be between 1 and " + MAX_BEDS + ".");
        if (id != null) {
            List<RoomAllocation> in = allocations.findCurrentInRoom(id);
            if (capacity < in.size())
                throw new IllegalArgumentException(in.size() + " students live in this room, so it needs at least " + in.size() + " beds.");
            int maxBed = in.stream().map(RoomAllocation::getBed).filter(Objects::nonNull).max(Integer::compare).orElse(0);
            if (capacity < maxBed) throw new IllegalArgumentException("Bed " + maxBed + " is in use. Move that student before removing the bed.");
        }
        String key = keyFor(block, name);
        if (!key.equals(r.getNameKey()) && rooms.existsByHostel_IdAndNameKey(r.getHostel().getId(), key))
            throw new IllegalArgumentException("There is already a room called " + name + (block != null ? " in " + block.getName() : "") + ".");
        r.setBlock(block);
        r.setFloor(floor);
        r.setName(name);
        r.setNameKey(key);
        r.setCapacity(capacity);
        r.setGender(HostelGender.parse(req.gender()));
        r.setStatus(req.status() != null && req.status().equalsIgnoreCase("CLOSED") ? HostelRoom.Status.CLOSED : HostelRoom.Status.OPEN);
        r.setLabel(BoardingViews.clean(req.label(), 60, "The label"));
        r.setAllowedLevels(BoardingViews.normaliseLevels(req.allowedLevels()));
        r = rooms.save(r);
        if (id != null) requireOccupantsStillFit(r.getHostel().getId());
        return roomInfo(r, id == null ? List.of() : allocations.findCurrentInRoom(r.getId()));
    }

    public record GenerateRequest(Long blockId, Integer floor, String prefix, Integer from, Integer to, Boolean pad,
                                  Integer capacity, String gender, String label) {}

    /** "Rooms 201–220 on the second floor, 8 beds each" in one go; names already taken are skipped. */
    @Transactional
    public Map<String, Object> generateRooms(User u, Long hostelId, GenerateRequest req) {
        settings.requireOn();
        access.requireHead(u);
        if (req == null || req.from() == null || req.to() == null) throw new IllegalArgumentException("Give the first and last room numbers.");
        int from = req.from(), to = req.to();
        if (from < 0 || to < from) throw new IllegalArgumentException("The last number must be the same as or after the first.");
        if (to - from + 1 > MAX_GENERATED) throw new IllegalArgumentException("Add at most " + MAX_GENERATED + " rooms at a time.");
        String prefix = req.prefix() == null ? "" : req.prefix().trim();
        int width = Boolean.TRUE.equals(req.pad()) ? String.valueOf(to).length() : 0;
        Hostel h = activeHostel(hostelId);
        HostelBlock block = blockFor(h, req.blockId());

        List<String> created = new ArrayList<>(), skipped = new ArrayList<>();
        for (int n = from; n <= to; n++) {
            String num = width > 0 ? String.format("%0" + width + "d", n) : String.valueOf(n);
            String name = prefix + num;
            if (rooms.existsByHostel_IdAndNameKey(h.getId(), keyFor(block, name))) { skipped.add(name); continue; }
            saveRoom(u, h.getId(), null, new RoomRequest(req.blockId(), req.floor(), name, req.capacity(), req.gender(), "OPEN", req.label(), null));
            created.add(name);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("created", created);
        out.put("skipped", skipped);
        out.put("message", created.size() + " room" + (created.size() == 1 ? "" : "s") + " added"
                + (skipped.isEmpty() ? "." : "; " + skipped.size() + " already existed and were skipped."));
        return out;
    }

    @Transactional
    public Map<String, Object> deleteRoom(User u, Long id) {
        settings.requireOn();
        access.requireHead(u);
        HostelRoom r = rooms.lockById(id).orElseThrow(() -> new IllegalArgumentException("That room no longer exists."));
        long in = allocations.countCurrentInRoom(id);
        if (in > 0) throw new IllegalArgumentException(in + " student" + (in == 1 ? " lives" : "s live") + " in this room. Move them first.");
        if (allocations.existsByRoom_Id(id)) {
            r.setActive(false);
            r.setNameKey(null);
            rooms.save(r);
            return Map.of("archived", true, "message", BoardingViews.roomName(r) + " is archived. Its history is kept.");
        }
        rooms.delete(r);
        return Map.of("archived", false, "message", BoardingViews.roomName(r) + " is deleted.");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static String keyFor(HostelBlock block, String name) {
        return HostelRoom.keyFor(block, name);
    }

    private HostelBlock blockFor(Hostel h, Long blockId) {
        if (blockId == null) return null;
        HostelBlock b = blocks.findById(blockId).orElseThrow(() -> new IllegalArgumentException("That block no longer exists."));
        if (!b.getHostel().getId().equals(h.getId()) || !b.isActive())
            throw new IllegalArgumentException("That block is not part of " + h.getName() + ".");
        return b;
    }

    Hostel hostelOrThrow(Long id) {
        return hostels.findById(id).orElseThrow(() -> new IllegalArgumentException("That " + settings.word().toLowerCase() + " no longer exists."));
    }

    private Hostel activeHostel(Long id) {
        Hostel h = hostelOrThrow(id);
        if (!h.isActive()) throw new IllegalArgumentException(h.getName() + " is archived. Restore it first.");
        return h;
    }

    /** After a gender change on a hostel, block or room: everyone living there must still be allowed to. */
    private void requireOccupantsStillFit(Long hostelId) {
        List<User> misfits = allocations.findCurrentInHostel(hostelId).stream()
                .filter(a -> !a.getRoom().effectiveGender().admits(a.getStudent().getGender()))
                .map(RoomAllocation::getStudent).toList();
        if (!misfits.isEmpty())
            throw new IllegalArgumentException("That would leave " + BoardingViews.names(misfits)
                    + " in a room that is no longer for them. Move " + (misfits.size() == 1 ? "them" : "those students") + " first.");
    }
}
