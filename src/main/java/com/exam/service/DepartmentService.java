package com.exam.service;

import com.exam.DTO.DepartmentDTO;
import com.exam.model.exam.Department;
import com.exam.repository.DepartmentRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.exam.model.academic.SchoolWords;
import com.exam.model.academic.SystemMode;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class DepartmentService {

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private com.exam.repository.ProgramRepository programRepository;

    /** Numbered ones first (sortOrder), then the rest in the order they were added. */
    public static final Comparator<Department> ORDER = Comparator
            .comparing(Department::getSortOrder, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(Department::getId, Comparator.nullsLast(Comparator.naturalOrder()));

    public DepartmentDTO createDepartment(DepartmentDTO dto) {
        if (departmentRepository.existsByName(dto.getName())) {
            throw new RuntimeException(SchoolWords.apply("Department with name '" + dto.getName() + "' already exists."));
        }
        if (departmentRepository.existsByCode(dto.getCode())) {
            throw new RuntimeException(SchoolWords.apply("Department with code '" + dto.getCode() + "' already exists."));
        }
        Department dept = Department.builder()
                .name(dto.getName())
                .code(dto.getCode().toUpperCase())
                .description(dto.getDescription())
                .sortOrder(nextSortOrder())
                .durationYears(SystemMode.current().isSchool() ? years(dto.getDurationYears()) : null)
                .build();
        return toDTO(departmentRepository.save(dept));
    }

    public List<DepartmentDTO> getAllDepartments() {
        return departmentRepository.findAll()
                .stream().sorted(ORDER).map(this::toDTO).collect(Collectors.toList());
    }

    public DepartmentDTO getDepartmentById(Long id) {
        return toDTO(departmentRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Department not found: " + id)));
    }

    public DepartmentDTO updateDepartment(Long id, DepartmentDTO dto) {
        Department dept = departmentRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Department not found: " + id));
        if (dto.getName() != null && !dto.getName().isBlank() && !dto.getName().equals(dept.getName())) {
            if (departmentRepository.existsByName(dto.getName()))
                throw new RuntimeException(SchoolWords.apply("Department with name '" + dto.getName() + "' already exists."));
            dept.setName(dto.getName());
        }
        if (dto.getCode() != null && !dto.getCode().isBlank()) dept.setCode(dto.getCode().toUpperCase());
        if (dto.getDescription() != null) dept.setDescription(dto.getDescription());
        if (SystemMode.current().isSchool() && dto.getDurationYears() != null) {
            int years = years(dto.getDurationYears());  // not null here
            // A shorter section can't strand the classes in its later years
            int highest = programRepository.findByDepartment_Id(id).stream().map(com.exam.model.exam.Program::getSectionYear)
                    .filter(java.util.Objects::nonNull).max(Integer::compare).orElse(0);
            if (highest > years)
                throw new IllegalArgumentException(dept.getName() + " has classes in year " + highest
                        + ". Move or delete them before making it " + years + " year" + (years == 1 ? "" : "s") + ".");
            dept.setDurationYears(years);
        }
        return toDTO(departmentRepository.save(dept));
    }

    /** Saves the order the Super Admin dragged them into; ids not listed keep their place after these. */
    @org.springframework.transaction.annotation.Transactional
    public List<DepartmentDTO> reorder(List<Long> ids) {
        if (ids == null || ids.isEmpty()) throw new IllegalArgumentException("Nothing to reorder.");
        java.util.Map<Long, Department> byId = departmentRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Department::getId, d -> d));
        int n = 1;
        for (Long id : new java.util.LinkedHashSet<>(ids)) {
            Department d = byId.get(id);
            if (d != null) d.setSortOrder(n++);
        }
        departmentRepository.saveAll(byId.values());
        return getAllDepartments();
    }

    public void deleteDepartment(Long id) {
        if (!departmentRepository.existsById(id)) {
            throw new EntityNotFoundException("Department not found: " + id);
        }
        departmentRepository.deleteById(id);
    }

    public Department getEntityById(Long id) {
        return departmentRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Department not found: " + id));
    }

    /** A section runs 1 to 12 years; none given = not set yet (its classes may then take any year). */
    private static Integer years(Integer y) {
        if (y == null) return null;
        if (y < 1 || y > 12) throw new IllegalArgumentException("A section runs from 1 to 12 years.");
        return y;
    }

    /** Schools number sections as they are added; universities keep the order they were created in. */
    private Integer nextSortOrder() {
        if (!com.exam.model.academic.SystemMode.current().isSchool()) return null;
        List<Department> all = departmentRepository.findAll().stream().sorted(ORDER).collect(Collectors.toList());
        // Unnumbered ones (added before) get numbers first, so the new one goes last, not first
        if (all.stream().anyMatch(d -> d.getSortOrder() == null)) {
            for (int i = 0; i < all.size(); i++) all.get(i).setSortOrder(i + 1);
            departmentRepository.saveAll(all);
        }
        return all.stream().map(Department::getSortOrder).max(Integer::compare).orElse(0) + 1;
    }

    private DepartmentDTO toDTO(Department d) {
        return DepartmentDTO.builder()
                .id(d.getId())
                .name(d.getName())
                .code(d.getCode())
                .description(d.getDescription())
                .sortOrder(d.getSortOrder())
                .durationYears(d.getDurationYears())
                .build();
    }
}
