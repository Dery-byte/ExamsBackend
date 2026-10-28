package com.exam.service;

import com.exam.DTO.ProgramDTO;
import com.exam.model.academic.SystemMode;
import com.exam.model.exam.Department;
import com.exam.model.exam.Program;
import com.exam.repository.DepartmentRepository;
import com.exam.repository.ProgramRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Programmes. In the school modes each programme is one class (Primary 1, JHS 2 …) inside a section
 * (the department record: Primary School, Junior High School …). A section runs a number of years
 * (Primary 6, JHS 3) and each class sits in one of them; streams (JHS 1 Gold, JHS 1 Silver) share a
 * year. A class itself is one year long (level 1) and has a next class its students are promoted to.
 */
@Service
public class ProgramService {

    @Autowired
    private ProgramRepository programRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    /** Section order, then the year in the section, then class order, then the order they were added. */
    public static final Comparator<Program> ORDER = Comparator
            .comparing(Program::getDepartment, Comparator.nullsLast(DepartmentService.ORDER))
            .thenComparing(Program::getSectionYear, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(Program::getSortOrder, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(Program::getId, Comparator.nullsLast(Comparator.naturalOrder()));

    private static boolean school() { return SystemMode.current().isSchool(); }

    private static String word() { return school() ? "class" : "programme"; }

    public ProgramDTO createProgram(ProgramDTO dto) {
        Department dept = departmentRepository.findById(dto.getDepartmentId())
                .orElseThrow(() -> new EntityNotFoundException("Department not found: " + dto.getDepartmentId()));
        // A school class is always one year: its students move to the next class, not up a level
        int years = school() ? 1 : dto.getDurationYears();
        if (years < 1 || years > 10) {
            throw new IllegalArgumentException("Duration must be between 1 and 10 years.");
        }
        requireUniqueName(dept, dto.getName(), null);
        Program program = Program.builder()
                .name(dto.getName().trim())
                .code(dto.getCode().toUpperCase())
                .durationYears(years)
                .department(dept)
                .enabled(true)  // new programs are enabled by default
                .build();
        if (dto.getSemestersPerLevel() != null) {
            program.setSemestersPerLevelMap(dto.getSemestersPerLevel());
        }
        if (school()) {
            program.setSectionYear(sectionYear(dept, dto.getSectionYear(), null));
            program.setSortOrder(dto.getSortOrder() != null ? dto.getSortOrder() : nextSortOrder(dept.getId()));
            applyNext(program, dto);
        }
        return toDTO(programRepository.save(program), route());
    }

    /** Returns ALL programs — used by Super Admin (sees disabled ones too). */
    public List<ProgramDTO> getAllPrograms() {
        return list(programRepository.findAll());
    }

    /** Returns only ENABLED programs — used by all non-SA consumers. */
    public List<ProgramDTO> getEnabledPrograms() {
        return list(programRepository.findByEnabledTrue());
    }

    /** Returns only ENABLED programs for a given department — used by non-SA consumers. */
    public List<ProgramDTO> getEnabledProgramsByDepartment(Long departmentId) {
        return list(programRepository.findByDepartment_IdAndEnabledTrue(departmentId));
    }

    public List<ProgramDTO> getProgramsByDepartment(Long departmentId) {
        return list(programRepository.findByDepartment_Id(departmentId));
    }

    @Autowired
    private com.exam.repository.UserRepository userRepository;

    public List<ProgramDTO> getMyDepartmentPrograms(String username) {
        if (username == null) return getEnabledPrograms();
        com.exam.model.User user = userRepository.findByUsername(username).orElse(null);
        if (user == null || user.getDepartment() == null) {
            return getEnabledPrograms();
        }
        return getEnabledProgramsByDepartment(user.getDepartment().getId());
    }

    public ProgramDTO getProgramById(Long id) {
        return toDTO(programRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Program not found: " + id)), route());
    }

    public ProgramDTO updateProgram(Long id, ProgramDTO dto) {
        Program program = programRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Program not found: " + id));
        Department dept = program.getDepartment();
        boolean moved = false;
        if (dto.getDepartmentId() != null && !dto.getDepartmentId().equals(dept.getId())) {
            dept = departmentRepository.findById(dto.getDepartmentId())
                    .orElseThrow(() -> new EntityNotFoundException("Department not found: " + dto.getDepartmentId()));
            moved = true;
        }
        if (dto.getName() != null && !dto.getName().isBlank()) {
            if (moved || !dto.getName().trim().equalsIgnoreCase(program.getName())) requireUniqueName(dept, dto.getName(), id);
            program.setName(dto.getName().trim());
        }
        if (dto.getCode() != null && !dto.getCode().isBlank()) program.setCode(dto.getCode().toUpperCase());
        if (dto.getDurationYears() > 0) program.setDurationYears(dto.getDurationYears());
        program.setDepartment(dept);
        if (dto.getSemestersPerLevel() != null) {
            program.setSemestersPerLevelMap(dto.getSemestersPerLevel());
        }
        if (school()) {
            if (dto.getSectionYear() != null || moved) program.setSectionYear(sectionYear(dept, dto.getSectionYear() != null ? dto.getSectionYear() : program.getSectionYear(), id));
            if (dto.getSortOrder() != null) program.setSortOrder(dto.getSortOrder());
            else if (moved) program.setSortOrder(nextSortOrder(dept.getId()));   // goes last in its new section
            applyNext(program, dto);
        }
        return toDTO(programRepository.save(program), route());
    }

    /** Saves the order of the classes in a section, as the Super Admin arranged them. */
    @Transactional
    public List<ProgramDTO> reorder(List<Long> ids) {
        if (ids == null || ids.isEmpty()) throw new IllegalArgumentException("Nothing to reorder.");
        Map<Long, Program> byId = programRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Program::getId, p -> p));
        int n = 1;
        for (Long id : new LinkedHashSet<>(ids)) {
            Program p = byId.get(id);
            if (p != null) p.setSortOrder(n++);
        }
        programRepository.saveAll(byId.values());
        return getAllPrograms();
    }

    /**
     * Toggles the enabled/disabled state of a program.
     * Super Admin only — called via PATCH /super-admin/programs/{id}/toggle.
     */
    public ProgramDTO toggleProgram(Long id) {
        Program program = programRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Program not found: " + id));
        program.setEnabled(!program.isEnabled());
        return toDTO(programRepository.save(program), route());
    }

    @Transactional
    public void deleteProgram(Long id) {
        if (!programRepository.existsById(id))
            throw new EntityNotFoundException("Program not found: " + id);
        programRepository.clearNextProgram(id);   // classes that promoted into it fall back to "next in order"
        programRepository.deleteById(id);
    }

    public Program getEntityById(Long id) {
        return programRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Program not found: " + id));
    }

    /**
     * The class a school's students move to at promotion: the one chosen for it, else the next enabled
     * class in order (Primary 6 → JHS 1 when JHS is the next section). Empty = they leave the school.
     */
    public Optional<Program> promotionTarget(Program p) {
        return Optional.ofNullable(route().get(p.getId()));
    }

    // ── helpers ──

    private List<ProgramDTO> list(List<Program> programs) {
        List<Program> ps = new ArrayList<>(programs);
        if (school()) ps.sort(ORDER);
        Map<Long, Program> route = route();
        return ps.stream().map(p -> toDTO(p, route)).collect(Collectors.toList());
    }

    /**
     * programme id → class its students are promoted to (no entry = they leave). Schools only.
     * The class chosen for it wins; otherwise the next year of its section that has classes, else the
     * first year of the next section; among several classes in that year, the same stream (JHS 1 Gold
     * → JHS 2 Gold), else the first. Disabled classes are only reached when chosen.
     */
    private Map<Long, Program> route() {
        Map<Long, Program> out = new HashMap<>();
        if (!school()) return out;
        List<Program> ordered = new ArrayList<>(programRepository.findAll());
        ordered.sort(ORDER);
        Map<Long, Program> byId = ordered.stream().collect(Collectors.toMap(Program::getId, p -> p));
        for (int i = 0; i < ordered.size(); i++) {
            Program p = ordered.get(i);
            if (p.endsSchool()) continue;
            Program chosen = p.getNextProgramId() != null ? byId.get(p.getNextProgramId()) : null;
            if (chosen == null) chosen = nextInOrder(p, ordered.subList(i + 1, ordered.size()));
            if (chosen != null && !chosen.getId().equals(p.getId())) out.put(p.getId(), chosen);
        }
        return out;
    }

    /** The classes after this one in order: the nearest later year (or section) that has enabled classes. */
    private static Program nextInOrder(Program p, List<Program> after) {
        List<Program> open = after.stream().filter(Program::isEnabled).toList();
        if (open.isEmpty()) return null;
        Program first = open.get(0);
        // A class without a year (added before sections had years) just takes the next one in order
        if (p.getSectionYear() == null) return first;
        List<Program> sameStep = open.stream()
                .filter(x -> Objects.equals(x.getDepartment().getId(), first.getDepartment().getId())
                        && Objects.equals(x.getSectionYear(), first.getSectionYear()))
                .filter(x -> !Objects.equals(x.getDepartment().getId(), p.getDepartment().getId())
                        || !Objects.equals(x.getSectionYear(), p.getSectionYear()))
                .toList();
        if (sameStep.isEmpty()) {
            // Streams of its own year come first in order: skip past them to the next year
            Program later = open.stream().filter(x -> !Objects.equals(x.getDepartment().getId(), p.getDepartment().getId())
                    || !Objects.equals(x.getSectionYear(), p.getSectionYear())).findFirst().orElse(null);
            if (later == null) return null;
            sameStep = open.stream().filter(x -> Objects.equals(x.getDepartment().getId(), later.getDepartment().getId())
                    && Objects.equals(x.getSectionYear(), later.getSectionYear())).toList();
        }
        String stream = streamOf(p.getName());
        return sameStep.stream().filter(x -> streamOf(x.getName()).equals(stream)).findFirst().orElse(sameStep.get(0));
    }

    /** What follows the year number in a class name: "JHS 1 Gold" → "gold", "Primary 4" → "". */
    static String streamOf(String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d+").matcher(name == null ? "" : name);
        return m.find() ? name.substring(m.end()).trim().toLowerCase(Locale.ROOT) : "";
    }

    /** The class's year in its section: 1 to the section's years; none given = the year after its section's highest. */
    private int sectionYear(Department dept, Integer year, Long selfId) {
        Integer max = dept.getDurationYears();
        if (year == null) {
            int highest = programRepository.findByDepartment_Id(dept.getId()).stream()
                    .filter(p -> !p.getId().equals(selfId)).map(Program::getSectionYear)
                    .filter(Objects::nonNull).max(Integer::compare).orElse(0);
            year = max == null ? highest + 1 : Math.min(highest + 1, max);
        }
        if (year < 1 || (max != null && year > max))
            throw new IllegalArgumentException(dept.getName() + " runs " + (max == null ? "from year 1" : "years 1 to " + max) + ": choose a year in it.");
        return year;
    }

    private void applyNext(Program program, ProgramDTO dto) {
        boolean last = Boolean.TRUE.equals(dto.getFinalClass());
        program.setFinalClass(last ? Boolean.TRUE : null);
        Long next = last ? null : dto.getNextProgramId();
        if (next != null) {
            if (next.equals(program.getId())) throw new IllegalArgumentException("A class can't be promoted into itself.");
            if (!programRepository.existsById(next)) throw new IllegalArgumentException("Choose a next class that exists.");
        }
        program.setNextProgramId(next);
    }

    private void requireUniqueName(Department dept, String name, Long selfId) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Enter a name.");
        String n = name.trim();
        boolean taken = programRepository.findByDepartment_Id(dept.getId()).stream()
                .anyMatch(p -> !p.getId().equals(selfId) && p.getName().trim().equalsIgnoreCase(n));
        if (taken) throw new IllegalArgumentException("There is already a " + word() + " called \"" + n + "\" in " + dept.getName() + ".");
    }

