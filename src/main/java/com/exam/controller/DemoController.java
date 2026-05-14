package com.exam.controller;

import com.exam.model.User;
import com.exam.repository.UserRepository;
import com.exam.service.AuthenticationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@CrossOrigin( origins = "*")
@RequestMapping("/api/v1/auth")
public class DemoController {

  @Autowired
  private  AuthenticationService authenticationService;
@Autowired
  private UserRepository userRepository;

  @GetMapping("/demo-controller")
  public ResponseEntity<String> sayHello() {
    return ResponseEntity.ok("Hello from secured endpoint");
  }

  @GetMapping("/all_users")
  public List<User> getAllUsers() {
    return  userRepository.findAll();
  }

//  @GetMapping("/all_user")
//  public Optional<User> getAllUserds(String username) {
//    return  userRepository.findByUsername(username);
//  }

@GetMapping("/{username}")
public Optional<User> fetch(@PathVariable("username") String username){
    return authenticationService.getUser(username);
}

}
