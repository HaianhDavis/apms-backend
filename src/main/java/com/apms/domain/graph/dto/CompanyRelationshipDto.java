package com.apms.domain.graph.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class CompanyRelationshipDto {
    private String sourceCompanyId;
    private String targetCompanyId;
    private String relationshipType;
    private Double confidenceScore;
    private String confirmedBy;
    private LocalDateTime confirmedAt;
    private String notes;
    private String projectId;
    private String candidateId;
}
