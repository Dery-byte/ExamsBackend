//package com.exam.service;
//
//import com.exam.DTO.SmsRequest;
//import com.exam.config.MNotifyV2Config;
//import com.fasterxml.jackson.databind.ObjectMapper;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.http.*;
//import org.springframework.stereotype.Service;
//import org.springframework.web.client.RestTemplate;
//import org.springframework.web.util.UriComponentsBuilder;
//
//import java.util.List;
//
//@Service
//public class MNotifyV2SmsService {
//
//    @Autowired
//    private MNotifyV2Config mNotifyV2Config;
//
//    private final RestTemplate restTemplate = new RestTemplate();
//
//    public String sendSms(List<String> recipients, String message) {
//        try {
//            String urlWithKey = UriComponentsBuilder.fromHttpUrl(mNotifyV2Config.getUrl())
//                    .queryParam("key", mNotifyV2Config.getKey())
//                    .toUriString();
//
//            System.out.println("Sending to: " + urlWithKey);
//            System.out.println("Using key: " + mNotifyV2Config.getKey());
//
//            SmsRequest smsRequest = new SmsRequest();
//            smsRequest.setRecipient(recipients);
//            smsRequest.setSender(mNotifyV2Config.getSenderId());
//            smsRequest.setMessage(message);
//            smsRequest.getIs_schedule();
//
//
//            System.out.println(smsRequest.getMessage());
//            System.out.println(smsRequest);
//
//            HttpHeaders headers = new HttpHeaders();
//            headers.setContentType(MediaType.APPLICATION_JSON);
//
//            HttpEntity<SmsRequest> requestEntity = new HttpEntity<>(smsRequest, headers);
//            System.out.println(requestEntity);
//
//            ResponseEntity<String> response = restTemplate.exchange(
//                    urlWithKey,
//                    HttpMethod.POST,
//                    requestEntity,
//                    String.class
//            );
//            System.out.println("This is the reponses"+ response);
//
//            System.out.println(new ObjectMapper().writeValueAsString(smsRequest));
//            System.out.println("Response: " + response.getBody());
//
//            return response.getBody();
//
//        } catch (Exception e) {
//            System.err.println("❌ Exception during SMS sending: " + e.getMessage());
//            e.printStackTrace();
//            return "SMS sending failed: " + e.getMessage();
//        }
//    }
//
//}




package com.exam.service;

import com.exam.DTO.SmsRequest;
import com.exam.config.MNotifyV2Config;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Map;

@Service
public class MNotifyV2SmsService {

    @Autowired
    private MNotifyV2Config mNotifyV2Config;

    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * Send SMS with pre-formatted message to multiple recipients
     * @param recipients List of phone numbers
     * @param message The SMS message to send
     * @return Response from MNotify API
     */
    public String sendSms(List<String> recipients, String message) {
        try {
            String urlWithKey = UriComponentsBuilder.fromHttpUrl(mNotifyV2Config.getUrl())
                    .queryParam("key", mNotifyV2Config.getKey())
                    .toUriString();

            System.out.println("Sending to: " + urlWithKey);
            System.out.println("Using key: " + mNotifyV2Config.getKey());

            SmsRequest smsRequest = new SmsRequest();
            smsRequest.setRecipient(recipients);
            smsRequest.setSender(mNotifyV2Config.getSenderId());
            smsRequest.setMessage(message);
            smsRequest.getIs_schedule();

            System.out.println(smsRequest.getMessage());
            System.out.println(smsRequest);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<SmsRequest> requestEntity = new HttpEntity<>(smsRequest, headers);
            System.out.println(requestEntity);

            ResponseEntity<String> response = restTemplate.exchange(
                    urlWithKey,
                    HttpMethod.POST,
                    requestEntity,
                    String.class
            );
            System.out.println("This is the response: " + response);

            System.out.println(new ObjectMapper().writeValueAsString(smsRequest));
            System.out.println("Response: " + response.getBody());

            return response.getBody();

        } catch (Exception e) {
            System.err.println("❌ Exception during SMS sending: " + e.getMessage());
            e.printStackTrace();
            return "SMS sending failed: " + e.getMessage();
        }
    }

    /**
     * Send SMS to single recipient with pre-formatted message
     * @param recipient Single phone number
     * @param message The SMS message to send
     * @return Response from MNotify API
     */
    public String sendSms(String recipient, String message) {
        return sendSms(List.of(recipient), message);
    }

    /**
     * Send password reset SMS with variables
     * Backend generates the SMS message with reset link
     * @param recipient Phone number
     * @param vars Map containing username and resetUrl
     * @return Response from MNotify API
     */
    public String sendSms(String recipient, Map<String, Object> vars) {
        try {
            String username = (String) vars.get("username");
            String resetUrl = (String) vars.get("resetUrl");

            // Backend creates the SMS message with clickable link
            // RECOMMENDED FORMAT - Most compatible
            String message = String.format(
                    "Hi %s\n" +
                            "Reset your password: Click the the link\n" +
                            "%s\n" +
                            "it expires in 30 minutes.\n" +
                            "Ignore this if you didn't request it.",
                    username,
                    resetUrl
            );

            System.out.println("📱 Sending password reset SMS to: " + recipient);
            System.out.println("🔗 Reset URL: " + resetUrl);
            System.out.println("📧 Message: " + message);

            // Use the existing sendSms method
            return sendSms(List.of(recipient), message);

        } catch (Exception e) {
            System.err.println("❌ Error formatting password reset SMS: " + e.getMessage());
            e.printStackTrace();
            return "SMS formatting failed: " + e.getMessage();
        }
    }

    /**
     * Send password reset SMS to multiple recipients with variables
     * @param recipients List of phone numbers
     * @param vars Map containing username and resetUrl
     * @return Response from MNotify API
     */
    public String sendSms(List<String> recipients, Map<String, Object> vars) {
        try {
            String username = (String) vars.get("username");
            String resetUrl = (String) vars.get("resetUrl");

            // Backend creates the SMS message with clickable link
            String message = String.format(
                    "Hi %s, " +
                            "Click this link to reset your password: %s " +
                            "This link will expire in 30 minutes. " +
                            "If you didn't request this, please ignore this message.",
                    username,
                    resetUrl
            );

            System.out.println("📱 Sending password reset SMS to: " + recipients);
            System.out.println("🔗 Reset URL: " + resetUrl);

            // Use the existing sendSms method
            return sendSms(recipients, message);

        } catch (Exception e) {
            System.err.println("❌ Error formatting password reset SMS: " + e.getMessage());
            e.printStackTrace();
            return "SMS formatting failed: " + e.getMessage();
        }
    }
}