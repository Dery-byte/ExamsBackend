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
                .build();
        return toDTO(programRepository.save(program));
    }

    public List<ProgramDTO> getAllPrograms() {
        return programRepository.findAll()
                .stream().map(this::toDTO).collect(Collectors.toList());
    }

    public List<ProgramDTO> getProgramsByDepartment(Long departmentId) {
        return programRepository.findByDepartment_Id(departmentId)
                .stream().map(this::toDTO).collect(Collectors.toList());
    }

    @Autowired
    private com.exam.repository.UserRepository userRepository;

    public List<ProgramDTO> getMyDepartmentPrograms(String username) {
        if (username == null) return getAllPrograms();
        com.exam.model.User user = userRepository.findByUsername(username).orElse(null);
        if (user == null || user.getDepartment() == null) {
            return getAllPrograms();
        }
        return getProgramsByDepartment(user.getDepartment().getId());
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
        return ProgramDTO.builder()
                .id(p.getId())
                .name(p.getName())
                .code(p.getCode())
                .durationYears(p.getDurationYears())
                .departmentId(p.getDepartment().getId())
                .departmentName(p.getDepartment().getName())
                .configuredLevels(p.getConfiguredLevels())
                .build();
    }
}
