package com.apms.domain.profile.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class CompanyProfileSearchResponse {
    private String id;
    private String companyId;
    private String name; // legalName or tradeName
    private List<String> industries;
    private List<String> markets;
    private String employeeTier;
    private String reviewStatus;
    private List<String> relationshipTypes;
    private Double latestTotalScore;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
