package com.apms.domain.score.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;

@Component
public class CanonicalScoreJsonMapper {

    private final ObjectMapper objectMapper;

    public CanonicalScoreJsonMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String serializeMap(LinkedHashMap<String, java.math.BigDecimal> map) {
        if (map == null) return null;
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize canonical score map to JSON", e);
        }
    }

    public LinkedHashMap<String, java.math.BigDecimal> deserializeMap(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try {
            return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, java.math.BigDecimal>>() {});
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize canonical score map from JSON", e);
        }
    }

    public String serializeList(List<String> list) {
        if (list == null) return null;
        try {
            return objectMapper.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize list to JSON", e);
        }
    }

    public String serializeEvidenceMap(java.util.Map<String, List<String>> map) {
        if (map == null || map.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize evidence map to JSON", e);
        }
    }

    public List<String> deserializeList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize list from JSON", e);
        }
    }
}