    /** Next place in a section; classes added before numbering get numbers first so the new one goes last. */
    private Integer nextSortOrder(Long departmentId) {
        List<Program> all = new ArrayList<>(programRepository.findByDepartment_Id(departmentId));
        all.sort(ORDER);
        if (all.stream().anyMatch(p -> p.getSortOrder() == null)) {
            for (int i = 0; i < all.size(); i++) all.get(i).setSortOrder(i + 1);
            programRepository.saveAll(all);
        }
        return all.stream().map(Program::getSortOrder).max(Integer::compare).orElse(0) + 1;
    }

    private ProgramDTO toDTO(Program p, Map<Long, Program> route) {
        // Build full semestersPerLevel: for every configured level, include its semester count (default 2)
        java.util.Map<Integer, Integer> splMap = new java.util.LinkedHashMap<>();
        for (int level : p.getConfiguredLevels()) {
            splMap.put(level, p.getSemestersForLevel(level));
        }
        Program next = route.get(p.getId());
        return ProgramDTO.builder()
                .id(p.getId())
                .name(p.getName())
                .code(p.getCode())
                .durationYears(p.getDurationYears())
                .departmentId(p.getDepartment().getId())
                .departmentName(p.getDepartment().getName())
                .configuredLevels(p.getConfiguredLevels())
                .enabled(p.isEnabled())
                .semestersPerLevel(splMap)
                .sortOrder(p.getSortOrder())
                .sectionYear(p.getSectionYear())
                .sectionYears(p.getDepartment().getDurationYears())
                .nextProgramId(p.getNextProgramId())
                .finalClass(p.endsSchool())
                .promotesToId(next != null ? next.getId() : null)
                .promotesToName(next != null ? next.getName() : null)
                .build();
    }
}
