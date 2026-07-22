package com.exam.service;

import com.exam.DTO.CategoryDTO;
import com.exam.DTO.CategoryRequest;
import com.exam.DTO.CategoryUpdateRequest;
import com.exam.DTO.CategoryWithQuizzesDTO;
import com.exam.DTO.QuizDTO;
import com.exam.model.User;
import com.exam.model.exam.Category;
import com.exam.model.exam.Program;
import com.exam.model.exam.Quiz;
import com.exam.model.exam.Registered_courses;
import com.exam.repository.*;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import org.springframework.transaction.annotation.Transactional;

import java.security.Principal;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class CategoryService  {
@Autowired
private CategoryRepository categoryRepository;

@Autowired
private SystemSettingService systemSettingService;

    /** One-time data migration: ensure all stored levels are plain numbers (e.g. "100" not "Level 100"). */
    @PostConstruct
    @Transactional
    public void normalizeLegacyLevels() {
        int updated = categoryRepository.normalizeLevelPrefix();
        if (updated > 0) {
            System.out.println("[CategoryService] Normalised " + updated + " legacy category levels (stripped 'Level ' prefix).");
        }
    }

@Autowired
    Registered_coursesRepository registeredCoursesRepository;
@Autowired
    NumberOfTheoryToAnswerRepository numberOfTheoryToAnswerRepository;
@Autowired
QuizRepository quizRepository;
@Autowired
    TheoryQuestionsRepository theoryQuestionsRepository;

@Autowired
ReportRepository reportRepository;

@Autowired
QuestionsRepository questionsRepository;

@Autowired
UserRepository userRepository;

@Autowired
ProgramRepository programRepository;



    /**
     * Resolves programId → Program entity, normalises semester (String → Integer)
     * and level format ("Level 100" → "100"), then saves.
     */
    private void resolveAndNormalize(Category category) {
        // 1. Resolve programIds → Programs
        if (category.getProgramIds() != null && !category.getProgramIds().isEmpty()) {
            java.util.List<Program> resolvedPrograms = programRepository.findAllById(category.getProgramIds());
            category.setPrograms(new java.util.HashSet<>(resolvedPrograms));
        }
        // 2. Normalise level: strip leading "Level " so we always store "100", "200" etc.
        if (category.getLevel() != null) {
            String lvl = category.getLevel().trim();
            if (lvl.toLowerCase().startsWith("level ")) {
                lvl = lvl.substring(6).trim();
            }
            category.setLevel(lvl);
        }
    }

    public Category addCategory(Category category){
        resolveAndNormalize(category);
        return this.categoryRepository.save(category);
    }




    public Category lecturerAddCategory(Category category) {
        resolveAndNormalize(category);
        String username = SecurityContextHolder.getContext()
                .getAuthentication()
                .getName();
        User currentUser = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));
        category.setUser(currentUser);
        return this.categoryRepository.save(category);
    }


    // Service
    @Transactional
    public com.exam.DTO.CategoryDTO adminUpdateCategory(Long categoryId, com.exam.DTO.CategoryUpdateRequest request) throws Exception {
        Category category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> new Exception("Category not found"));
        category.setTitle(request.getTitle());
        category.setDescription(request.getDescription());
        category.setLevel(request.getLevel());
        category.setCourseCode(request.getCourseCode());
        if (request.getProgramIds() != null) {
            java.util.List<Program> resolvedPrograms = programRepository.findAllById(request.getProgramIds());
            category.setPrograms(new java.util.HashSet<>(resolvedPrograms));
        }
        Category savedCategory = categoryRepository.save(category);
        return convertToDTO(savedCategory);
    }



    // Helper method to convert Category entity to CategoryDTO
    private com.exam.DTO.CategoryDTO convertToDTO(Category category) {
        com.exam.DTO.CategoryDTO dto = new com.exam.DTO.CategoryDTO();
        dto.setCid(category.getCid());
        dto.setLevel(String.valueOf(category.getLevel()));
        dto.setCourseCode(String.valueOf(category.getCourseCode()));
//        dto.setId(category.getId());
        dto.setTitle(category.getTitle());
        dto.setDescription(category.getDescription());
        if (category.getPrograms() != null) {
            dto.setProgramIds(category.getPrograms().stream().map(Program::getId).collect(java.util.stream.Collectors.toList()));
            dto.setProgramNames(category.getPrograms().stream().map(Program::getName).collect(java.util.stream.Collectors.toList()));
        } else {
            dto.setProgramIds(new java.util.ArrayList<>());
            dto.setProgramNames(new java.util.ArrayList<>());
        }
        // Map other fields...
        return dto;
    }


    public com.exam.DTO.CategoryDTO updateCategory(com.exam.DTO.CategoryUpdateRequest request) {
        Category category = categoryRepository.findById(request.getId())
                .orElseThrow(() -> new RuntimeException("Category not found with id: " + request.getId()));
        // Update only the fields that should be changed
        category.setTitle(request.getTitle());
        category.setDescription(request.getDescription());
        category.setLevel(request.getLevel());
        category.setCourseCode(request.getCourseCode());
        if (request.getProgramIds() != null) {
            java.util.List<Program> resolvedPrograms = programRepository.findAllById(request.getProgramIds());
            category.setPrograms(new java.util.HashSet<>(resolvedPrograms));
        }
        // User field is NOT touched, so it remains unchanged
        Category updated = categoryRepository.save(category);
        return new com.exam.DTO.CategoryDTO(updated);
    }

    public java.util.List<com.exam.DTO.CategoryDTO> getCategories(String username){
        java.util.List<Category> allCategories = categoryRepository.findAll();

        if (username != null) {
            User currentUser = userRepository.findByUsername(username).orElse(null);
            if (currentUser != null && currentUser.getRole() == com.exam.model.Role.ADMIN && currentUser.getDepartment() != null) {
                Long deptId = currentUser.getDepartment().getId();
                allCategories = allCategories.stream().filter(c -> {
                    if (c.getPrograms() == null || c.getPrograms().isEmpty()) {
                        return false;
                    }
                    return c.getPrograms().stream().anyMatch(p -> p.getDepartment() != null && p.getDepartment().getId().equals(deptId));
                }).collect(Collectors.toList());
            }
        }

        return allCategories.stream()
                .map(c -> {
                    com.exam.DTO.CategoryDTO dto = new com.exam.DTO.CategoryDTO(c);
                    if (c.getPrograms() != null) {
                        dto.setProgramIds(c.getPrograms().stream().map(p -> p.getId()).collect(Collectors.toList()));
                        dto.setProgramNames(c.getPrograms().stream().map(p -> p.getName()).collect(Collectors.toList()));
                    } else {
                        dto.setProgramIds(new java.util.ArrayList<>());
                        dto.setProgramNames(new java.util.ArrayList<>());
                    }
                    return dto;
                })
                .collect(Collectors.toList());
    }


    public Category getCategory(Long categoryId){
        return this.categoryRepository.findById(categoryId).get();
    }










    public void deleteCategory(Long categoryId){

        List<Quiz> quizzes = quizRepository.findByCategory_cid(categoryId);

        for (Quiz quiz : quizzes) {
            reportRepository.deleteByQuizId(quiz.getqId());
            theoryQuestionsRepository.deleteByQuizId(quiz.getqId());
            numberOfTheoryToAnswerRepository.deleteByQuiz_Id(quiz.getqId());
            questionsRepository.deleteByQuiz_Id(quiz.getqId());
        }

        this.quizRepository.deleteByCategory_cid(categoryId);
//        this.numberOfTheoryToAnswerRepository.deleteByQuiz_Id(categoryId);
        this.registeredCoursesRepository.deleteByCategory_cid(categoryId);
        this.categoryRepository.deleteById(categoryId);
    }









    // ✅ GET CATEGORIES BY USER
    @Transactional(readOnly = true)
    public List<Category> getCategoriesForLoggedInUser(Principal principal) {
        String username = principal.getName();
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new EntityNotFoundException("User not found"));
        return categoryRepository.findByUser_Id(user.getId());
    }

    // ✅ ASSIGN CATEGORY TO USER
    @Transactional
    public Category addCategoryForUser(CategoryRequest request, Principal principal) {
        String username = principal.getName();
        User lecturer = userRepository.findByUsername(username)
                .orElseThrow(() -> new EntityNotFoundException("User not found"));

        Category category = new Category();
        category.setTitle(request.getTitle());
        category.setCourseCode(request.getCourseCode());
        category.setDescription(request.getDescription());
        category.setLevel(request.getLevel());
        category.setUser(lecturer); // assign lecturer as owner
        return categoryRepository.save(category);
    }



    @Transactional
    public Category assignCourseToLecturer(Long categoryId, Long userId) throws Exception {
        // Find the category
        Category category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> new Exception("Category with ID " + categoryId + " not found"));

        // Find the user (lecturer)
        User lecturer = userRepository.findById(userId)
                .orElseThrow(() -> new Exception("Lecturer with ID " + userId + " not found"));

        // Verify that the user is actually a lecturer (case-insensitive and trim whitespace)
        String role = String.valueOf(lecturer.getRole());

        // Handle both "LECTURER" and "ROLE_LECTURER"
        if (!role.equals("LECTURER") && !role.equals("ROLE_LECTURER")) {
            throw new Exception("User with ID " + userId + " has role '" + lecturer.getRole() + "' but must be a LECTURER");
        }

        // Assign the lecturer to the category
        category.setUser(lecturer);

        // Save and return the updated category
        return categoryRepository.save(category);
    }

    @Transactional
    public Category unassignCourseFromLecturer(Long categoryId) throws Exception {
        Category category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> new Exception("Category with ID " + categoryId + " not found"));

        category.setUser(null);
        return categoryRepository.save(category);
    }






    /**
     * Get all categories and their associated quizzes assigned to a specific lecturer
     */
    @Transactional(readOnly = true)
    public List<CategoryWithQuizzesDTO> getCategoriesWithQuizzesByLecturerId(Long lecturerId) {
        List<Category> categories = categoryRepository.findByUser_Id(lecturerId);
        return categories.stream().map(c -> {
            CategoryWithQuizzesDTO dto = new CategoryWithQuizzesDTO();
            dto.setCid(c.getCid());
            dto.setTitle(c.getTitle());
            dto.setCourseCode(c.getCourseCode());
            dto.setDescription(c.getDescription());
            dto.setLevel(c.getLevel());
            if (c.getPrograms() != null) {
                dto.setProgramIds(c.getPrograms().stream().map(p -> p.getId()).collect(Collectors.toList()));
                dto.setProgramNames(c.getPrograms().stream().map(p -> p.getName()).collect(Collectors.toList()));
            } else {
                dto.setProgramIds(new java.util.ArrayList<>());
                dto.setProgramNames(new java.util.ArrayList<>());
            }
            if (c.getQuizzes() != null) {
                List<QuizDTO> quizDtos = c.getQuizzes().stream().map(QuizDTO::new).collect(Collectors.toList());
                dto.setQuizzes(quizDtos);
            }
            return dto;
        }).collect(Collectors.toList());
    }

    /**
     * Get all categories and their associated quizzes for the currently logged-in user
     */
    @Transactional(readOnly = true)
    public List<CategoryWithQuizzesDTO> getCategoriesWithQuizzesForLoggedInUser(Principal principal) {
        List<Category> categories = getCategoriesForLoggedInUser(principal);
        return categories.stream().map(c -> {
            CategoryWithQuizzesDTO dto = new CategoryWithQuizzesDTO();
            dto.setCid(c.getCid());
            dto.setTitle(c.getTitle());
            dto.setCourseCode(c.getCourseCode());
            dto.setDescription(c.getDescription());
            dto.setLevel(c.getLevel());
            if (c.getPrograms() != null) {
                dto.setProgramIds(c.getPrograms().stream().map(p -> p.getId()).collect(Collectors.toList()));
                dto.setProgramNames(c.getPrograms().stream().map(p -> p.getName()).collect(Collectors.toList()));
            } else {
                dto.setProgramIds(new java.util.ArrayList<>());
                dto.setProgramNames(new java.util.ArrayList<>());
            }
            if (c.getQuizzes() != null) {
                List<QuizDTO> quizDtos = c.getQuizzes().stream().map(QuizDTO::new).collect(Collectors.toList());
                dto.setQuizzes(quizDtos);
            }
            return dto;
        }).collect(Collectors.toList());
    }


    /**
     * Returns courses visible to the currently logged-in student:
     * - Program-specific courses at their current level + semester
     * - PLUS global courses (program = null) at their level + semester
     * Level is normalised (strips "Level " prefix) for consistent comparison.
     * Falls back to broader queries if no results found.
     */
    public List<Category> getCoursesForStudent(Principal principal) {
        if (principal == null) {
            return categoryRepository.findAll();
        }
        String username = principal.getName();
        User student = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));

        Program program = student.getProgram();
        Integer level    = student.getCurrentLevel();
        Integer semester = student.getCurrentSemester();

        // No level assigned — return full catalogue
        if (level == null) {
            return categoryRepository.findAll();
        }

        String levelStr = String.valueOf(level);
        // Helper: normalise stored level string
        java.util.function.Function<String, String> norm = raw -> {
            if (raw == null) return "";
            String s = raw.trim();
            if (s.toLowerCase().startsWith("level ")) s = s.substring(6).trim();
            return s;
        };

        boolean allowCarryOver = systemSettingService.getBooleanSetting(SystemSettingService.ALLOW_CARRYOVER_REGISTRATION, false);

        // Helper: check if course is available (no future courses, and optionally no past courses)
        java.util.function.Predicate<Category> isAvailable = c -> {
            String courseLevelStr = norm.apply(c.getLevel());
            if (courseLevelStr.isEmpty()) return true;
            
            int cLevel;
            try {
                cLevel = Integer.parseInt(courseLevelStr);
            } catch (NumberFormatException e) {
                if (!courseLevelStr.equals(levelStr)) return false;
                cLevel = level;
            }

            if (cLevel > level) return false;
            
            if (!allowCarryOver) {
                if (cLevel < level) return false;
            }

            if (cLevel == level) {
                if (semester != null && c.getSemester() != null) {
                    if (c.getSemester() > semester) return false;
                    if (!allowCarryOver && c.getSemester() < semester) return false;
                }
            }
            return true;
        };

        // Collect program-specific courses — skip if student's program is disabled
        java.util.Set<Long> seen = new java.util.HashSet<>();
        java.util.List<Category> combined = new java.util.ArrayList<>();

        if (program != null && program.isEnabled()) {
            List<Category> programCourses = categoryRepository.findByProgramsContaining(program);
            programCourses.stream()
                    .filter(isAvailable)
                    .forEach(c -> { if (seen.add(c.getCid())) combined.add(c); });
        }

        // Always include global courses (programs is empty) at available level/semester
        categoryRepository.findAll().stream()
                .filter(c -> c.getPrograms() == null || c.getPrograms().isEmpty())
                .filter(isAvailable)
                .forEach(c -> { if (seen.add(c.getCid())) combined.add(c); });

        // If NO program is assigned to the student, they will see global courses only.
        if (program == null && combined.isEmpty()) {
            categoryRepository.findAll().stream()
                    .filter(isAvailable)
                    .forEach(c -> { if (seen.add(c.getCid())) combined.add(c); });
        }

        return combined;
    }

    /**
     * Returns all courses belonging to a given ENABLED program (for admin enroll picker).
     * Also includes global courses (programs is empty).
     * Returns empty if the program is disabled — no enrollments should occur in disabled programs.
     */
    public List<Category> getCategoriesForProgram(Long programId) {
        Program prog = programRepository.findById(programId).orElse(null);
        if (prog == null || !prog.isEnabled()) {
            return java.util.Collections.emptyList();
        }
        List<Category> all = categoryRepository.findAll();
        return all.stream()
                .filter(c -> c.getPrograms() == null || c.getPrograms().isEmpty() ||
                        c.getPrograms().stream().anyMatch(p -> p.getId() != null && p.getId().equals(programId)))
                .collect(Collectors.toList());
    }

    @Transactional
    public List<Long> getEnrolledCourseIdsForStudent(Long studentId) {
        return registeredCoursesRepository.findRegistrationsByUserId(studentId).stream()
                .map(reg -> reg.getCategory().getCid())
                .collect(java.util.stream.Collectors.toList());
    }

    @Transactional
    public Map<String, String> unenrollStudentFromCourse(Long studentId, Long categoryId) {
        registeredCoursesRepository.deleteByCategoryIdAndUserId(categoryId, studentId);
        return Map.of("message", "Student unenrolled successfully.");
    }

    public Map<String, String> enrollStudentInCourse(Long studentId, Long categoryId) {
        User student = userRepository.findById(studentId)
                .orElseThrow(() -> new RuntimeException("Student not found"));
        Category category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> new RuntimeException("Course not found"));
        if (registeredCoursesRepository.countByCategoryAndUser(category, student) > 0) {
            return Map.of("message", "Student is already enrolled in this course.");
        }
        Registered_courses reg = new Registered_courses();
        reg.setCategory(category);
        reg.setUser(student);
        reg.setRegDate(new java.util.Date());
        registeredCoursesRepository.save(reg);
        return Map.of("message", "Student enrolled in " + category.getTitle() + " successfully.");
    }

}


