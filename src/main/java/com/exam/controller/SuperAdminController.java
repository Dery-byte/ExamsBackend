package com.exam.controller;

import com.exam.DTO.*;
import com.exam.auth.AuthenticationResponse;
import com.exam.auth.RegisterRequest;
import com.exam.helper.UserFoundException;
import com.exam.model.Role;
import com.exam.model.User;
import com.exam.repository.UserRepository;
import com.exam.service.AuthenticationService;
import com.exam.service.DepartmentService;
import com.exam.service.ProgramService;
import com.exam.service.SystemSettingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * All endpoints here are protected by SUPER_ADMIN role in SecurityConfiguration.
 * The Super Admin can:
 *  - Manage Departments
 *  - Manage Programs (with level configuration)
 *  - Create / delete HOD accounts (role = ADMIN)
 *  - Set a student's current semester (and optionally level)
 */
@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/api/v1/super-admin")
public class SuperAdminController {

    @Autowired
    private DepartmentService departmentService;

    @Autowired
    private ProgramService programService;

    @Autowired
    private AuthenticationService authService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private com.exam.repository.DepartmentRepository departmentRepository;

    @Autowired
    private com.exam.service.CategoryService categoryService;

    // ─────────────────────────────────────────────────────────────────────────
    // DEPARTMENTS
    // ─────────────────────────────────────────────────────────────────────────

    @PostMapping("/departments")
    public ResponseEntity<?> createDepartment(@RequestBody DepartmentDTO dto) {
        try {
            return ResponseEntity.ok(departmentService.createDepartment(dto));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", e.getMessage()));
        }
    }

    @GetMapping("/departments")
    public ResponseEntity<List<DepartmentDTO>> getAllDepartments() {
        return ResponseEntity.ok(departmentService.getAllDepartments());
    }

    @GetMapping("/departments/{id}")
    public ResponseEntity<DepartmentDTO> getDepartment(@PathVariable Long id) {
        return ResponseEntity.ok(departmentService.getDepartmentById(id));
    }

    @PutMapping("/departments/{id}")
    public ResponseEntity<?> updateDepartment(@PathVariable Long id, @RequestBody DepartmentDTO dto) {
        try {
            return ResponseEntity.ok(departmentService.updateDepartment(id, dto));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", e.getMessage()));
        }
    }

