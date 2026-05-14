package com.exam.controller;

import com.exam.model.User;
import com.exam.model.exam.Registered_courses;
import com.exam.service.RegisteredCourseService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;

import java.security.Principal;

@RestController
//@RequestMapping("/category")
@CrossOrigin(origins = "*")
@RequestMapping("/api/v1/auth")
public class RegisteredCoursesController {
@Autowired
private RegisteredCourseService registeredCourseService;
    @Autowired
    private final UserDetailsService userDetailsService;
    public RegisteredCoursesController(UserDetailsService userDetailsService) {
        this.userDetailsService = userDetailsService;
    }


    @PostMapping("/registerCourse")
    public ResponseEntity<Registered_courses> RegCourse(@RequestBody Registered_courses registered_courses, Principal principal){
        User user = (User) this.userDetailsService.loadUserByUsername(principal.getName());
        registered_courses.setUser(user);

        LocalDate localDate = LocalDate.now();
        Date date = Date.from(localDate.atStartOfDay(ZoneId.systemDefault()).toInstant());
        registered_courses.setRegDate(date);
//        registered_courses.setRegDate(LocalDate.now());


        Registered_courses registered_courses1 = this.registeredCourseService.registerCourse(registered_courses);
        registered_courses1.setUser(user);
        return ResponseEntity.ok(registered_courses1);
    }




    @GetMapping("/getRegCourses")
    public ResponseEntity<?> getRegCourses(){
        return ResponseEntity.ok(this.registeredCourseService.getRegCourses());
}


    @DeleteMapping("/regCourse/deleteById/{Rid}")
        public void deleteRegCourse(@PathVariable("Rid") Long Rid){
       this.registeredCourseService.deleteCourseRegisteredById(Rid);
        }
    }
