package com.exam.model.exam;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.AttributeConverter;

import java.io.IOException;

public class StringArrayConverter implements AttributeConverter<String[], String> {
    private final ObjectMapper objectMapper = new ObjectMapper();
    @Override
    public String convertToDatabaseColumn(String[] attribute) {
        try {
            return objectMapper.writeValueAsString(attribute);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to convert string array to JSON", e);
        }
//        return null;
    }
    @Override
    public String[] convertToEntityAttribute(String dbData) {
        try {
            return objectMapper.readValue(dbData, String[].class);
        } catch (IOException e) {
            throw new RuntimeException("Failed to convert JSON to string array", e);
        }
//        return new String[0];
    }
}
