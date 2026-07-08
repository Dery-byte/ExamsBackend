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
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.*;
import java.util.stream.Collectors;

@RestController
//@RequestMapping("/category")
@CrossOrigin(origins = "*")
@RequestMapping("/api/v1/auth")
public class CategoryController {
    //add category
    @Autowired
    private CategoryService categoryService;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private UserRepository userRepository;


    @PostMapping("/add")
    public ResponseEntity<Category> addCategory(@RequestBody Category category){
        Category category1 = this.categoryService.addCategory(category);
        return ResponseEntity.ok(category1);
    }

    @PostMapping("/lecturer/addCategory")
    public ResponseEntity<Category> lecturerAddCategory(@RequestBody Category category){
        Category category1 = this.categoryService.lecturerAddCategory(category);
        return ResponseEntity.ok(category1);
    }



    @GetMapping("/getCategories")
    public ResponseEntity<?> getCategories(){
        return ResponseEntity.ok(this.categoryService.getCategories());
    }

    //getCategory
    @GetMapping("/category/{categoryId}")
    public Category getCategory(@PathVariable("categoryId") Long categoryId){
        return this.categoryService.getCategory(categoryId);
    }









    //update Categories
    @PutMapping("/category/admin/updateCategory/{id}")
    public ResponseEntity<?> adminUpdateCategory(
            @PathVariable Long id,
            @RequestBody CategoryUpdateRequest request) {
        try {
            CategoryDTO updatedCategory = this.categoryService.adminUpdateCategory(id, request);
            return ResponseEntity.ok(updatedCategory);
        } catch (Exception e) {
            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body(new ErrorMessage(e.getMessage()));
        }
    }

//    @PutMapping("/category/updateCategory")
//    public CategoryDTO updateCategory(@RequestBody Category category){
//        return categoryService.updateCategory(category);
//    }


    @PutMapping("/category/updateCategory")
    public ResponseEntity<CategoryDTO> updateCategory(@RequestBody CategoryUpdateRequest request) {
        CategoryDTO updated = categoryService.updateCategory(request);
        return ResponseEntity.ok(updated);
    }









    //delete category
    @DeleteMapping("/category/{categoryId}")
    public void deleteCategory(@PathVariable("categoryId") Long categoryId){
        this.categoryService.deleteCategory(categoryId);
    }


//Get element by Course Name
private List<Quiz> itemList = new ArrayList<>();
    @GetMapping("/byCourse/{cid}")
    public List<Quiz> getItemsByCourse(@PathVariable Long cid) {
        List<Quiz> itemsByCourse = itemList.stream()
                .filter(item -> item.getCategory().getCid()!= null && item.getCategory().getCid().equals(cid))
                .collect(Collectors.toList());
        System.out.println(itemsByCourse);
        return itemsByCourse;
    }





    // ✅ GET CATEGORIES BY USER

    @GetMapping("/categoriesForUser")
    public List<Category> getCategoriesForLoggedInUser(Principal principal) {
        return categoryService.getCategoriesForLoggedInUser(principal);
    }

    // ✅ GET CATEGORIES AND QUIZZES BY LECTURER ID
    @GetMapping("/category/lecturer/{lecturerId}/with-quizzes")
    public ResponseEntity<List<CategoryWithQuizzesDTO>> getCategoriesWithQuizzesForLecturer(@PathVariable("lecturerId") Long lecturerId) {
        return ResponseEntity.ok(categoryService.getCategoriesWithQuizzesByLecturerId(lecturerId));
    }

    // ✅ GET CATEGORIES AND QUIZZES FOR THE LOGGED-IN LECTURER (via Principal)
    @GetMapping("/category/my-courses-with-quizzes")
    public ResponseEntity<List<CategoryWithQuizzesDTO>> getMyCoursesWithQuizzes(Principal principal) {
        return ResponseEntity.ok(categoryService.getCategoriesWithQuizzesForLoggedInUser(principal));
    }








    // ✅ ASSIGN CATEGORY TO USER
    @PostMapping("/user/addCategory")
    public Category addCategoryForLoggedInUser(
            @RequestBody CategoryRequest category,
            Principal principal) {
        return categoryService.addCategoryForUser(category, principal);
    }






    @PutMapping("/courses/{categoryId}/assign/{lecturerId}")
    public ResponseEntity<?> assignCourseToLecturer(
            @PathVariable("categoryId") Long categoryId,
            @PathVariable("lecturerId") Long lecturerId) {
        try {
            Category updatedCategory = categoryService.assignCourseToLecturer(categoryId, lecturerId);
            return ResponseEntity.ok(updatedCategory);
        } catch (Exception e) {
            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body(new ErrorMessage(e.getMessage()));
        }

    }

