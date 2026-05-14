package com.exam.service.Impl;

import com.exam.model.Mail;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.InputStream;
import java.util.Date;

public interface MailService
{
    public void sendEmail(Mail mail);

    void sendEmail(String to, String subject, String message);

    void sendEmail(String[] to, String subject, String message);

    void sendEmailWithFile(String to, String subject, String message, File file);

    void sendEmailWithHtml(String mailTo, String mailFrom, String mailContent, String mailSubject);

    void sendEmailWithFile(String to, String subject, String message, InputStream ins);

    void sendEmail(String mailTo, String mailFrom, String mailContent, String mailSubject, Date mailSendDate);
}
