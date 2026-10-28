package com.exam.service.admin;

import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.academic.SchoolWords;
import com.exam.model.academic.SystemMode;
import com.exam.model.exam.Category;
import com.exam.model.exam.Department;
import com.exam.model.exam.Program;
import com.exam.repository.CategoryRepository;
import com.exam.repository.DepartmentRepository;
import com.exam.repository.ProgramRepository;
import com.exam.repository.UserRepository;
import com.exam.service.comms.NotificationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Bulk import of students, lecturers and courses from spreadsheet rows (the browser parses
 * CSV / Excel and sends one map per row, keyed by normalised column name).
 * <p>
 * {@code commit=false} validates only and reports every problem per row. {@code commit=true}
 * creates the valid rows and skips the rest. Imported accounts with no password column get a
 * random temporary password, returned once so it can be handed to the user. With {@code notify},
 * each new user is also emailed their username and temporary password (generated or from the file).
 * <p>
 * HODs can only import into their own department (and can't create global courses).
 * <p>
 * Students may also carry Gender, Residency (day / boarding, when the institution has both) and
 * House / Block / Room / Bed columns; the room is checked like any other (gender, level, free beds,
 * beds taken by earlier rows) and given once the account exists, by staff who look after that house.
 */
@Service
public class ImportService {

    public static final int MAX_ROWS = 2000;
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final String PW_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired private UserRepository userRepository;
    @Autowired private ProgramRepository programRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private CredentialEmailService credentialEmailService;
    @Autowired private com.exam.service.boarding.BoardingBulkService boardingBulk;
    @Autowired private com.exam.service.boarding.BoardingSettings boardingSettings;

    /** One validated row, ready to create. */
    private record Row(int number, Map<String, String> data, List<String> errors, String summary, Object resolved) {}

    public Map<String, Object> run(User actor, String type, List<Map<String, String>> rawRows, boolean commit) {
        return run(actor, type, rawRows, commit, false);
    }

    public Map<String, Object> run(User actor, String type, List<Map<String, String>> rawRows, boolean commit, boolean notify) {
        if (actor.getRole() != Role.SUPER_ADMIN && actor.getRole() != Role.ADMIN)
            throw new AccessDeniedException("Only the Super Admin and HODs can import data.");
        if (rawRows == null || rawRows.isEmpty()) throw new IllegalArgumentException("The file has no data rows.");
        if (rawRows.size() > MAX_ROWS) throw new IllegalArgumentException("Import at most " + MAX_ROWS + " rows at a time.");

        List<Map<String, String>> rows = rawRows.stream().map(ImportService::normaliseKeys).toList();
        List<Row> checked = switch (type) {
            case "students" -> validateUsers(actor, rows, Role.NORMAL);
            case "lecturers" -> validateUsers(actor, rows, Role.LECTURER);
            case "courses" -> validateCourses(actor, rows);
            default -> throw new IllegalArgumentException("Unknown import type: " + type);
        };

        List<Map<String, String>> credentials = new ArrayList<>();
        int created = 0;
        if (commit) {
            for (Row r : checked) {
                if (!r.errors().isEmpty()) continue;
                try {
                    if (type.equals("courses")) createCourse(r);
                    else credentials.add(createUser(actor, r, type.equals("students") ? Role.NORMAL : Role.LECTURER));
                    created++;
                } catch (Exception e) {
                    r.errors().add("Could not be saved: " + e.getMessage());
                }
            }
        }
        int emailed = notify && !credentials.isEmpty()
                ? credentialEmailService.queue(credentials, type.equals("students") ? "student" : "lecturer") : 0;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", type);
        out.put("committed", commit);
        out.put("total", checked.size());
        out.put("valid", checked.stream().filter(r -> r.errors().isEmpty()).count());
        out.put("created", created);
        out.put("emailed", emailed);
        out.put("rows", checked.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("row", r.number());
            m.put("summary", r.summary());
            m.put("ok", r.errors().isEmpty());
            m.put("errors", r.errors());
            return m;
        }).toList());
        // Only generated passwords are returned; passwords supplied in the file are not echoed back
        out.put("credentials", credentials.stream().filter(c -> c.get("generated") != null).map(c -> {
            Map<String, String> m = new LinkedHashMap<>();
            for (String k : List.of("name", "username", "email", "password")) m.put(k, c.get(k));
            return m;
        }).toList());
        return out;
    }

    // ── Users ────────────────────────────────────────────────────────────────

    private List<Row> validateUsers(User actor, List<Map<String, String>> rows, Role role) {
        boolean student = role == Role.NORMAL;
        boolean school = SystemMode.current().isSchool();
        Set<String> existingUsernames = new HashSet<>(), existingEmails = new HashSet<>();
        userRepository.findAll().forEach(u -> {
            if (u.getUsername() != null) existingUsernames.add(u.getUsername().toLowerCase());
            if (u.getEmail() != null) existingEmails.add(u.getEmail().toLowerCase());
        });
        List<Program> programs = programRepository.findAll();
        List<Department> departments = departmentRepository.findAll();
        Set<String> seenUsernames = new HashSet<>(), seenEmails = new HashSet<>();
        com.exam.service.boarding.BoardingBulkService.StudentImportRooms rooms = null;

        List<Row> out = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Map<String, String> d = rows.get(i);
            List<String> errors = new ArrayList<>();
            String first = req(d, errors, "First name", "firstname", "first");
            String last = req(d, errors, "Last name", "lastname", "surname", "last");
            String email = req(d, errors, "Email", "email", "emailaddress");
            String username = req(d, errors, student ? (school ? "Admission No." : "Student ID") : "Staff ID",
                    student ? new String[]{"studentid", "username", "indexnumber", "admissionno", "admissionnumber", "id"} : new String[]{"staffid", "username", "id"});

            if (email != null) {
                if (!EMAIL.matcher(email).matches()) errors.add("Email '" + email + "' is not valid.");
                else if (existingEmails.contains(email.toLowerCase())) errors.add("Email " + email + " is already registered.");
                else if (!seenEmails.add(email.toLowerCase())) errors.add("Email " + email + " appears twice in the file.");
            }
            if (username != null) {
                if (existingUsernames.contains(username.toLowerCase())) errors.add("ID " + username + " already has an account.");
                else if (!seenUsernames.add(username.toLowerCase())) errors.add("ID " + username + " appears twice in the file.");
            }
            String password = opt(d, "password");
            if (password != null && password.length() < 6) errors.add("Password must be at least 6 characters.");

            Object resolved;
            if (student) {
                Program p = null;
                // Schools call it "Class" (Primary 4); either heading works
                String prog = req(d, errors, school ? "Class" : "Program", "program", "programme", "programcode", "class", "classname", "classcode");
                if (prog != null) {
                    p = programs.stream().filter(x -> prog.equalsIgnoreCase(x.getCode()) || prog.equalsIgnoreCase(x.getName())).findFirst().orElse(null);
                    if (p == null) errors.add(SchoolWords.apply("Program '" + prog + "' not found (use its code or exact name)."));
                    else if (!inScope(actor, p.getDepartment())) errors.add(SchoolWords.apply("Program " + p.getName() + " is not in your department."));
                }
                // A school class is one year: no Level column needed, its only level is used
                boolean oneLevel = school && p != null && p.getConfiguredLevels().size() == 1;
                Integer level = intOrError(d, errors, "Level", !oneLevel, "level", "currentlevel");
                if (level == null && oneLevel) level = p.getConfiguredLevels().get(0);
                if (level != null && p != null && p.getConfiguredLevels() != null && !p.getConfiguredLevels().isEmpty()
                        && !p.getConfiguredLevels().contains(level))
                    errors.add("Level " + level + " is not offered by " + p.getName() + " (" + p.getConfiguredLevels() + ").");
                Integer semester = intOrError(d, errors, school ? "Term" : "Semester", false, "semester", "currentsemester", "term", "currentterm");
                if (semester != null && (semester < 1 || semester > 3)) errors.add((school ? "Term" : "Semester") + " must be 1, 2 or 3.");
                com.exam.model.Gender gender = null;
                com.exam.model.Residency residency = null;
                try { gender = com.exam.model.Gender.parse(opt(d, "gender", "sex")); } catch (IllegalArgumentException e) { errors.add(e.getMessage()); }
                String rawResidency = opt(d, "residency", "dayorboarding", "dayboarding", "boarding");
                if (rawResidency != null && boardingSettings.mode() == com.exam.model.boarding.ResidencyMode.BOTH) {
                    try { residency = com.exam.model.Residency.parse(rawResidency); } catch (IllegalArgumentException e) { errors.add(e.getMessage()); }
                }
                com.exam.service.boarding.BoardingBulkService.NewPlace place = null;
                if (hasRoomColumns(d)) {
                    if (rooms == null) rooms = boardingBulk.studentImportRooms(actor);
                    User candidate = new User();
                    candidate.setFirstname(first);
                    candidate.setLastname(last);
                    candidate.setRole(Role.NORMAL);
                    candidate.setGender(gender);
                    candidate.setCurrentLevel(level);
                    candidate.setResidency(residency);
                    if (errors.isEmpty()) place = rooms.check(d, candidate, errors);
                    if (place != null && residency == null && boardingSettings.mode() == com.exam.model.boarding.ResidencyMode.BOTH)
                        residency = com.exam.model.Residency.BOARDING;   // a room makes them a boarder
                }
                resolved = new Object[]{p, level, semester == null ? 1 : semester, gender, residency, place};
            } else {
                String dep = req(d, errors, school ? "Section" : "Department", "department", "departmentcode", "dept", "section", "sectioncode");
                Department dept = null;
                if (dep != null) {
                    dept = departments.stream().filter(x -> dep.equalsIgnoreCase(x.getCode()) || dep.equalsIgnoreCase(x.getName())).findFirst().orElse(null);
                    if (dept == null) errors.add(SchoolWords.apply("Department '" + dep + "' not found (use its code or exact name)."));
                    else if (!inScope(actor, dept)) errors.add(SchoolWords.apply("Department " + dept.getName() + " is not yours."));
                }
                resolved = dept;
            }
            String summary = ((first == null ? "" : first) + " " + (last == null ? "" : last)).trim() + (username != null ? " (" + username + ")" : "");
            out.add(new Row(i + 2, d, errors, summary, resolved));   // +2: header is row 1 in the sheet
        }
        return out;
    }

    private Map<String, String> createUser(User actor, Row r, Role role) {
        Map<String, String> d = r.data();
        String supplied = opt(d, "password");
        String password = supplied != null ? supplied : tempPassword();
        User u = new User();
        u.setFirstname(opt(d, "firstname", "first"));
        u.setLastname(opt(d, "lastname", "surname", "last"));
        u.setEmail(opt(d, "email", "emailaddress"));
        u.setUsername(role == Role.NORMAL ? opt(d, "studentid", "username", "indexnumber", "admissionno", "admissionnumber", "id") : opt(d, "staffid", "username", "id"));
        u.setPhone(opt(d, "phone", "phonenumber", "mobile"));
        u.setPassword(passwordEncoder.encode(password));
        u.setMustChangePassword(Boolean.TRUE);
        u.setRole(role);
        u.setEnabled(true);
        if (role == Role.NORMAL) {
            Object[] res = (Object[]) r.resolved();
            u.setProgram((Program) res[0]);
            u.setCurrentLevel((Integer) res[1]);
            u.setCurrentSemester((Integer) res[2]);
            u.setGender((com.exam.model.Gender) res[3]);
            u.setResidency((com.exam.model.Residency) res[4]);
        } else {
            u.setDepartment((Department) r.resolved());
        }
        userRepository.save(u);
        if (role == Role.NORMAL && ((Object[]) r.resolved())[5] instanceof com.exam.service.boarding.BoardingBulkService.NewPlace place) {
            try { boardingBulk.placeNew(u, place, actor); }
            catch (RuntimeException e) { r.errors().add("Account created, but no room was given: " + e.getMessage()); }
        }
        Map<String, String> c = new LinkedHashMap<>();
        c.put("name", r.summary());
        c.put("username", u.getUsername());
        c.put("email", u.getEmail());
        c.put("password", password);
        c.put("firstname", u.getFirstname());
        if (supplied == null) c.put("generated", "true");
        return c;
    }

    // ── Courses ──────────────────────────────────────────────────────────────

    private List<Row> validateCourses(User actor, List<Map<String, String>> rows) {
        boolean school = SystemMode.current().isSchool();
        Set<String> existingCodes = new HashSet<>();
        categoryRepository.findAll().forEach(c -> { if (c.getCourseCode() != null) existingCodes.add(c.getCourseCode().trim().toLowerCase()); });
        List<Program> programs = programRepository.findAll();
        Map<String, User> lecturers = new HashMap<>();
        userRepository.findByRole(Role.LECTURER).forEach(u -> lecturers.put(u.getUsername().toLowerCase(), u));
        Set<String> seen = new HashSet<>();

        List<Row> out = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Map<String, String> d = rows.get(i);
            List<String> errors = new ArrayList<>();
            String code = req(d, errors, school ? "Subject code" : "Course code", "coursecode", "code", "subjectcode");
            String title = req(d, errors, "Title", "title", "coursetitle", "subjecttitle", "name");
            if (code != null) {
                if (existingCodes.contains(code.toLowerCase())) errors.add("Course code " + code + " already exists.");
                else if (!seen.add(code.toLowerCase())) errors.add("Course code " + code + " appears twice in the file.");
            }
            // Schools: a subject's classes are one year each, so its level is 1 unless the file says otherwise
            Integer level = intOrError(d, errors, "Level", !school, "level");
            if (level == null && school) level = 1;
            Integer semester = intOrError(d, errors, school ? "Term" : "Semester", true, "semester", "term");
            if (semester != null && (semester < 1 || semester > 3)) errors.add((school ? "Term" : "Semester") + " must be 1, 2 or 3.");
            Integer credits = intOrError(d, errors, "Credit units", false, "creditunits", "credits", "cu");
            if (credits != null && (credits < 0 || credits > 30)) errors.add("Credit units must be between 0 and 30.");

            Set<Program> chosen = new HashSet<>();
            String progs = opt(d, "programs", "program", "programcodes", "classes", "class", "classcodes");
            if (progs != null) {
                for (String token : progs.split("[;,|]")) {
                    String t = token.trim();
                    if (t.isEmpty()) continue;
                    Program p = programs.stream().filter(x -> t.equalsIgnoreCase(x.getCode()) || t.equalsIgnoreCase(x.getName())).findFirst().orElse(null);
                    if (p == null) errors.add(SchoolWords.apply("Program '" + t + "' not found."));
                    else if (!inScope(actor, p.getDepartment())) errors.add(SchoolWords.apply("Program " + p.getName() + " is not in your department."));
                    else chosen.add(p);
                }
            }
            if (chosen.isEmpty() && progs == null && actor.getRole() != Role.SUPER_ADMIN)
                errors.add(SchoolWords.apply(school ? "Classes are required. Only the Super Admin can create subjects for everyone."
                        : "Programs are required. Only the Super Admin can create global courses."));

            User lecturer = null;
            String lec = opt(d, "lecturer", "lecturerid", "staffid", "teacher", "teacherid");
            if (lec != null) {
                lecturer = lecturers.get(lec.toLowerCase());
                if (lecturer == null) errors.add("Lecturer with staff ID '" + lec + "' not found.");
            }
            out.add(new Row(i + 2, d, errors, (code == null ? "" : code + " ") + (title == null ? "" : title),
                    new Object[]{chosen, level, semester, credits, lecturer}));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private void createCourse(Row r) {
        Map<String, String> d = r.data();
        Object[] res = (Object[]) r.resolved();
        Category c = new Category();
        c.setCourseCode(opt(d, "coursecode", "code", "subjectcode").toUpperCase());
        c.setTitle(opt(d, "title", "coursetitle", "subjecttitle", "name"));
        c.setDescription(opt(d, "description"));
        c.setPrograms(new HashSet<>((Set<Program>) res[0]));
        c.setLevel(String.valueOf(res[1]));
        c.setSemester((Integer) res[2]);
        c.setCreditUnits((Integer) res[3]);
        c.setUser((User) res[4]);
        categoryRepository.save(c);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Any of the House / Block / Room / Bed columns filled in. */
    private static boolean hasRoomColumns(Map<String, String> d) {
        return opt(d, "house", "hostel", "hall", "dormitory", "dorm", "block", "wing", "room", "roomno", "roomnumber", "bed", "bedno") != null;
    }

    private static boolean inScope(User actor, Department d) {
        if (actor.getRole() == Role.SUPER_ADMIN) return true;
        return d != null && actor.getDepartment() != null && d.getId().equals(actor.getDepartment().getId());
    }

    /** "First Name" / "first_name" / "FIRSTNAME" → "firstname"; values trimmed. */
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

    private static String opt(Map<String, String> d, String... keys) {
        for (String k : keys) if (d.get(k) != null) return d.get(k);
        return null;
    }

    private static String req(Map<String, String> d, List<String> errors, String label, String... keys) {
        String v = opt(d, keys);
        if (v == null) errors.add(label + " is missing.");
        return v;
    }

    private static Integer intOrError(Map<String, String> d, List<String> errors, String label, boolean required, String... keys) {
        String v = opt(d, keys);
        if (v == null) { if (required) errors.add(label + " is missing."); return null; }
        String clean = v.toLowerCase().replace("level", "").trim();
        if (clean.endsWith(".0")) clean = clean.substring(0, clean.length() - 2);   // Excel numbers
        try { return Integer.parseInt(clean); }
        catch (NumberFormatException e) { errors.add(label + " '" + v + "' is not a number."); return null; }
    }

    private static String tempPassword() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) sb.append(PW_ALPHABET.charAt(RANDOM.nextInt(PW_ALPHABET.length())));
        return sb.toString();
    }
}
