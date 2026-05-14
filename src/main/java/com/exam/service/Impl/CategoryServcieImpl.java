//package com.exam.service.Impl;
//
//import com.exam.model.exam.Category;
//import com.exam.repository.CategoryRepository;
//import com.exam.service.CategoryService;
//import org.springframework.beans.factory.annotation.Autowired;
//
//import java.util.Set;
//
//public class CategoryServcieImpl implements CategoryService {
//
//
//    @Autowired
//    private CategoryRepository categoryRepository;
//    @Override
//    public Category addCategory(Category category) {
//        return this.categoryRepository.save(category);
//    }
//
//    @Override
//    public Category UpdateCategory(Category category) {
//        return null;
//    }
//
//    @Override
//    public Set<Category> getCategories() {
//        return null;
//    }
//
//    @Override
//    public Category getCategory(Long categoryId) {
//        return null;
//    }
//
//    @Override
//    public void deleteCategory(Long categoryId) {
//
//    }
//}
