package com.exam.controller;

import com.exam.DTO.CategoryDTO;
import com.exam.DTO.CategoryRequest;
import com.exam.DTO.CategoryUpdateRequest;
import com.exam.DTO.CategoryWithQuizzesDTO;
import com.exam.exception.ErrorMessage;
import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.exam.Category;
import com.exam.model.exam.Quiz;
import com.exam.repository.CategoryRepository;
import com.exam.repository.UserRepository;
import com.exam.service.CategoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/api/v1/auth")
public class CategoryController {

    @Autowired
    private CategoryService categoryService;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private com.exam.service.ProgramService programService;

    @Autowired
    private com.exam.service.academic.AcademicRecordService academicRecordService;

    // ── CATEGORY CRUD ─────────────────────────────────────────────────────────

    @PostMapping("/add")
    public ResponseEntity<?> addCategory(@RequestBody Category category) {
        try {
            return ResponseEntity.ok(this.categoryService.addCategory(category));
        } catch (AccessDeniedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ErrorMessage(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ErrorMessage(e.getMessage()));
        }
    }

    @PostMapping("/lecturer/addCategory")
    public ResponseEntity<?> lecturerAddCategory(@RequestBody Category category) {
        try {
            return ResponseEntity.ok(this.categoryService.lecturerAddCategory(category));
        } catch (AccessDeniedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ErrorMessage(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ErrorMessage(e.getMessage()));
        }
    }

    @GetMapping("/getCategories")
    public ResponseEntity<?> getCategories(Principal principal) {
        String username = principal != null ? principal.getName() : null;
        return ResponseEntity.ok(this.categoryService.getCategories(username));
    }

    @GetMapping("/category/{categoryId}")
    public ResponseEntity<CategoryDTO> getCategory(@PathVariable("categoryId") Long categoryId) {
        Category cat = this.categoryService.getCategory(categoryId);
        if (cat == null) return ResponseEntity.notFound().build();
        CategoryDTO dto = new CategoryDTO(cat);
        if (cat.getPrograms() != null) {
            dto.setProgramIds(cat.getPrograms().stream().map(p -> p.getId()).collect(Collectors.toList()));
            dto.setProgramNames(cat.getPrograms().stream().map(p -> p.getName()).collect(Collectors.toList()));
        } else {
            dto.setProgramIds(new ArrayList<>());
            dto.setProgramNames(new ArrayList<>());
        }
        return ResponseEntity.ok(dto);
    }

    @PutMapping("/category/admin/updateCategory/{id}")
    public ResponseEntity<?> adminUpdateCategory(@PathVariable Long id, @RequestBody CategoryUpdateRequest request) {
        try {
            return ResponseEntity.ok(this.categoryService.adminUpdateCategory(id, request));
        } catch (AccessDeniedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ErrorMessage(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ErrorMessage(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorMessage(e.getMessage()));
        }
    }

    @PutMapping("/category/updateCategory")
    public ResponseEntity<?> updateCategory(@RequestBody CategoryUpdateRequest request) {
        try {
            return ResponseEntity.ok(categoryService.updateCategory(request));
        } catch (AccessDeniedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ErrorMessage(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ErrorMessage(e.getMessage()));
        }
    }

    @DeleteMapping("/category/{categoryId}")
    public void deleteCategory(@PathVariable("categoryId") Long categoryId) {
        this.categoryService.deleteCategory(categoryId);
    }

    // ── MISC CATEGORY LOOKUPS ─────────────────────────────────────────────────

    private List<Quiz> itemList = new ArrayList<>();

    @GetMapping("/byCourse/{cid}")
    public List<Quiz> getItemsByCourse(@PathVariable Long cid) {
        return itemList.stream()
                .filter(item -> item.getCategory().getCid() != null && item.getCategory().getCid().equals(cid))
                .collect(Collectors.toList());
    }

    @GetMapping("/categoriesForUser")
    public List<Category> getCategoriesForLoggedInUser(Principal principal) {
        return categoryService.getCategoriesForLoggedInUser(principal);
    }

    @GetMapping("/category/lecturer/{lecturerId}/with-quizzes")
    public ResponseEntity<List<CategoryWithQuizzesDTO>> getCategoriesWithQuizzesForLecturer(
            @PathVariable("lecturerId") Long lecturerId) {
        return ResponseEntity.ok(categoryService.getCategoriesWithQuizzesByLecturerId(lecturerId));
    }

    @GetMapping("/category/my-courses-with-quizzes")
    public ResponseEntity<List<CategoryWithQuizzesDTO>> getMyCoursesWithQuizzes(Principal principal) {
        return ResponseEntity.ok(categoryService.getCategoriesWithQuizzesForLoggedInUser(principal));
    }

    @PostMapping("/user/addCategory")
    public ResponseEntity<?> addCategoryForLoggedInUser(@RequestBody CategoryRequest category, Principal principal) {
        try {
            return ResponseEntity.ok(categoryService.addCategoryForUser(category, principal));
        } catch (AccessDeniedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ErrorMessage(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ErrorMessage(e.getMessage()));
        }
    }

    @PutMapping("/courses/{categoryId}/assign/{lecturerId}")
    public ResponseEntity<?> assignCourseToLecturer(
            @PathVariable("categoryId") Long categoryId,
            @PathVariable("lecturerId") Long lecturerId) {
        try {
            return ResponseEntity.ok(categoryService.assignCourseToLecturer(categoryId, lecturerId));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorMessage(e.getMessage()));
        }
    }

