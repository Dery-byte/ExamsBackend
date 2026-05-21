package com.exam.controller;

import com.exam.DTO.CategoryDTO;
import com.exam.DTO.CategoryRequest;
import com.exam.DTO.CategoryUpdateRequest;
import com.exam.DTO.CategoryWithQuizzesDTO;
import com.exam.exception.ErrorMessage;
import com.exam.model.exam.Category;
import com.exam.model.exam.Quiz;
import com.exam.repository.CategoryRepository;
import com.exam.service.CategoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
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





























}
