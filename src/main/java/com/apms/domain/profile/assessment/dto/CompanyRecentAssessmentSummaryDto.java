package com.apms.domain.profile.assessment.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompanyRecentAssessmentSummaryDto {
    private String companyProfileId;
    private String companyId;
    private String companyName;
    private String relationshipType;
    private RecentAssessmentItemDto latestAssessment;
    private RecentAssessmentItemDto previousAssessment;
}
