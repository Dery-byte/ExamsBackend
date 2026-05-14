package com.exam.service.Impl;


import com.exam.model.Mail;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Date;

@Service
public class MailServiceImpl implements MailService
{
    @Autowired
    private JavaMailSender javaMailSender;

    public MailServiceImpl(JavaMailSender mailSender) {
        this.javaMailSender = mailSender;
    }
    private Logger logger= LoggerFactory.getLogger(MailServiceImpl.class);

    @Override
    public void sendEmail(Mail mail) {

    }

    @Override
    public void sendEmail(String to, String subject, String message)
    {
        MimeMessage mimeMessage = this.javaMailSender.createMimeMessage();

//			MimeMessageHelper mimeMessageHelper = new MimeMessageHelper(mimeMessage, true);
//			mimeMessageHelper.setSubject(mail.getMailSubject());
//			mimeMessageHelper.setFrom(String.valueOf(new InternetAddress(mail.getMailFrom())));
//			mimeMessageHelper.setTo(mail.getMailTo());
//			mimeMessageHelper.setText(mail.getMailContent());
//			javaMailSender.send(mimeMessageHelper.getMimeMessage());

        SimpleMailMessage simpleMailMessage=new SimpleMailMessage();
        simpleMailMessage.setTo(to);
        simpleMailMessage.setSubject(subject);
        simpleMailMessage.setText(message);
        simpleMailMessage.setFrom("emmanuelderryshare@gmail.com");
        javaMailSender.send(simpleMailMessage);
        logger.info("Email has been sent");

    }


    @Override
    public void sendEmail(String[] to, String subject, String message) {
        SimpleMailMessage simpleMailMessage = new SimpleMailMessage();
        simpleMailMessage.setTo(to);
        simpleMailMessage.setSubject(subject);
        simpleMailMessage.setText(message);
        simpleMailMessage.setFrom("emmanuelderryshare@gmail.com");
        javaMailSender.send(simpleMailMessage);
    }

    @Override
    public void sendEmailWithFile(String to, String subject, String message, File file) {
        MimeMessage mimeMessage = javaMailSender.createMimeMessage();
        try {
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true);
            helper.setTo(to);
            helper.setText(message);
            helper.setSubject(subject);
            helper.setFrom("emmanuelderryshare@gmail.com");
            FileSystemResource fileSystemResource=new FileSystemResource(file);
            helper.addAttachment(fileSystemResource.getFilename(), file);
            javaMailSender.send(mimeMessage);
            logger.info("Email send success");

        } catch (MessagingException e) {
            throw new RuntimeException(e);
        }

    }



    @Override
    public void sendEmailWithHtml(String from, String to,String subject, String htmlContent) {
        MimeMessage simpleMailMessage=javaMailSender.createMimeMessage();
        try{
            MimeMessageHelper helper=new MimeMessageHelper(simpleMailMessage,true,"UTF-8");
            helper.setTo("emmanuelderryshare@gmail.com");
            helper.setSubject(subject);
            helper.setFrom(from);
            helper.setText(htmlContent,true);
            javaMailSender.send(simpleMailMessage);
            logger.info("Email has been sent");

        }catch(MessagingException e){
            throw new RuntimeException(e);
        }
    }


    @Override
    public void sendEmailWithFile(String to, String subject, String message, InputStream ins) {
        MimeMessage mimeMessage = javaMailSender.createMimeMessage();
        try {
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true);
            helper.setTo(to);
            helper.setText(message,true);
            helper.setSubject(subject);
            // helper.setFrom("avinash20020114@gmail.com");
            File file = new File("E:\\vs code\\project\\EmailSenderApp\\src\\main\\resources\\static\\images\\email\\test.png");
            Files.copy(ins, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            FileSystemResource fileSystemResource=new FileSystemResource(file);
            helper.addAttachment(fileSystemResource.getFilename(), file);
            javaMailSender.send(mimeMessage);
            logger.info("Email send success");

        } catch (MessagingException e) {
            throw new RuntimeException(e);
        }
        catch (IOException e){
            throw new RuntimeException(e);
        }
    }

    @Override
    public void sendEmail(String mailTo, String mailFrom, String mailContent, String mailSubject, Date mailSendDate) {

    }


}

