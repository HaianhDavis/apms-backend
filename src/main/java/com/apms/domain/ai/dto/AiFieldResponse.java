package com.apms.domain.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AiFieldResponse {
    private Object value;
    private Double confidence;
    private String evidenceText;
    private Integer pageNumber;
}
