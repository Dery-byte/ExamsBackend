package com.exam.helper;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

public class CustomLocalDateDeserializer extends JsonDeserializer<LocalDate> {
    @Override
    public LocalDate deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        String dateString = p.getValueAsString();
        if (dateString == null || dateString.isEmpty()) {
            return null;
        }

        // Handle both "2025-09-26" and "2025-09-26T00:00:00.000Z"
        if (dateString.contains("T")) {
            return Instant.parse(dateString)
                    .atZone(ZoneOffset.UTC)
                    .toLocalDate();
        } else {
            return LocalDate.parse(dateString);
        }
    }
}