package com.exam.service;

import com.exam.DTO.*;
import com.exam.auth.AuthenticationRequest;
import com.exam.auth.AuthenticationResponse;
import com.exam.auth.RegisterRequest;
import com.exam.exception.ResetPasswordTokenAlreadyUsedException;
import com.exam.exception.ResetPasswordTokenExpiredException;
import com.exam.helper.UserFoundException;
import com.exam.helper.UserNotFoundException;
import com.exam.repository.TokenRepository;
import com.exam.repository.UserRepository;
import com.exam.service.Impl.EmailService;
import com.exam.service.Impl.EmailTemplateName;
import com.exam.token.Token;
import com.exam.token.TokenType;
import com.exam.model.Role;
import com.exam.model.User;
import jakarta.mail.MessagingException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.UnsupportedEncodingException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class AuthenticationService {
    private final UserRepository userRepository;
    private final TokenRepository tokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;
    private final EmailService emailServices;

    @Autowired
    private MNotifyV2SmsService mNotifyV2SmsService;





    @Value("${application.mailing.frontend.baseUrl}")
    private String frontendBaseUrl;

// RESGISTER AS A STUDENT
    public AuthenticationResponse register(RegisterRequest request) throws UserFoundException {
        var userExist = userRepository.findByUsername(request.getUsername());
        if (userExist.isPresent()) {
            System.out.println("User is already in the system");
            throw new UserFoundException();
        }
        else
        {
        var user = User.builder()
                .firstname(request.getFirstname())
                .lastname(request.getLastname())
                .email(request.getEmail())
                .phone(request.getPhone())
                .username(request.getUsername())
                .enabled(true)
                .password(passwordEncoder.encode(request.getPassword()))
                .role(Role.NORMAL)
                .build();
        var savedUser = userRepository.save(user);
        var jwtToken = jwtService.generateToken(user);
        saveUserToken(savedUser, jwtToken);
        return AuthenticationResponse.builder()
                .token(jwtToken)
                .build();
    }
    }


    // RESGISTER AS A LECTURER
    public AuthenticationResponse registerAslecturer(RegisterRequest request) throws UserFoundException {
        var userExist = userRepository.findByUsername(request.getUsername());
        if (userExist.isPresent()) {
            System.out.println("User is already in the system");
            throw new UserFoundException();
        }
        else
        {
            var user = User.builder()
                    .firstname(request.getFirstname())
                    .lastname(request.getLastname())
                    .email(request.getEmail())
                    .phone(request.getPhone())
                    .username(request.getUsername())
                    .enabled(true)
                    .password(passwordEncoder.encode(request.getPassword()))
                    .role(Role.LECTURER)
                    .build();
            var savedUser = userRepository.save(user);
            var jwtToken = jwtService.generateToken(user);
            saveUserToken(savedUser, jwtToken);
            return AuthenticationResponse.builder()
                    .token(jwtToken)
                    .build();
        }
    }


    // RESGISTER AS ADMIN
    public AuthenticationResponse registerAsAdmin(RegisterRequest request) throws UserFoundException {
        var userExist = userRepository.findByUsername(request.getUsername());
        if (userExist.isPresent()) {
            System.out.println("User is already in the system");
            throw new UserFoundException();
        }
        else
        {
            var user = User.builder()
                    .firstname(request.getFirstname())
                    .lastname(request.getLastname())
                    .email(request.getEmail())
                    .phone(request.getPhone())
                    .username(request.getUsername())
                    .enabled(true)
                    .password(passwordEncoder.encode(request.getPassword()))
                    .role(Role.ADMIN)
                    .build();
            var savedUser = userRepository.save(user);
            var jwtToken = jwtService.generateToken(user);
            saveUserToken(savedUser, jwtToken);
            return AuthenticationResponse.builder()
                    .token(jwtToken)
                    .build();
        }
    }



    public AuthenticationResponse authenticate(AuthenticationRequest request) throws UserNotFoundException {
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        request.getUsername(),
                        request.getPassword()
                )
        );
        var user = userRepository.findByUsername(request.getUsername()).orElseThrow();
            var jwtToken = jwtService.generateToken((UserDetails) user);
            revokeAllUserTokens((User) user);
            saveUserToken((User) user, jwtToken);
            return AuthenticationResponse.builder()
                    .token(jwtToken)
                    .build();
        }
    private void saveUserToken(User user, String jwtToken) {
        var token = Token.builder()
                .user(user)
                .token(jwtToken)
                .tokenType(TokenType.BEARER)
                .expired(false)
                .revoked(false)
                .build();
        tokenRepository.save(token);
    }













    private void revokeAllUserTokens(User user) {
        var validUserTokens = tokenRepository.findAllValidTokenByUser(Math.toIntExact(user.getId()));
//        var tokens = tokenRepository.findByToken(user.getTokens().toString());


        //deleting the expired tokens

        if (validUserTokens.isEmpty())
//            return;
        validUserTokens.forEach(token -> {
            token.setExpired(true);
            token.setRevoked(true);
        });
        tokenRepository.saveAll(validUserTokens);
    }

    public Optional<User> getUser(String email ){
        return this.userRepository.findByUsername(email);
    }

