package com.apms.domain.contract.entity;

import com.apms.domain.ai.dto.ExtractionFieldResult;
import com.apms.domain.ai.dto.ExtractionValidationStatus;
import com.apms.domain.contract.dto.ContractClauseTerms;
import com.apms.domain.contract.enums.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * MongoDB document representing the AI-produced extraction draft for a PartnerContract.
 * Collection: partner_contract_extractions
 */
@Document(collection = "partner_contract_extractions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PartnerContractExtractionDraft {

    @Id
    private String id;

    @Indexed
    private Long partnerContractId;

    @Indexed
    private String rawDocumentId;

    @Indexed
    private Long sourceProjectId;

    private Long sourceTaskId;
    private String sourceDocumentHash;
    private String sourceExtractionVersion;

    private ContractExtractionPurpose purpose;
    private String targetCompanyProfileId;
    private String ownerCompanyProfileId;

    private Integer contractVersionAtGeneration;
    private Integer currentApprovedVersion;
    private Integer expectedNextApprovalVersion;

    private ContractExtractionGenerationStatus generationStatus;
    private ContractExtractionQualityStatus qualityStatus;
    private ContractExtractionReviewStatus reviewStatus;

    private ContractExtractionApplicationStatus applicationStatus;
    private ContractExtractionApprovalSyncStatus approvalSyncStatus;
    private String clauseSetHash;

    private String applyOperationId;
    private LocalDateTime applyStartedAt;

    private String modelProvider;
    private String modelName;
    private String promptVersion;

    private LocalDateTime generatedAt;
    private Long generatedByAccountId;

    private LocalDateTime appliedAt;
    private Long appliedByAccountId;

    private String supersededByExtractionId;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @Field("fieldResults")
    private List<ContractExtractionFieldResult> fieldResults;

    @Field("clauseCandidates")
    private List<ClauseCandidate> clauseCandidates;

    @Field("segments")
    private List<com.apms.domain.contract.dto.ContractDocumentSegment> segments;

    @Field("validationErrors")
    private List<String> validationErrors;

    @Field("warnings")
    private List<String> warnings;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ContractExtractionFieldResult {
        private String fieldName;
        private Object value;
        private Object normalizedValue;
        private Double confidence;
        private String evidenceText;
        private List<String> evidenceReferences;
        private String sourceDocumentId;
        private Integer pageNumber;

        @Builder.Default
        private ExtractionValidationStatus validationStatus = ExtractionValidationStatus.NOT_CHECKED;
        private String validationMessages;

        private ContractExtractionReviewDecision reviewDecision;
        private Object reviewedValue;
        private Long reviewedByAccountId;
        private LocalDateTime reviewedAt;
        private String reviewComment;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ClauseCandidate {
        private String clauseCandidateId;
        private String clauseType;
        private String clauseTitle;
        private String sourceExcerpt;

        private ContractClauseTerms normalizedTerms;

        private LocalDate effectiveDate;
        private LocalDate expiryDate;
        private Integer noticePeriodDays;

        private String targetMetricKey;
        private String targetValue;
        private String targetUnit;
        private String comparator;
        private String measurementPeriod;

        private BigDecimal penaltyValue;
        private String penaltyCurrency;
        private String penaltyDescription;

        private Double confidence;
        private List<String> evidenceReferences;
        private List<String> missingData;
        private List<String> ambiguities;

        @Builder.Default
        private ExtractionValidationStatus validationStatus = ExtractionValidationStatus.NOT_CHECKED;

        private ContractExtractionReviewDecision reviewDecision;

        private List<String> reviewedFields;
        private String reviewComment;
        private Long reviewedByAccountId;
        private LocalDateTime reviewedAt;
    }
}
