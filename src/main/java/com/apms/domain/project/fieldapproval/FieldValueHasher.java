package com.apms.domain.project.fieldapproval;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class FieldValueHasher {

    private static final ObjectMapper MAPPER;

    static {
        MAPPER = new ObjectMapper();
        MAPPER.registerModule(new JavaTimeModule());
        MAPPER.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        MAPPER.setSerializationInclusion(JsonInclude.Include.ALWAYS);
    }

    public static String hashValue(Object value, boolean isUnorderedCollection) {
        if (value == null) {
            return hashString("null");
        }

        Object normalizedValue = value;
        if (isUnorderedCollection && value instanceof List) {
            List<?> list = (List<?>) value;
            normalizedValue = list.stream()
                    .map(item -> item == null ? "null" : item.toString())
                    .sorted()
                    .collect(Collectors.toList());
        }

        try {
            String json = MAPPER.writeValueAsString(normalizedValue);
            return hashString(json);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to hash field value", e);
        }
    }

    private static String hashString(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not found", e);
        }
    }
}