//    public Optional<User> getUserByUserName(String username){
//        return this.userRepository.findByUsername(username);
//    }

    public List<User> getUserByUserName(User user){
        return Collections.singletonList(this.userRepository.save(user));
    }


    public List<User> getAllUsers() {
        return  userRepository.findAll();
    }

    public User getUserById(Integer user_id){
        return (User) userRepository.findById(Long.valueOf(user_id)).get();
    }








    // PASSWORD RESET STUFF

    public void resetPassword(ResetPasswordRequest request) {
        Token token = tokenRepository.findByToken(request.getToken())
                .orElseThrow(() -> new RuntimeException("Invalid token"));
        if (LocalDateTime.now().isAfter(token.getExpiresAt())) {
            throw new ResetPasswordTokenExpiredException("Token has expired. Please request a new one.");
        }
        if (token.getValidatedAt() != null) {
            throw new ResetPasswordTokenAlreadyUsedException("This token has already been used.");
        }
        User user = token.getUser();
        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);

        token.setValidatedAt(LocalDateTime.now());
        tokenRepository.save(token);

    }





    public void forgottenPassword(ForgottenPasswordRequest request) throws MessagingException, UnsupportedEncodingException {
        var user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));

        System.out.println(user.getFullName());
        sendResetPasswordEmail(user);
    }



    private void sendResetPasswordEmail(User user) throws MessagingException, UnsupportedEncodingException {
        var newToken = generateAndSaveResetPasswordToken(user);
//        String resetUrl = resetURL.replace("resetpassword", "reset-password") + "?token=" + newToken;
        String resetUrl = frontendBaseUrl + "/reset-password?token=" + newToken;

        System.out.println(resetUrl);
        System.out.println(newToken);

        Map<String, Object> vars = new HashMap<>();
        vars.put("username", user.getFullName());
        vars.put("resetUrl", resetUrl);
        vars.put("newToken", newToken);
        vars.put("baseUrl", "http://localhost:4200/");

        emailServices.sendEmail(
                user.getEmail(),
                EmailTemplateName.RESET_PASSWORD,
                vars,
                "Password Reset"
        );

//        emailService.sendEmail(
//                user.getUsername(),
//                user.getFullName(),
//                EmailTemplateName.RESET_PASSWORD,
//                resetUrl,
//                newToken,
//                "Password Reset"
//        );
    }












    private void sendResetPasswordPhone(User user) throws MessagingException, UnsupportedEncodingException {
        var newToken = generateAndSaveResetPasswordToken(user);
//        String resetUrl = resetURL.replace("resetpassword", "reset-password") + "?token=" + newToken;
        String resetUrl = frontendBaseUrl + "/reset-password?token=" + newToken;

        System.out.println(resetUrl);
        System.out.println(newToken);

        Map<String, Object> vars = new HashMap<>();
        vars.put("username", user.getFullName());
        vars.put("resetUrl", resetUrl);
        vars.put("newToken", newToken);
        vars.put("baseUrl", "http://localhost:4200/");

        emailServices.sendEmail(
                user.getEmail(),
                EmailTemplateName.RESET_PASSWORD,
                vars,
                "Password Reset"
        );

        mNotifyV2SmsService.sendSms(user.getPhone(), vars);


    }










    private String generateAndSaveResetPasswordToken(User user) {
        String generatedToken = generateActivationCode(6); // Reuse your existing token generator
        var token = Token.builder()
                .token(generatedToken)
                .createdAt(LocalDateTime.now())
                .expiresAt(LocalDateTime.now().plusMinutes(15))
                .user(user)
                .build();
        tokenRepository.save(token);
        return generatedToken;
    }




    private String generateActivationCode(int length) {
        String characters = "0123456789";
        StringBuilder codeBuilder = new StringBuilder();
        SecureRandom secureRandom = new SecureRandom();
        for (int i = 0; i < length; i++) {
            int randomIndex = secureRandom.nextInt(characters.length());
            codeBuilder.append(characters.charAt(randomIndex));
        }
        return codeBuilder.toString();
    }











































    // Convert User to DTO
    private LecturerDTO toDTO(User user) {
        return new LecturerDTO(
                user.getId(),
                user.getFirstname(),
                user.getLastname(),
                user.getEmail(),
                user.getUsername(),
                user.getFullName(),
                user.getPhone()
        );
    }

    // Get all lecturers as DTOs
    public List<LecturerDTO> getAllLecturers() {
        return userRepository.findByRole(Role.LECTURER)
                .stream()
                .map(this::toDTO)
                .toList();
    }


    // Get all lecturers as DTOs
    public List<LecturerDTO> getAllStudents() {
        return userRepository.findByRole(Role.NORMAL)
                .stream()
                .map(this::toDTO)
                .toList();
    }





    // Get lecturer by id as DTO
    public Optional<LecturerDTO> getLecturerById(Long id) {
        return userRepository.findByIdAndRole(id, Role.LECTURER)
                .map(this::toDTO);
    }



    // Get lecturer by id as DTO
    public Optional<LecturerDTO> getStuentById(Long id) {
        return userRepository.findByIdAndRole(id, Role.NORMAL)
                .map(this::toDTO);
    }

    // Create or update lecturer and return DTO
    public LecturerDTO saveOrUpdateLecturer(User lecturer) {
        lecturer.setRole(Role.LECTURER); // Ensure role is LECTURER
        User saved = userRepository.save(lecturer);
        return toDTO(saved);
    }

    @Transactional
    public Optional<LecturerDTO> updateLecturer(Long id, LecturerUpdateDTO updateDTO) {
        System.out.println("=== Received DTO ===");
        System.out.println("Firstname: " + updateDTO.getFirstname());
        System.out.println("Lastname: " + updateDTO.getLastname());
        System.out.println("Email: " + updateDTO.getEmail());
        System.out.println("Phone: " + updateDTO.getPhone());
        System.out.println("Phone: " + updateDTO.getUsername());

        System.out.println("==================");

        return userRepository.findById(id)
                .filter(user -> user.getRole() == Role.LECTURER)
                .map(existing -> {
                    System.out.println("Before update: " + existing.getFirstname());

                    // ✅ ADD THESE DEBUG LINES
                    String newFirstname = updateDTO.getFirstname();
                    String newLastname = updateDTO.getLastname();
                    String newEmail = updateDTO.getEmail();
                    String newPhone = updateDTO.getPhone();
                    String username = updateDTO.getUsername();

                    System.out.println("DTO firstname value: '" + newFirstname + "'");
                    System.out.println("DTO lastname value: '" + newLastname + "'");
                    System.out.println("DTO email value: '" + newEmail + "'");
                    System.out.println("DTO phone value: '" + newPhone + "'");

                    existing.setFirstname(newFirstname);
                    existing.setLastname(newLastname);
                    existing.setEmail(newEmail);
                    existing.setPhone(newPhone);
                    existing.setUsername(username);


//                    System.out.println("After update: " + existing.getFirstname());

                    User saved = userRepository.save(existing);
                    userRepository.flush();
//
//                    System.out.println("Saved entity ID: " + saved.getId());
//                    System.out.println("Saved first name: " + saved.getFirstname());

                    return toDTO(saved);
                });
    }



    @Transactional
    public Optional<LecturerDTO> updateStudent(Long id, LecturerUpdateDTO updateDTO) {

        System.out.println("=== Received DTO ===");
        System.out.println("Firstname: " + updateDTO.getFirstname());
        System.out.println("Lastname: " + updateDTO.getLastname());
        System.out.println("Email: " + updateDTO.getEmail());
        System.out.println("Phone: " + updateDTO.getPhone());
        System.out.println("Phone: " + updateDTO.getUsername());

        System.out.println("==================");


        return userRepository.findById(id)
                .filter(user -> user.getRole() == Role.NORMAL)
                .map(existing -> {
                    existing.setFirstname(updateDTO.getFirstname());
                    existing.setLastname(updateDTO.getLastname());
                    existing.setEmail(updateDTO.getEmail());
                    existing.setPhone(updateDTO.getPhone());
                    existing.setUsername(updateDTO.getUsername());
                    User saved = userRepository.save(existing);
                    userRepository.flush();
                    return toDTO(saved);
                });
    }




    private LecturerDTO LetDTO(User user) {
        LecturerDTO dto = new LecturerDTO();
//        dto.setId(user.getId());
        dto.setFirstname(user.getFirstname());
        dto.setLastname(user.getLastname());
        dto.setEmail(user.getEmail());
        dto.setPhone(user.getPhone());
//        dto.setPhone(user.getPhone());
//        dto.setRole(user.getRole());
        return dto;
    }




    // Get lecturer entity by ID
    public Optional<User> getLecturerEntityById(Long id) {
        return userRepository.findById(id)
                .filter(user -> user.getRole() == Role.LECTURER);
    }


    // Delete lecturer
    public void deleteLecturer(Long id) {
        Optional<User> lecturer = userRepository.findByIdAndRole(id, Role.LECTURER);
        lecturer.ifPresent(userRepository::delete);
    }



    public void deleteStudent(Long id) {
        Optional<User> student = userRepository.findByIdAndRole(id, Role.NORMAL);
        student.ifPresent(userRepository::delete);
    }




//    CONTROLLER FOR GETTING LECTURER, ADMIN,STUDENT

    public StudentResponse getStudents() {
        List<User> students = userRepository.findByRole(Role.NORMAL);
        return new StudentResponse(students);
    }

    public LecturerResponse getLecturers() {
        List<User> lecturers = userRepository.findByRole(Role.LECTURER);
        return new LecturerResponse(lecturers);
    }

    public AdminResponse getAdmins() {
        List<User> admins = userRepository.findByRole(Role.ADMIN);
        return new AdminResponse(admins);
    }











}
