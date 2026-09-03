package com.apms.domain.contract.model;

import com.apms.domain.contract.enums.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContractEntry {
    private String id;
    private Long projectId;
    private Long taskId;
    private String documentId;
    private String documentName;
    private String title;
    private LocalDate documentDate;

    // Type classification & resolution (Canonical Type Owner)
    private ContractType declaredContractType;
    private ContractType detectedContractType;
    private ContractType confirmedContractType;
    private TypeValidationStatus typeValidationStatus;
    private Long typeConfirmedBy;
    private LocalDateTime typeConfirmedAt;

    // Classification metadata
    private Integer classificationSourcePage;
    private String classificationEvidence;
    private Double classificationConfidence;

    // Target Company Validation
    private CompanyMatchStatus companyMatchStatus;
    private Boolean companyMatchConfirmed;
    private Long companyMatchConfirmedBy;
    private LocalDateTime companyMatchConfirmedAt;

    // Derived Status Provenance (Backend-derived)
    private ContractStatus derivedContractStatus;
    private LocalDateTime statusDerivedAt;
    private String statusDerivationReason;

    // Extraction state
    @Builder.Default
    private ContractExtractionStatus extractionStatus = ContractExtractionStatus.NOT_EXTRACTED;
    private ContractExtractionStage extractionStage;
    private Integer extractionProgress;
    private LocalDateTime extractionStartedAt;
    private LocalDateTime extractionCompletedAt;
    private String extractionErrorCode;
    private String extractionErrorMessage;

    // Review state (Current active decision)
    @Builder.Default
    private ContractEntryReviewStatus reviewStatus = ContractEntryReviewStatus.DRAFT;
    private Long reviewedBy;
    private String reviewedByName;
    private LocalDateTime reviewedAt;
    private String reviewComment;

    // Immutable Review History
    @Builder.Default
    private List<ContractReviewEvent> reviewHistory = new ArrayList<>();

    // Extracted Data (Single Source of Truth)
    private CommonContractData commonData;
    private CooperationAgreementData cooperationAgreementData;
    private PartnershipAgreementData partnershipAgreementData;
    private JointVentureAgreementData jointVentureAgreementData;
    private BusinessCooperationContractData businessCooperationContractData;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
