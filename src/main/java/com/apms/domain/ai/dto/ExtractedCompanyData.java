package com.apms.domain.ai.dto;

import com.apms.common.enums.RelationshipType;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ExtractedCompanyData {

    private String legalName;
    private String tradeName;
    private String taxCode;
    private List<String> industries;
    private String businessModel;
    private List<Product> products;
    private List<String> markets;
    private List<String> targetCustomers;
    private String employeeTier;
    private String website;
    private List<String> strengths;
    private List<String> weaknesses;
    private List<String> opportunities;
    private List<String> threats;

    private RelationshipSuggestion relationshipSuggestion;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Product {
        private String name;
        private String category;
        private String description;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RelationshipSuggestion {
        private RelationshipType suggestedType;
        private Double confidence;
        private List<AlternativeRelationship> alternatives;
        private List<String> reasoning;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AlternativeRelationship {
        private RelationshipType type;
        private Double confidence;
    }
}
