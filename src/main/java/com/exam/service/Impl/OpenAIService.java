package com.exam.service.Impl;

import com.exam.config.OpenAIConfig;
import com.exam.model.exam.GradeRequest;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class OpenAIService {

    private final OpenAIConfig openAIConfig;

    public OpenAIService(OpenAIConfig openAIConfig) {
        this.openAIConfig = openAIConfig;
    }

    public String gradeResponse(GradeRequest gradeRequest) throws Exception {
        String prompt = "Question: " + gradeRequest.getQuestion() + "\n\nStudent's Response: " + gradeRequest.getAnswer() +
                "\n\nPlease evaluate the response based on accuracy, completeness, clarity, and relevance, and provide a score from 0 to 10 with an explanation.";

        CloseableHttpClient client = HttpClients.createDefault();
        HttpPost httpPost = new HttpPost("https://api.openai.com/v1/completions");

        // Construct the JSON payload correctly
        JSONObject json = new JSONObject();
        json.put("model", "gpt-3.5-turbo-1106");
        json.put("prompt", prompt);
        json.put("max_tokens", 150);

        StringEntity entity = new StringEntity(json.toString());
        httpPost.setEntity(entity);
        httpPost.setHeader("Content-type", "application/json");
        httpPost.setHeader("Authorization", "Bearer " + openAIConfig.getApiKey());

        String responseBody = EntityUtils.toString(client.execute(httpPost).getEntity());
        client.close();

        return responseBody;
    }
}