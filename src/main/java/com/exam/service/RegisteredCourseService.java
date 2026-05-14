package com.exam.service;


import com.exam.model.User;
import com.exam.model.exam.Category;
import com.exam.model.exam.Registered_courses;
import com.exam.repository.Registered_coursesRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Set;

@Service
public class RegisteredCourseService {

    @Autowired
    private Registered_coursesRepository registeredRepository;
    public Registered_courses registerCourse(Registered_courses registered_courses){
        return registeredRepository.save(registered_courses);
    }


    public void deleteCourseRegisteredById(Long Rid){
//        Registered_courses registered_courses = new Registered_courses();
//        registered_courses.setRid(Rid);
//        registered_courses.setRid(Rid);
        this.registeredRepository.deleteById(Rid);
    }


    public Set<Registered_courses> getRegCourses(){
        return new LinkedHashSet<>(this.registeredRepository.findAll());
    }


    public Registered_courses getRegCourseById(Long RegCourseId){
        return this.registeredRepository.findById(RegCourseId).get();
    }



}
