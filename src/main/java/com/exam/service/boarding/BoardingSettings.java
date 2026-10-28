package com.exam.service.boarding;

import com.exam.model.Residency;
import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.academic.SystemMode;
import com.exam.model.boarding.ResidencyMode;
import com.exam.repository.UserRepository;
import com.exam.service.SystemSettingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Boarding switches. The developer chooses the residency mode (day only / boarding only / both);
 * the Super Admin and the head of boarding choose the wording, the floor names and whether students
 * see their roommates.
 */
@Service
public class BoardingSettings {

    /** Developer only: DAY, BOARDING or BOTH. */
    public static final String MODE = "RESIDENCY_MODE";
    public static final String WORD = "BOARDING_WORD";
    public static final String GROUND_FLOOR = "BOARDING_GROUND_FLOOR";
    public static final String SHOW_ROOMMATES = "BOARDING_SHOW_ROOMMATES";
    public static final String SHOW_ON_REPORT = "BOARDING_SHOW_ON_REPORT";
    /** Schools: TOGETHER (classmates share rooms) or MIXED (classmates spread across rooms); the Super Admin decides. */
    public static final String CLASSMATES = "BOARDING_CLASSMATES";

    /** An AccessDeniedException, so every controller (and the global handler) answers 403. */
    public static final class BoardingOffException extends AccessDeniedException {
        public BoardingOffException() { super("This institution has no boarding students."); }
    }

    @Autowired private SystemSettingService settings;
    @Autowired private UserRepository users;

    public ResidencyMode mode() {
        ResidencyMode m = ResidencyMode.parse(settings.getSetting(MODE));
        return m == null ? ResidencyMode.DAY : m;
    }

    public boolean on() { return mode().hasBoarding(); }

    public void requireOn() {
        if (!on()) throw new BoardingOffException();
    }

    /**
     * Developer only. Moving to "both" gives every student with no residency recorded the one they
     * all had until now (day students in a day school, boarders in a boarding school).
     */
    @Transactional
    public ResidencyMode setMode(ResidencyMode next) {
        if (next == null) throw new IllegalArgumentException("Choose day only, boarding only or both.");
        ResidencyMode before = mode();
        settings.updateSetting(MODE, next.name());
        if (next == ResidencyMode.BOTH && before != ResidencyMode.BOTH)
            users.fillMissingResidency(before == ResidencyMode.BOARDING ? Residency.BOARDING : Residency.DAY);
        return next;
    }

    /** What the student is right now, by the mode: everyone is a boarder in a boarding school. Null = not recorded. */
    public Residency residencyOf(User student) {
        return switch (mode()) {
            case DAY -> Residency.DAY;
            case BOARDING -> Residency.BOARDING;
            case BOTH -> student.getResidency();
        };
    }

    public boolean isBoarder(User student) {
        return student != null && student.getRole() == Role.NORMAL && residencyOf(student) == Residency.BOARDING;
    }

    // ── Wording ──────────────────────────────────────────────────────────────

    /** "House" in schools, "Hall" at university, unless the Super Admin chose another word. */
    public String word() {
        String w = settings.getSetting(WORD);
        if (w != null && !w.isBlank()) return w.trim();
        return SystemMode.current().isSchool() ? "House" : "Hall";
    }

    public String plural() {
        return plural(word());
    }

    static String plural(String w) {
        String lower = w.toLowerCase();
        if (lower.endsWith("y") && w.length() > 1 && "aeiou".indexOf(lower.charAt(lower.length() - 2)) < 0)
            return w.substring(0, w.length() - 1) + "ies";
        if (lower.endsWith("s") || lower.endsWith("x") || lower.endsWith("ch") || lower.endsWith("sh")) return w + "es";
        return w + "s";
    }

    /** Floor 0 is called "Ground floor" (default) or "Floor 1". */
    public boolean groundFloor() { return settings.getBooleanSetting(GROUND_FLOOR, true); }

    public boolean showRoommates() { return settings.getBooleanSetting(SHOW_ROOMMATES, true); }

    /** Print the house (or day / boarding) on report cards. */
    public boolean showOnReport() { return settings.getBooleanSetting(SHOW_ON_REPORT, true); }

    /** Whether auto-allocation keeps classmates in the same rooms (default) or mixes classes. */
    public boolean classmatesTogether() { return !"MIXED".equalsIgnoreCase(settings.getSetting(CLASSMATES)); }

    private static final String[] ORDINALS = {"First", "Second", "Third", "Fourth", "Fifth", "Sixth", "Seventh", "Eighth", "Ninth", "Tenth"};

    public String floorLabel(int floor) {
        return floorLabel(floor, groundFloor());
    }

    static String floorLabel(int floor, boolean groundFloor) {
        if (!groundFloor) return "Floor " + (floor + 1);
        if (floor == 0) return "Ground floor";
        if (floor <= ORDINALS.length) return ORDINALS[floor - 1] + " floor";
        int n = floor;
        String suffix = (n % 100 >= 11 && n % 100 <= 13) ? "th" : switch (n % 10) { case 1 -> "st"; case 2 -> "nd"; case 3 -> "rd"; default -> "th"; };
        return n + suffix + " floor";
    }

    public Map<String, Object> view() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", mode().name());
        m.put("modeLabel", mode().label());
        m.put("word", word());
        m.put("plural", plural());
        m.put("customWord", settings.getSetting(WORD));
        m.put("groundFloor", groundFloor());
        m.put("showRoommates", showRoommates());
        m.put("showOnReport", showOnReport());
        m.put("classmates", classmatesTogether() ? "TOGETHER" : "MIXED");
        return m;
    }

    public record SettingsRequest(String word, Boolean groundFloor, Boolean showRoommates, Boolean showOnReport, String classmates) {}

    public Map<String, Object> save(User actor, SettingsRequest req, BoardingAccess access) {
        if (!access.isHead(actor)) throw new AccessDeniedException("Only the Super Admin or the head of boarding can change these settings.");
        if (req == null) throw new IllegalArgumentException("Nothing to save.");
        if (req.word() != null) {
            String w = req.word().trim();
            if (w.length() > 20) throw new IllegalArgumentException("Keep the word short (20 letters at most), e.g. House, Hall or Hostel.");
            if (!w.isEmpty() && !w.matches("[\\p{L} '-]+")) throw new IllegalArgumentException("Use letters only, e.g. House, Hall or Hostel.");
            if (w.isEmpty()) settings.deleteSetting(WORD); else settings.updateSetting(WORD, w);
        }
        if (req.groundFloor() != null) settings.updateSetting(GROUND_FLOOR, String.valueOf(req.groundFloor()));
        if (req.showRoommates() != null) settings.updateSetting(SHOW_ROOMMATES, String.valueOf(req.showRoommates()));
        if (req.showOnReport() != null) settings.updateSetting(SHOW_ON_REPORT, String.valueOf(req.showOnReport()));
        if (req.classmates() != null) {
            if (actor.getRole() != com.exam.model.Role.SUPER_ADMIN)
                throw new AccessDeniedException("Only the Super Admin decides whether classmates share rooms.");
            String c = req.classmates().trim().toUpperCase();
            if (!c.equals("TOGETHER") && !c.equals("MIXED")) throw new IllegalArgumentException("Choose to keep classmates together or to mix them.");
            settings.updateSetting(CLASSMATES, c);
        }
        return view();
    }
}