    @DeleteMapping("/departments/{id}")
    public ResponseEntity<?> deleteDepartment(@PathVariable Long id) {
        try {
            departmentService.deleteDepartment(id);
            return ResponseEntity.ok(Map.of("message", "Department deleted successfully."));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", e.getMessage()));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PROGRAMS
    // ─────────────────────────────────────────────────────────────────────────

    @PostMapping("/programs")
    public ResponseEntity<?> createProgram(@RequestBody ProgramDTO dto) {
        try {
            return ResponseEntity.ok(programService.createProgram(dto));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", e.getMessage()));
        }
    }

    @GetMapping("/programs")
    public ResponseEntity<List<ProgramDTO>> getAllPrograms() {
        return ResponseEntity.ok(programService.getAllPrograms());
    }

    @GetMapping("/programs/department/{deptId}")
    public ResponseEntity<List<ProgramDTO>> getProgramsByDepartment(@PathVariable Long deptId) {
        return ResponseEntity.ok(programService.getProgramsByDepartment(deptId));
    }

    @GetMapping("/programs/{id}")
    public ResponseEntity<ProgramDTO> getProgram(@PathVariable Long id) {
        return ResponseEntity.ok(programService.getProgramById(id));
    }

    @PutMapping("/programs/{id}")
    public ResponseEntity<?> updateProgram(@PathVariable Long id, @RequestBody ProgramDTO dto) {
        try {
            return ResponseEntity.ok(programService.updateProgram(id, dto));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", e.getMessage()));
        }
    }

    @DeleteMapping("/programs/{id}")
    public ResponseEntity<?> deleteProgram(@PathVariable Long id) {
        try {
            programService.deleteProgram(id);
            return ResponseEntity.ok(Map.of("message", "Program deleted successfully."));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * Toggle the enabled/disabled state of a program.
     * Super Admin only. When disabled, the program is hidden from all other roles.
     */
    @PatchMapping("/programs/{id}/toggle")
    public ResponseEntity<?> toggleProgram(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(programService.toggleProgram(id));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", e.getMessage()));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HOD MANAGEMENT
    // ─────────────────────────────────────────────────────────────────────────

    /** Register a new HOD (creates an ADMIN-role user linked to a department) */
    @PostMapping("/register/hod")
    public ResponseEntity<?> registerHod(@RequestBody RegisterHodRequest request) {
        try {
            AuthenticationResponse resp = authService.registerAsHod(request);
            return ResponseEntity.ok(Map.of("message", "HOD account created successfully.", "token", resp.getToken()));
        } catch (UserFoundException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", e.getMessage()));
        } catch (DataIntegrityViolationException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("message", "An account with these details already exists."));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", e.getMessage()));
        }
    }

    /** List all HOD / ADMIN accounts */
    @GetMapping("/admins")
    public ResponseEntity<List<LecturerDTO>> getAllHods() {
        List<User> admins = userRepository.findByRole(Role.ADMIN);
        List<LecturerDTO> dtos = admins.stream().map(u -> {
            LecturerDTO dto = new LecturerDTO();
            dto.setId(u.getId());
            dto.setFirstname(u.getFirstname());
            dto.setLastname(u.getLastname());
            dto.setEmail(u.getEmail());
            dto.setUsername(u.getUsername());
            dto.setPhone(u.getPhone());
            if (u.getDepartment() != null) {
                dto.setDepartmentId(u.getDepartment().getId());
            }
            return dto;
        }).collect(Collectors.toList());
        return ResponseEntity.ok(dtos);
    }

    /** Delete an HOD account */
    @DeleteMapping("/admin/{id}")
    public ResponseEntity<?> deleteHod(@PathVariable Long id) {
        User user = userRepository.findById(id)
                .orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "User not found."));
        }
        if (user.getRole() != Role.ADMIN) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("message", "User is not an HOD/Admin."));
        }
        userRepository.deleteById(id);
        return ResponseEntity.ok(Map.of("message", "HOD account deleted."));
    }

    /** Update an HOD account's details */
    @PutMapping("/admin/{id}")
    public ResponseEntity<?> updateHod(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        User user = userRepository.findById(id).orElse(null);
        if (user == null)
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "User not found."));
        if (user.getRole() != Role.ADMIN)
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", "User is not an HOD/Admin."));

        if (body.containsKey("firstname"))  user.setFirstname((String) body.get("firstname"));
        if (body.containsKey("lastname"))   user.setLastname((String) body.get("lastname"));
        if (body.containsKey("email"))      user.setEmail((String) body.get("email"));
        if (body.containsKey("phone"))      user.setPhone((String) body.get("phone"));
        if (body.containsKey("username"))   user.setUsername((String) body.get("username"));
        // Only update password if provided and non-blank
        String newPass = (String) body.get("password");
        if (newPass != null && !newPass.isBlank()) {
            user.setPassword(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode(newPass));
        }

        if (body.containsKey("departmentId")) {
            Object rawDeptId = body.get("departmentId");
            if (rawDeptId != null) {
                Long deptId = null;
                if (rawDeptId instanceof Number) {
                    deptId = ((Number) rawDeptId).longValue();
                } else if (rawDeptId instanceof String && !((String) rawDeptId).isBlank()) {
                    try {
                        deptId = Long.parseLong((String) rawDeptId);
                    } catch (NumberFormatException e) { }
                }
                if (deptId != null) {
                    com.exam.model.exam.Department dept = departmentRepository.findById(deptId).orElse(null);
                    if (dept != null) {
                        user.setDepartment(dept);
                    }
                }
            }
        }

        userRepository.save(user);
        return ResponseEntity.ok(Map.of("message", "HOD updated successfully.",
                "id", user.getId(), "firstname", user.getFirstname(), "lastname", user.getLastname()));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STUDENT SEMESTER MANAGEMENT
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Set a student's current semester (and optionally override their level).
     * Called by HOD or Super Admin.
     */
    @PutMapping("/student/{id}/level-semester")
    public ResponseEntity<?> setStudentLevelSemester(
            @PathVariable Long id,
            @RequestBody StudentSemesterRequest req) {
        User student = userRepository.findById(id).orElse(null);
        if (student == null)
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Student not found."));
        if (student.getRole() != Role.NORMAL)
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", "User is not a student."));

        if (req.getCurrentSemester() != null) student.setCurrentSemester(req.getCurrentSemester());
        if (req.getCurrentLevel() != null)    student.setCurrentLevel(req.getCurrentLevel());
        userRepository.save(student);

        return ResponseEntity.ok(Map.of(
                "message", "Student level/semester updated.",
                "currentLevel", student.getCurrentLevel(),
                "currentSemester", student.getCurrentSemester()
        ));
    }

    /** List all students (with their program, level, semester info) */
    @GetMapping("/students")
    public ResponseEntity<List<Map<String, Object>>> getAllStudentsWithInfo() {
        List<User> students = userRepository.findByRole(Role.NORMAL);
        List<Map<String, Object>> result = students.stream().map(s -> Map.<String, Object>of(
                "id", s.getId(),
                "firstname", s.getFirstname() != null ? s.getFirstname() : "",
                "lastname", s.getLastname() != null ? s.getLastname() : "",
                "email", s.getEmail() != null ? s.getEmail() : "",
                "username", s.getUsername() != null ? s.getUsername() : "",
                "phone", s.getPhone() != null ? s.getPhone() : "",
                "program", s.getProgram() != null ? s.getProgram().getName() : "",
                "programId", s.getProgram() != null ? s.getProgram().getId() : 0,
                "currentLevel", s.getCurrentLevel() != null ? s.getCurrentLevel() : 0,
                "currentSemester", s.getCurrentSemester() != null ? s.getCurrentSemester() : 0
        )).collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STUDENT PROMOTION  (Super Admin — both forward AND backward)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Promote or demote a single student to any level in their program.
     * Super Admin only — no directional restriction.
     */
    @PutMapping("/student/{id}/promote")
    public ResponseEntity<?> promoteStudent(
            @PathVariable Long id,
            @RequestBody Map<String, Integer> body) {
        User student = userRepository.findById(id).orElse(null);
        if (student == null)
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Student not found."));
        if (student.getRole() != Role.NORMAL)
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", "User is not a student."));

        Integer targetLevel = body.get("targetLevel");
        if (targetLevel == null)
            return ResponseEntity.badRequest().body(Map.of("message", "targetLevel is required."));

        student.setCurrentLevel(targetLevel);
        // Reset semester to 1 when level changes
        student.setCurrentSemester(1);
        userRepository.save(student);
        return ResponseEntity.ok(Map.of("message", "Student moved to Level " + targetLevel,
                "currentLevel", targetLevel, "currentSemester", 1));
    }

    /**
     * Promote ALL students currently at a given level within a specific program to the next (or previous) level.
     * Super Admin only.
     */
    @PutMapping("/students/promote-all/{programId}/{level}")
    public ResponseEntity<?> promoteAllAtLevel(
            @PathVariable Long programId,
            @PathVariable Integer level,
            @RequestBody Map<String, Integer> body) {
        Integer targetLevel = body.get("targetLevel");
        if (targetLevel == null)
            return ResponseEntity.badRequest().body(Map.of("message", "targetLevel is required."));

        List<User> students = userRepository.findByRole(Role.NORMAL).stream()
                .filter(s -> s.getProgram() != null && s.getProgram().getId().equals(programId))
                .filter(s -> level.equals(s.getCurrentLevel()))
                .collect(Collectors.toList());

        students.forEach(s -> { s.setCurrentLevel(targetLevel); s.setCurrentSemester(1); });
        userRepository.saveAll(students);
        return ResponseEntity.ok(Map.of("message", "Promoted " + students.size() + " students in program from Level "
                + level + " to Level " + targetLevel, "count", students.size()));
    }

    /**
     * Promote ALL students currently at a given level within a specific program to the next semester.
     * Capped at the configured max semesters for that level. Super Admin only.
     */
    @PutMapping("/students/promote-semester-all/{programId}/{level}")
    public ResponseEntity<?> promoteSemesterAllAtLevel(
            @PathVariable Long programId,
            @PathVariable Integer level) {

        com.exam.model.exam.Program program = programService.getEntityById(programId);
        int maxSemesters = program.getSemestersForLevel(level);

        List<User> students = userRepository.findByRole(Role.NORMAL).stream()
                .filter(s -> s.getProgram() != null && s.getProgram().getId().equals(programId))
                .filter(s -> level.equals(s.getCurrentLevel()))
                .collect(Collectors.toList());

        long alreadyAtMax = students.stream()
                .filter(s -> s.getCurrentSemester() != null && s.getCurrentSemester() >= maxSemesters)
                .count();

        students.forEach(s -> {
            int currentSem = s.getCurrentSemester() != null ? s.getCurrentSemester() : 1;
            if (currentSem < maxSemesters) {
                s.setCurrentSemester(currentSem + 1);
            }
        });
        userRepository.saveAll(students);
        return ResponseEntity.ok(Map.of(
                "message", "Promoted " + (students.size() - alreadyAtMax) + " students to the next semester. "
                        + alreadyAtMax + " already at max semester (" + maxSemesters + ").",
                "count", students.size(),
                "promoted", students.size() - alreadyAtMax,
                "skipped", alreadyAtMax));
    }

    /**
     * Demote ALL students currently at a given level within a specific program to the previous semester.
     * Stops at semester 1. Super Admin only.
     */
    @PutMapping("/students/demote-semester-all/{programId}/{level}")
    public ResponseEntity<?> demoteSemesterAllAtLevel(
            @PathVariable Long programId,
            @PathVariable Integer level) {

        List<User> students = userRepository.findByRole(Role.NORMAL).stream()
                .filter(s -> s.getProgram() != null && s.getProgram().getId().equals(programId))
                .filter(s -> level.equals(s.getCurrentLevel()))
                .collect(Collectors.toList());

        long alreadyAtMin = students.stream()
                .filter(s -> s.getCurrentSemester() == null || s.getCurrentSemester() <= 1)
                .count();

        students.forEach(s -> {
            int currentSem = s.getCurrentSemester() != null ? s.getCurrentSemester() : 1;
            if (currentSem > 1) {
                s.setCurrentSemester(currentSem - 1);
            }
        });
        userRepository.saveAll(students);
        return ResponseEntity.ok(Map.of(
                "message", "Demoted " + (students.size() - alreadyAtMin) + " students to the previous semester. "
                        + alreadyAtMin + " already at semester 1.",
                "count", students.size(),
                "demoted", students.size() - alreadyAtMin,
                "skipped", alreadyAtMin));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ADMIN COURSE ENROLLMENT FOR STUDENTS  (Super Admin scope)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Enroll any student in any course (admin-initiated, bypasses semester/level restrictions).
     */
    @GetMapping("/student/{studentId}/enrolled-courses")
    public ResponseEntity<?> saGetEnrolledCourseIds(@PathVariable Long studentId) {
        return ResponseEntity.ok(categoryService.getEnrolledCourseIdsForStudent(studentId));
    }

    @DeleteMapping("/unenroll-student/{studentId}/{categoryId}")
    public ResponseEntity<?> saUnenrollStudent(@PathVariable Long studentId, @PathVariable Long categoryId) {
        return ResponseEntity.ok(categoryService.unenrollStudentFromCourse(studentId, categoryId));
    }

    @PostMapping("/enroll-student")
    public ResponseEntity<?> enrollStudentInCourse(@RequestBody Map<String, Long> body) {
        Long studentId  = body.get("studentId");
        Long categoryId = body.get("categoryId");
        if (studentId == null || categoryId == null)
            return ResponseEntity.badRequest().body(Map.of("message", "studentId and categoryId are required."));
        try {
            Map<String, String> result = categoryService.enrollStudentInCourse(studentId, categoryId);
            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * Returns all courses for a given program (including global ones) — used by admin enroll picker.
     */
    @GetMapping("/courses-for-program/{programId}")
    public ResponseEntity<?> getCoursesForProgram(@PathVariable Long programId) {
        return ResponseEntity.ok(categoryService.getCategoriesForProgram(programId));
    }
    // ─────────────────────────────────────────────────────────────────────────
    // SYSTEM SETTINGS (Super Admin Only)
    // ─────────────────────────────────────────────────────────────────────────
    @Autowired
    private SystemSettingService systemSettingService;

    @GetMapping("/settings")
    public ResponseEntity<Map<String, String>> getAllSettings() {
        return ResponseEntity.ok(systemSettingService.getAllSettings());
    }

    @PutMapping("/settings")
    public ResponseEntity<Map<String, String>> updateSettings(@RequestBody Map<String, String> payload) {
        payload.forEach((key, value) -> systemSettingService.updateSetting(key, value));
        return ResponseEntity.ok(Map.of("message", "Settings updated successfully."));
    }
}

