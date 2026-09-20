package com.apms.domain.candidate.dto;

import com.apms.common.enums.CandidateStatus;
import com.apms.common.enums.RelationshipType;
import com.apms.domain.candidate.CompanyCandidate;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CandidateResponse {
    private String id;
    private String projectId;
    private String importJobId;
    private String rawDocumentId;
    private java.util.List<String> sourceDocumentIds;
    private Integer candidateOrder;
    private Integer revisionNumber;
    private Integer currentReviewRound;
    private String draftName;
    private Integer draftSequence;
    private CandidateStatus status;
    private com.apms.domain.financial.DocumentCompanyValidationStatus companyMatchStatus;
    private Boolean companyMatchConfirmed;
    private Long companyMatchConfirmedBy;
    private java.time.LocalDateTime companyMatchConfirmedAt;
    private String detectedCompanyName;
    private RelationshipType suggestedRelationshipType;
    private Double relationshipConfidenceScore;
    private RelationshipType relationshipTypeOverride;
    private CompanyCandidate.RelationshipSuggestion relationshipSuggestion;
    private CompanyCandidate.Lifecycle lifecycle;

    private CompanyCandidate.Identity identity;
    private CompanyCandidate.Business business;
    private CompanyCandidate.CompanySize companySize;
    private CompanyCandidate.Contact contact;
    private CompanyCandidate.Insights insights;

    private com.apms.domain.company.model.FinancialInfo financial;
    private com.apms.domain.company.model.MarketInfo market;
    private com.apms.domain.company.model.InnovationInfo innovation;
    private com.apms.domain.company.model.RiskInfo risk;
    private com.apms.domain.company.model.ComplianceInfo compliance;

    private CompanyCandidate.Validation validation;
    private CompanyCandidate.Normalization normalization;
    private CompanyCandidate.Deduplication deduplication;
    private CompanyCandidate.ExtractionSource extractionSource;
    private CompanyCandidate.Review review;
    private java.util.Map<String, java.util.List<CompanyCandidate.DocumentEvidence>> fieldEvidence;
    private java.util.Map<String, com.apms.domain.ai.dto.ExtractionFieldResult> fieldResults;
    private java.util.List<com.apms.domain.project.fieldapproval.FieldApprovalRecord> fieldApprovals;
    private com.apms.domain.ai.dto.ExtractionQualityStatus qualityStatus;
    private com.apms.domain.ai.dto.ExtractionQualityMetrics qualityMetrics;
    private String rawAiOutput;
    private CompanyCandidate.ScorePreview scorePreview;
    private CompanyCandidate.AiMetadata aiMetadata;
    private CompanyCandidate.Metadata metadata;
}
