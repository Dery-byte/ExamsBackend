package com.exam.controller;

import com.exam.DTO.ProgramDTO;
import com.exam.service.ProgramService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Read-only program & department endpoints accessible by any authenticated user.
 * Used by signup form, AddCategory, etc. to populate dropdowns.
 */
@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/api/v1/auth")
public class ProgramPublicController {

    @Autowired
    private ProgramService programService;

    /** All ENABLED programs (used by signup page to populate Program dropdown).
     *  Disabled programs are hidden from all non-SA users. */
    @GetMapping("/programs")
    public ResponseEntity<List<ProgramDTO>> getAllPrograms() {
        return ResponseEntity.ok(programService.getEnabledPrograms());
    }

    /** Programs for the current admin/HOD's department */
    @GetMapping("/programs/my-department")
    public ResponseEntity<List<ProgramDTO>> getMyDepartmentPrograms(java.security.Principal principal) {
        String username = principal != null ? principal.getName() : null;
        return ResponseEntity.ok(programService.getMyDepartmentPrograms(username));
    }

    /** Enabled programs filtered by department (used by admin/add-course page) */
    @GetMapping("/programs/department/{deptId}")
    public ResponseEntity<List<ProgramDTO>> getProgramsByDept(@PathVariable Long deptId) {
        return ResponseEntity.ok(programService.getEnabledProgramsByDepartment(deptId));
    }

    /** Single program with its configured levels */
    @GetMapping("/programs/{id}")
    public ResponseEntity<ProgramDTO> getProgram(@PathVariable Long id) {
        return ResponseEntity.ok(programService.getProgramById(id));
    }

    @Autowired
    private com.exam.service.DepartmentService departmentService;

    @GetMapping("/departments")
    public ResponseEntity<List<com.exam.DTO.DepartmentDTO>> getAllDepartments() {
        return ResponseEntity.ok(departmentService.getAllDepartments());
    }
}
