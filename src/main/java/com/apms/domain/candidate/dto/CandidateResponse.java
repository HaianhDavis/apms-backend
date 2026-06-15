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
    private Integer candidateOrder;
    private Integer revisionNumber;
    private CandidateStatus status;
    private RelationshipType suggestedRelationshipType;
    private Double relationshipConfidenceScore;
    private RelationshipType relationshipTypeOverride;

    private CompanyCandidate.Identity identity;
    private CompanyCandidate.Business business;
    private CompanyCandidate.CompanySize companySize;
    private CompanyCandidate.Contact contact;
    private CompanyCandidate.Insights insights;
    private CompanyCandidate.Validation validation;
    private CompanyCandidate.Normalization normalization;
    private CompanyCandidate.Deduplication deduplication;
    private CompanyCandidate.ExtractionSource extractionSource;
    private CompanyCandidate.Review review;
    private CompanyCandidate.ScorePreview scorePreview;
    private CompanyCandidate.AiMetadata aiMetadata;
    private CompanyCandidate.Metadata metadata;
}
