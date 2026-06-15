package com.apms.domain.ai.dto;

import com.apms.common.enums.RelationshipType;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ExtractedCompanyData {

    private String companyName;
    private String industry;
    private String website;
    private String description;

    /**
     * AI-suggested relationship types based on the extracted context.
     */
    private List<SuggestedRelationship> suggestedRelationships;

    @Data
    @Builder
    public static class SuggestedRelationship {
        private RelationshipType relationshipType;
        private Double confidenceScore;
        private String reasoning;
    }
}
