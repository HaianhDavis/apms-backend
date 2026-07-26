package com.apms.domain.score.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CanonicalScoreJsonMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CanonicalScoreJsonMapper mapper = new CanonicalScoreJsonMapper(objectMapper);

    @Test
    void shouldSerializeAndDeserializeMapPreservingOrderAndBigDecimal() {
        LinkedHashMap<String, BigDecimal> original = new LinkedHashMap<>();
        original.put("a", new BigDecimal("10.50"));
        original.put("b", null);
        original.put("c", new BigDecimal("100.00"));

        String json = mapper.serializeMap(original);
        assertThat(json).contains("\"a\":10.50");
        assertThat(json).contains("\"b\":null");

        LinkedHashMap<String, BigDecimal> deserialized = mapper.deserializeMap(json);

        assertThat(deserialized).hasSize(3);

        // Preserve order
        List<String> keys = List.copyOf(deserialized.keySet());
        assertThat(keys).containsExactly("a", "b", "c");

        // Preserve values
        assertThat(deserialized.get("a")).isEqualTo(new BigDecimal("10.50"));
        assertThat(deserialized.get("b")).isNull();
        assertThat(deserialized.get("c")).isEqualTo(new BigDecimal("100.00"));
    }

    @Test
    void shouldHandleNullAndEmptyMaps() {
        assertThat(mapper.serializeMap(null)).isNull();

        assertThat(mapper.deserializeMap(null)).isEmpty();
        assertThat(mapper.deserializeMap("")).isEmpty();
    }

    @Test
    void shouldSerializeAndDeserializeList() {
        List<String> original = List.of("x", "y");

        String json = mapper.serializeList(original);
        assertThat(json).isEqualTo("[\"x\",\"y\"]");

        List<String> deserialized = mapper.deserializeList(json);
        assertThat(deserialized).containsExactly("x", "y");
    }

    @Test
    void shouldHandleNullAndEmptyLists() {
        assertThat(mapper.serializeList(null)).isNull();

        assertThat(mapper.deserializeList(null)).isEmpty();
        assertThat(mapper.deserializeList("")).isEmpty();
    }
}
