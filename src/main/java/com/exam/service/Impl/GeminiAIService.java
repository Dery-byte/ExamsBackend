package com.exam.service.Impl;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;



@Service
public class GeminiAIService {
    private static final String GEMINI_ORDER_STATUS_URL = "https://api.gemini.com";
    private final String apiKey;
    private final RestTemplate restTemplate;

    @Autowired
    public GeminiAIService(@Value("${google.gemini.api.key}") String apiKey, RestTemplate restTemplate) {
        this.apiKey = apiKey;
        this.restTemplate = restTemplate;
    }
//    public String getSomeDataFromGemini() {
//        String url = "https://api.gemini.com/v1/order/status";
//        return restTemplate.getForObject(url + "?apiKey=" + apiKey, String.class);
//    }

    public String getSomeDataFromGemini(String question) {
        String url = UriComponentsBuilder.fromHttpUrl(GEMINI_ORDER_STATUS_URL)
                .queryParam("apiKey", apiKey)
                .queryParam("question", question)
                .toUriString();
        try {
            return restTemplate.getForObject(url, String.class);
        } catch (HttpClientErrorException | HttpServerErrorException e) {
            System.err.println("HTTP Status Code: " + e.getStatusCode());
            System.err.println("Response Body: " + e.getResponseBodyAsString());
            throw new RuntimeException("Error retrieving data from Gemini API", e);
        } catch (Exception e) {
            System.err.println("Unexpected Error: " + e.getMessage());
            throw new RuntimeException("Error retrieving data from Gemini API", e);
        }
    }
}