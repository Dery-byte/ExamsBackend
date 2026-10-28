package com.exam.service.boarding;

import com.exam.model.User;
import com.exam.model.boarding.HostelBlock;
import com.exam.model.boarding.HostelRoom;
import com.exam.model.boarding.RoomAllocation;
import com.exam.service.comms.CurrentUserService;

import java.util.*;

/** How boarding places and students are shown: names, floor labels and the "House › Block › Floor › Room › Bed" path. */
public final class BoardingViews {

    private BoardingViews() {}

    /** "Room 14" for a bare number or code; names that already say what they are ("Dorm A", "Room 3") stay as typed. */
    public static String roomName(HostelRoom r) {
        String n = r.getName().trim();
        return n.matches("[A-Za-z]{0,3}[0-9]*[-/ ]?[0-9]+[A-Za-z]?") ? "Room " + n : n;
    }

    public static int floorsOf(HostelRoom r) {
        return r.getBlock() != null ? r.getBlock().getFloors() : r.getHostel().getFloors();
    }

    static Map<String, Object> place(HostelRoom r, Integer bed, BoardingSettings settings) {
        Map<String, Object> m = new LinkedHashMap<>();
        HostelBlock b = r.getBlock();
        boolean showFloor = floorsOf(r) > 1;
        m.put("hostelId", r.getHostel().getId());
        m.put("hostel", r.getHostel().getName());
        m.put("blockId", b != null ? b.getId() : null);
        m.put("block", b != null ? b.getName() : null);
        m.put("floor", r.getFloor());
        m.put("floorLabel", showFloor ? settings.floorLabel(r.getFloor()) : null);
        m.put("roomId", r.getId());
        m.put("room", roomName(r));
        m.put("bed", bed);
        List<String> parts = new ArrayList<>();
        parts.add(r.getHostel().getName());
        if (b != null) parts.add(b.getName());
        if (showFloor) parts.add(settings.floorLabel(r.getFloor()));
        parts.add(roomName(r));
        if (bed != null) parts.add("Bed " + bed);
        m.put("path", parts);
        m.put("short", r.getHostel().getName() + " · " + roomName(r) + (bed != null ? " · Bed " + bed : ""));
        return m;
    }

    static Map<String, Object> place(RoomAllocation a, BoardingSettings settings) {
        Map<String, Object> m = place(a.getRoom(), a.getBed(), settings);
        m.put("allocationId", a.getId());
        m.put("since", a.getStartedAt());
        return m;
    }

    static Map<String, Object> student(User s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("name", CurrentUserService.displayName(s));
        m.put("username", s.getUsername());
        m.put("photo", s.getProfilePic());
        m.put("gender", s.getGender() != null ? s.getGender().name() : null);
        m.put("level", s.getCurrentLevel());
        m.put("programme", s.getProgram() != null ? s.getProgram().getName() : null);
        m.put("programId", s.getProgram() != null ? s.getProgram().getId() : null);
        m.put("enabled", s.isEnabled());
        return m;
    }

    /** "1, 2,3" → "1,2,3" (sorted, no repeats); blank → null. */
    static String normaliseLevels(String raw) {
        if (raw == null || raw.isBlank()) return null;
        TreeSet<Integer> levels = new TreeSet<>();
        for (String p : raw.split("[,;\\s]+")) {
            if (p.isBlank()) continue;
            try { levels.add(Integer.parseInt(p.trim())); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("Levels must be numbers separated by commas, e.g. 1, 2."); }
        }
        return levels.isEmpty() ? null : String.join(",", levels.stream().map(String::valueOf).toList());
    }

    static Set<Integer> levels(String normalised) {
        if (normalised == null || normalised.isBlank()) return Set.of();
        Set<Integer> s = new TreeSet<>();
        for (String p : normalised.split(",")) {
            try { s.add(Integer.parseInt(p.trim())); } catch (NumberFormatException ignored) { /* skip */ }
        }
        return s;
    }

    static String clean(String s, int max, String what) {
        if (s == null) return null;
        String t = s.trim().replaceAll("\\s+", " ");
        if (t.isEmpty()) return null;
        if (t.length() > max) throw new IllegalArgumentException(what + " can be at most " + max + " characters.");
        return t;
    }

    static String names(List<User> people) {
        List<String> n = people.stream().map(CurrentUserService::displayName).limit(3).toList();
        String s = String.join(", ", n);
        return people.size() > 3 ? s + " and " + (people.size() - 3) + " more" : s;
    }
}
