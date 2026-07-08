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

    /** All programs (used by signup page to populate Program dropdown) */
    @GetMapping("/programs")
    public ResponseEntity<List<ProgramDTO>> getAllPrograms() {
        return ResponseEntity.ok(programService.getAllPrograms());
    }

    /** Programs filtered by department (used by admin/add-course page) */
    @GetMapping("/programs/department/{deptId}")
    public ResponseEntity<List<ProgramDTO>> getProgramsByDept(@PathVariable Long deptId) {
        return ResponseEntity.ok(programService.getProgramsByDepartment(deptId));
    }

    /** Single program with its configured levels */
    @GetMapping("/programs/{id}")
    public ResponseEntity<ProgramDTO> getProgram(@PathVariable Long id) {
        return ResponseEntity.ok(programService.getProgramById(id));
    }
}
