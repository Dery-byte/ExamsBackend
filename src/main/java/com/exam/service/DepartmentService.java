package com.exam.service;

import com.exam.DTO.DepartmentDTO;
import com.exam.model.exam.Department;
import com.exam.repository.DepartmentRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class DepartmentService {

    @Autowired
    private DepartmentRepository departmentRepository;

    public DepartmentDTO createDepartment(DepartmentDTO dto) {
        if (departmentRepository.existsByName(dto.getName())) {
            throw new RuntimeException("Department with name '" + dto.getName() + "' already exists.");
        }
        if (departmentRepository.existsByCode(dto.getCode())) {
            throw new RuntimeException("Department with code '" + dto.getCode() + "' already exists.");
        }
        Department dept = Department.builder()
                .name(dto.getName())
                .code(dto.getCode().toUpperCase())
                .description(dto.getDescription())
                .build();
        return toDTO(departmentRepository.save(dept));
    }

    public List<DepartmentDTO> getAllDepartments() {
        return departmentRepository.findAll()
                .stream().map(this::toDTO).collect(Collectors.toList());
    }

    public DepartmentDTO getDepartmentById(Long id) {
        return toDTO(departmentRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Department not found: " + id)));
    }

    public DepartmentDTO updateDepartment(Long id, DepartmentDTO dto) {
        Department dept = departmentRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Department not found: " + id));
        if (dto.getName() != null && !dto.getName().isBlank()) dept.setName(dto.getName());
        if (dto.getCode() != null && !dto.getCode().isBlank()) dept.setCode(dto.getCode().toUpperCase());
        if (dto.getDescription() != null) dept.setDescription(dto.getDescription());
        return toDTO(departmentRepository.save(dept));
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

    private DepartmentDTO toDTO(Department d) {
        return DepartmentDTO.builder()
                .id(d.getId())
                .name(d.getName())
                .code(d.getCode())
                .description(d.getDescription())
                .build();
    }
}
