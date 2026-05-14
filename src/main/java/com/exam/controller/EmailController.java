package com.exam.controller;
import com.exam.helper.CustomResponse;
import com.exam.model.Mail;
import com.exam.service.Impl.MailService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;


@RestController
@CrossOrigin( origins = "*")
@RequestMapping("/api/v1/auth")

public class EmailController {

    @Autowired
    private MailService mailService;

    @PostMapping("/sendMail")
    public Mail sendMail(@RequestBody Mail mail) {
        mailService.sendEmail(
                mail.getMailTo(),
                mail.getMailFrom(),
                mail.getMailContent(),
                mail.getMailSubject(),
                mail.getMailSendDate());
//        mailService.sendEmail(mail);
        return mail;

    }


    @PostMapping("/sendMail2")
    public ResponseEntity<?> sendEmail(@RequestBody Mail mail){
        mailService.sendEmailWithHtml(
                mail.getMailFrom(),
                mail.getMailTo(),
                mail.getMailSubject(),
                mail.getMailContent());
        return ResponseEntity.ok(CustomResponse.builder().message("Email Send Successfully !!").httpStatus(HttpStatus.OK).success(true).build()
        );
    }




    @PostMapping("/sendMailf")
    public ResponseEntity<CustomResponse> sendWithFile(@RequestPart Mail mail, @RequestPart MultipartFile file) throws IOException {
        mailService.sendEmailWithFile(
//                mail.getMailTo(),
                mail.getMailSubject(),
                mail.getMailContent(),
                mail.getMailFrom(),
                file.getInputStream());
        return ResponseEntity.ok(
                CustomResponse.builder().message("Email Send Successfully !!").httpStatus(HttpStatus.OK).success(true)
                        .build());

    }




}
