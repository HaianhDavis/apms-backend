package com.apms.domain.dashboard.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DashboardSummaryDto {
    private long totalCompanyProfiles;
    private long totalProjects;
    private long totalCandidates;
    private long approvedCandidates;
    private long pendingReviewCandidates;
    private long verifiedCompanyCount;
    private long totalIndustries;

    private long partnerCount;
    private long competitorCount;
    private long supplierCount;
    private long customerCount;
    private long potentialPartnerCount;

    private long totalRelatedCompanies;
    private RelationshipClosenessOverviewDto relationshipClosenessOverview;
    private java.util.List<RelationshipCompositionDto> relationshipComposition;
    private SignalOverviewDto riskOverview;
    private SignalOverviewDto opportunityOverview;
    private java.util.List<RecentActivityDto> recentActivities;
}