    @PutMapping("/{categoryId}/unassign")
    public ResponseEntity<?> unassignCourseFromLecturer(@PathVariable("categoryId") Long categoryId) {
        try {
            return ResponseEntity.ok(categoryService.unassignCourseFromLecturer(categoryId));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorMessage(e.getMessage()));
        }
    }

    @GetMapping("/categories/for-student")
    public ResponseEntity<List<Category>> getCoursesForStudent(Principal principal) {
        return ResponseEntity.ok(categoryService.getCoursesForStudent(principal));
    }

    // ── HOD (ADMIN) — STUDENT PROMOTION ──────────────────────────────────────

    @PutMapping("/admin/student/{id}/promote")
    public ResponseEntity<?> hodPromoteStudent(@PathVariable Long id, @RequestBody Map<String, Integer> body) {
        User student = userRepository.findById(id).orElse(null);
        if (student == null)
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Student not found."));
        if (student.getRole() != Role.NORMAL)
            return ResponseEntity.badRequest().body(Map.of("message", "User is not a student."));
        Integer targetLevel = body.get("targetLevel");
        if (targetLevel == null)
            return ResponseEntity.badRequest().body(Map.of("message", "targetLevel is required."));
        Integer current = student.getCurrentLevel() != null ? student.getCurrentLevel() : 0;
        if (targetLevel <= current)
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "HOD can only promote students forward. Target level must be higher than current level."));
        Map<String, Object> elig = academicRecordService.eligibility(student);
        if (!Boolean.TRUE.equals(elig.get("eligible")))
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "message", "This student doesn't meet the promotion rules. Only the Super Admin can override.",
                    "reasons", elig.get("reasons"), "canOverride", false));
        student.setCurrentLevel(targetLevel);
        student.setCurrentSemester(1);
        userRepository.save(student);
        return ResponseEntity.ok(Map.of("message", "Student promoted to Level " + targetLevel,
                "currentLevel", targetLevel, "currentSemester", 1));
    }

    @PutMapping("/admin/students/promote-all/{programId}/{level}")
    public ResponseEntity<?> hodPromoteAllAtLevel(
            @PathVariable Long programId, @PathVariable Integer level,
            @RequestBody Map<String, Integer> body) {
        Integer targetLevel = body.get("targetLevel");
        if (targetLevel == null)
            return ResponseEntity.badRequest().body(Map.of("message", "targetLevel is required."));
        if (targetLevel <= level)
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "HOD can only promote forward. targetLevel must exceed current level."));
        List<User> students = userRepository.findByRole(Role.NORMAL).stream()
                .filter(s -> s.getProgram() != null && s.getProgram().getId().equals(programId))
                .filter(s -> level.equals(s.getCurrentLevel()))
                .collect(Collectors.toList());
        return ResponseEntity.ok(SuperAdminController.applyBulkPromotion(students, level, targetLevel, academicRecordService, userRepository));
    }

    @PutMapping("/admin/students/promote-semester-all/{programId}/{level}")
    public ResponseEntity<?> hodPromoteSemesterAllAtLevel(
            @PathVariable Long programId, @PathVariable Integer level) {
        int maxSemesters = 2;
        try {
            com.exam.model.exam.Program program = programService.getEntityById(programId);
            maxSemesters = program.getSemestersForLevel(level);
        } catch (Exception ignored) { }
        List<User> students = userRepository.findByRole(Role.NORMAL).stream()
                .filter(s -> s.getProgram() != null && s.getProgram().getId().equals(programId))
                .filter(s -> level.equals(s.getCurrentLevel()))
                .collect(Collectors.toList());
        final int maxSem = maxSemesters;
        long alreadyAtMax = students.stream()
                .filter(s -> s.getCurrentSemester() != null && s.getCurrentSemester() >= maxSem).count();
        students.forEach(s -> {
            int cur = s.getCurrentSemester() != null ? s.getCurrentSemester() : 1;
            if (cur < maxSem) s.setCurrentSemester(cur + 1);
        });
        userRepository.saveAll(students);
        return ResponseEntity.ok(Map.of(
                "message", "Promoted " + (students.size() - alreadyAtMax) + " students to next semester. "
                        + alreadyAtMax + " already at max semester (" + maxSem + ").",
                "count", students.size(), "promoted", students.size() - alreadyAtMax, "skipped", alreadyAtMax));
    }

    @PutMapping("/admin/students/demote-semester-all/{programId}/{level}")
    public ResponseEntity<?> hodDemoteSemesterAllAtLevel(
            @PathVariable Long programId, @PathVariable Integer level) {
        List<User> students = userRepository.findByRole(Role.NORMAL).stream()
                .filter(s -> s.getProgram() != null && s.getProgram().getId().equals(programId))
                .filter(s -> level.equals(s.getCurrentLevel()))
                .collect(Collectors.toList());
        long alreadyAtMin = students.stream()
                .filter(s -> s.getCurrentSemester() == null || s.getCurrentSemester() <= 1).count();
        students.forEach(s -> {
            int cur = s.getCurrentSemester() != null ? s.getCurrentSemester() : 1;
            if (cur > 1) s.setCurrentSemester(cur - 1);
        });
        userRepository.saveAll(students);
        return ResponseEntity.ok(Map.of(
                "message", "Demoted " + (students.size() - alreadyAtMin) + " students to previous semester. "
                        + alreadyAtMin + " already at semester 1.",
                "count", students.size(), "demoted", students.size() - alreadyAtMin, "skipped", alreadyAtMin));
    }

    // ── HOD (ADMIN) — COURSE ENROLLMENT ──────────────────────────────────────

    @GetMapping("/admin/student/{studentId}/enrolled-courses")
    public ResponseEntity<?> adminGetEnrolledCourseIds(@PathVariable Long studentId) {
        return ResponseEntity.ok(categoryService.getEnrolledCourseIdsForStudent(studentId));
    }

    @DeleteMapping("/admin/unenroll-student/{studentId}/{categoryId}")
    public ResponseEntity<?> adminUnenrollStudent(@PathVariable Long studentId, @PathVariable Long categoryId) {
        return ResponseEntity.ok(categoryService.unenrollStudentFromCourse(studentId, categoryId));
    }

    @PostMapping("/admin/enroll-student")
    public ResponseEntity<?> adminEnrollStudent(@RequestBody Map<String, Long> body, Principal principal) {
        Long studentId  = body.get("studentId");
        Long categoryId = body.get("categoryId");
        if (studentId == null || categoryId == null)
            return ResponseEntity.badRequest().body(Map.of("message", "studentId and categoryId are required."));
        // HODs may only enrol students of their own department
        User caller = principal == null ? null : userRepository.findByUsername(principal.getName()).orElse(null);
        User student = userRepository.findById(studentId).orElse(null);
        if (caller != null && caller.getRole() == Role.ADMIN && student != null
                && !com.exam.service.comms.NotificationService.inDepartment(student, caller.getDepartment()))
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "That student is not in your department."));
        try {
            return ResponseEntity.ok(categoryService.enrollStudentInCourse(studentId, categoryId));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", e.getMessage()));
        }
    }

    @GetMapping("/admin/courses-for-program/{programId}")
    public ResponseEntity<?> adminGetCoursesForProgram(@PathVariable Long programId) {
        return ResponseEntity.ok(categoryService.getCategoriesForProgram(programId));
    }

    @GetMapping("/admin/courses-for-sheet")
    public ResponseEntity<?> adminGetCoursesForSheet(
            @RequestParam Long programId, @RequestParam String level, @RequestParam Integer semester) {
        List<Category> courses = categoryRepository.findAll().stream()
            .filter(c -> c.getPrograms() != null && c.getPrograms().stream().anyMatch(p -> p.getId().equals(programId)))
            .filter(c -> c.getLevel() != null && (c.getLevel().equals(level) || c.getLevel().equals("Level " + level)))
            .filter(c -> semester.equals(c.getSemester()))
            .collect(Collectors.toList());
        return ResponseEntity.ok(courses);
    }

    @GetMapping("/admin/students")
    public ResponseEntity<List<Map<String, Object>>> adminGetAllStudents(Principal principal) {
        List<User> students = userRepository.findByRole(com.exam.model.Role.NORMAL);
        if (principal != null) {
            User currentUser = userRepository.findByUsername(principal.getName()).orElse(null);
            if (currentUser != null && currentUser.getRole() == com.exam.model.Role.ADMIN && currentUser.getDepartment() != null) {
                Long deptId = currentUser.getDepartment().getId();
                students = students.stream().filter(u -> {
                    if (u.getDepartment() != null && u.getDepartment().getId().equals(deptId)) return true;
                    if (u.getProgram() != null && u.getProgram().getDepartment() != null
                            && u.getProgram().getDepartment().getId().equals(deptId)) return true;
                    return false;
                }).collect(Collectors.toList());
            }
        }
        List<Map<String, Object>> result = students.stream().map(s -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id",              s.getId());
            m.put("firstname",       s.getFirstname()  != null ? s.getFirstname()  : "");
            m.put("lastname",        s.getLastname()   != null ? s.getLastname()   : "");
            m.put("email",           s.getEmail()      != null ? s.getEmail()      : "");
            m.put("username",        s.getUsername()   != null ? s.getUsername()   : "");
            m.put("phone",           s.getPhone()      != null ? s.getPhone()      : "");
            m.put("program",         s.getProgram()    != null ? s.getProgram().getName() : "");
            m.put("programId",       s.getProgram()    != null ? s.getProgram().getId()   : null);
            m.put("currentLevel",    s.getCurrentLevel()    != null ? s.getCurrentLevel()    : 0);
            m.put("currentSemester", s.getCurrentSemester() != null ? s.getCurrentSemester() : 0);
            m.put("enabled",         s.isEnabled());
            return m;
        }).collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }
}
