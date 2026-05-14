package com.exam.service;

import com.exam.DTO.CategoryDTO;
import com.exam.DTO.CategoryRequest;
import com.exam.DTO.CategoryUpdateRequest;
import com.exam.model.User;
import com.exam.model.exam.Category;
import com.exam.model.exam.Quiz;
import com.exam.repository.*;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.Principal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class CategoryService  {
@Autowired
private CategoryRepository categoryRepository;

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




    public Category addCategory(Category category){
        return  this.categoryRepository.save(category);
    }







    // Service
    @Transactional
    public CategoryDTO adminUpdateCategory(Long categoryId, CategoryUpdateRequest request) throws Exception {
        Category category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> new Exception("Category not found"));
        category.setTitle(request.getTitle());
        category.setDescription(request.getDescription());
        category.setLevel(request.getLevel());
        category.setCourseCode(request.getCourseCode());
        Category savedCategory = categoryRepository.save(category);
        return convertToDTO(savedCategory);
    }



    // Helper method to convert Category entity to CategoryDTO
    private CategoryDTO convertToDTO(Category category) {
        CategoryDTO dto = new CategoryDTO();
        dto.setCid(category.getCid());
        dto.setLevel(String.valueOf(category.getLevel()));
        dto.setCourseCode(String.valueOf(category.getCourseCode()));
//        dto.setId(category.getId());
        dto.setTitle(category.getTitle());
        dto.setDescription(category.getDescription());
        // Map other fields...
        return dto;
    }


    public CategoryDTO updateCategory(CategoryUpdateRequest request) {
        Category category = categoryRepository.findById(request.getId())
                .orElseThrow(() -> new RuntimeException("Category not found with id: " + request.getId()));
        // Update only the fields that should be changed
        category.setTitle(request.getTitle());
        category.setDescription(request.getDescription());
        category.setLevel(request.getLevel());
        category.setCourseCode(request.getCourseCode());
        // User field is NOT touched, so it remains unchanged
        Category updated = categoryRepository.save(category);
        return new CategoryDTO(updated);
    }







    public Set<Category> getCategories(){
        return new LinkedHashSet<>(this.categoryRepository.findAll());
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
     * Get all categories assigned to a specific lecturer
     */
//    public List<Category> getCategoriesByLecturer(Long lecturerId) {
//        return categoryRepository.findByUserIdOrderByCourseCodeAsc(lecturerId);
//    }


}