    @PutMapping("/{categoryId}/unassign")
    public ResponseEntity<?> unassignCourseFromLecturer(
            @PathVariable("categoryId") Long categoryId) {
        try {
            Category updatedCategory = categoryService.unassignCourseFromLecturer(categoryId);
            return ResponseEntity.ok(updatedCategory);
        } catch (Exception e) {
            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body(new ErrorMessage(e.getMessage()));
        }
    }


























    /**
     * GET /api/v1/auth/categories/for-student
     * Returns courses visible to the logged-in student based on their program, level and semester.
     */
    @GetMapping("/categories/for-student")
    public ResponseEntity<List<Category>> getCoursesForStudent(java.security.Principal principal) {
        List<Category> courses = categoryService.getCoursesForStudent(principal);
        return ResponseEntity.ok(courses);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HOD (ADMIN) — STUDENT PROMOTION  (forward-only)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * HOD promotes a single student forward to the next level only.
     * Requires: targetLevel > currentLevel.
     */
    @PutMapping("/admin/student/{id}/promote")
    public ResponseEntity<?> hodPromoteStudent(
            @PathVariable Long id,
            @RequestBody Map<String, Integer> body) {
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

        student.setCurrentLevel(targetLevel);
        student.setCurrentSemester(1);
        userRepository.save(student);
        return ResponseEntity.ok(Map.of("message", "Student promoted to Level " + targetLevel,
                "currentLevel", targetLevel, "currentSemester", 1));
    }

    /**
     * HOD promotes ALL students at a given level within a specific program forward (forward-only).
     */
    @PutMapping("/admin/students/promote-all/{programId}/{level}")
    public ResponseEntity<?> hodPromoteAllAtLevel(
            @PathVariable Long programId,
            @PathVariable Integer level,
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

        students.forEach(s -> { s.setCurrentLevel(targetLevel); s.setCurrentSemester(1); });
        userRepository.saveAll(students);
        return ResponseEntity.ok(Map.of("message", "Promoted " + students.size() + " students in program from Level "
                + level + " to Level " + targetLevel, "count", students.size()));
    }

    /**
     * HOD promotes ALL students at a given level within a specific program to the next semester.
     */
    @PutMapping("/admin/students/promote-semester-all/{programId}/{level}")
    public ResponseEntity<?> hodPromoteSemesterAllAtLevel(
            @PathVariable Long programId,
            @PathVariable Integer level) {
        
        List<User> students = userRepository.findByRole(Role.NORMAL).stream()
                .filter(s -> s.getProgram() != null && s.getProgram().getId().equals(programId))
                .filter(s -> level.equals(s.getCurrentLevel()))
                .collect(Collectors.toList());

        students.forEach(s -> {
            int currentSem = s.getCurrentSemester() != null ? s.getCurrentSemester() : 1;
            s.setCurrentSemester(currentSem + 1);
        });
        userRepository.saveAll(students);
        return ResponseEntity.ok(Map.of("message", "Promoted " + students.size() + " students in program to the next semester.", "count", students.size()));
    }

    /**
     * HOD/Admin enrolls a student in any course (bypasses semester/level restriction).
     */
    @GetMapping("/admin/student/{studentId}/enrolled-courses")
    public ResponseEntity<?> adminGetEnrolledCourseIds(@PathVariable Long studentId) {
        return ResponseEntity.ok(categoryService.getEnrolledCourseIdsForStudent(studentId));
    }

    @DeleteMapping("/admin/unenroll-student/{studentId}/{categoryId}")
    public ResponseEntity<?> adminUnenrollStudent(@PathVariable Long studentId, @PathVariable Long categoryId) {
        return ResponseEntity.ok(categoryService.unenrollStudentFromCourse(studentId, categoryId));
    }

    @PostMapping("/admin/enroll-student")
    public ResponseEntity<?> adminEnrollStudent(@RequestBody Map<String, Long> body) {
        Long studentId  = body.get("studentId");
        Long categoryId = body.get("categoryId");
        if (studentId == null || categoryId == null)
            return ResponseEntity.badRequest().body(Map.of("message", "studentId and categoryId are required."));
        try {
            return ResponseEntity.ok(categoryService.enrollStudentInCourse(studentId, categoryId));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * Returns all courses for a given program + global courses — used by admin enroll picker.
     */
    @GetMapping("/admin/courses-for-program/{programId}")
    public ResponseEntity<?> adminGetCoursesForProgram(@PathVariable Long programId) {
        return ResponseEntity.ok(categoryService.getCategoriesForProgram(programId));
    }

    /**
     * Rich student list for admin-initiated actions (enroll, promote).
     * Returns the same fields as the Super Admin /students endpoint including programId,
     * currentLevel, currentSemester — required by the EnrollStudent and Students pages.
     */
    @GetMapping("/admin/students")
    public ResponseEntity<List<Map<String, Object>>> adminGetAllStudents() {
        List<User> students = userRepository.findByRole(com.exam.model.Role.NORMAL);
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
            return m;
        }).collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

}


