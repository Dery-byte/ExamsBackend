package com.exam.service;

import com.exam.DTO.ProgramDTO;
import com.exam.model.exam.Department;
import com.exam.model.exam.Program;
import com.exam.repository.DepartmentRepository;
import com.exam.repository.ProgramRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class ProgramService {

    @Autowired
    private ProgramRepository programRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    public ProgramDTO createProgram(ProgramDTO dto) {
        Department dept = departmentRepository.findById(dto.getDepartmentId())
                .orElseThrow(() -> new EntityNotFoundException("Department not found: " + dto.getDepartmentId()));
        if (dto.getDurationYears() < 1 || dto.getDurationYears() > 10) {
            throw new IllegalArgumentException("Duration must be between 1 and 10 years.");
        }
        Program program = Program.builder()
                .name(dto.getName())
                .code(dto.getCode().toUpperCase())
                .durationYears(dto.getDurationYears())
                .department(dept)
                .enabled(true)  // new programs are enabled by default
                .build();
        if (dto.getSemestersPerLevel() != null) {
            program.setSemestersPerLevelMap(dto.getSemestersPerLevel());
        }
        return toDTO(programRepository.save(program));
    }

    /** Returns ALL programs — used by Super Admin (sees disabled ones too). */
    public List<ProgramDTO> getAllPrograms() {
        return programRepository.findAll()
                .stream().map(this::toDTO).collect(Collectors.toList());
    }

    /** Returns only ENABLED programs — used by all non-SA consumers. */
    public List<ProgramDTO> getEnabledPrograms() {
        return programRepository.findByEnabledTrue()
                .stream().map(this::toDTO).collect(Collectors.toList());
    }

    /** Returns only ENABLED programs for a given department — used by non-SA consumers. */
    public List<ProgramDTO> getEnabledProgramsByDepartment(Long departmentId) {
        return programRepository.findByDepartment_IdAndEnabledTrue(departmentId)
                .stream().map(this::toDTO).collect(Collectors.toList());
    }

    public List<ProgramDTO> getProgramsByDepartment(Long departmentId) {
        return programRepository.findByDepartment_Id(departmentId)
                .stream().map(this::toDTO).collect(Collectors.toList());
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
                .orElseThrow(() -> new EntityNotFoundException("Program not found: " + id)));
    }

    public ProgramDTO updateProgram(Long id, ProgramDTO dto) {
        Program program = programRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Program not found: " + id));
        if (dto.getName() != null && !dto.getName().isBlank()) program.setName(dto.getName());
        if (dto.getCode() != null && !dto.getCode().isBlank()) program.setCode(dto.getCode().toUpperCase());
        if (dto.getDurationYears() > 0) program.setDurationYears(dto.getDurationYears());
        if (dto.getDepartmentId() != null) {
            Department dept = departmentRepository.findById(dto.getDepartmentId())
                    .orElseThrow(() -> new EntityNotFoundException("Department not found: " + dto.getDepartmentId()));
            program.setDepartment(dept);
        }
        if (dto.getSemestersPerLevel() != null) {
            program.setSemestersPerLevelMap(dto.getSemestersPerLevel());
        }
        return toDTO(programRepository.save(program));
    }

    /**
     * Toggles the enabled/disabled state of a program.
     * Super Admin only — called via PATCH /super-admin/programs/{id}/toggle.
     */
    public ProgramDTO toggleProgram(Long id) {
        Program program = programRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Program not found: " + id));
        program.setEnabled(!program.isEnabled());
        return toDTO(programRepository.save(program));
    }

    public void deleteProgram(Long id) {
        if (!programRepository.existsById(id))
            throw new EntityNotFoundException("Program not found: " + id);
        programRepository.deleteById(id);
    }

    public Program getEntityById(Long id) {
        return programRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Program not found: " + id));
    }

    private ProgramDTO toDTO(Program p) {
        // Build full semestersPerLevel: for every configured level, include its semester count (default 2)
        java.util.Map<Integer, Integer> splMap = new java.util.LinkedHashMap<>();
        for (int level : p.getConfiguredLevels()) {
            splMap.put(level, p.getSemestersForLevel(level));
        }
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
                .build();
    }
}
