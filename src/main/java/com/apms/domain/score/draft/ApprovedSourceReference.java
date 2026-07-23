package com.apms.domain.score.draft;

import com.apms.domain.score.enums.ApprovedSourceType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import com.apms.common.exception.BusinessValidationException;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApprovedSourceReference {
    private String referenceId;
    private ApprovedSourceType sourceType;
    private Long sqlSourceId;
    private String mongoSourceId;
    private Integer sourceVersionNumber;
    private String criterionKey;
    private Long projectId;
    private String companyProfileId;
    private LocalDate measurementDate;
    private LocalDate periodStart;
    private LocalDate periodEnd;
    private String sourceHash;
    private LocalDateTime pinnedAt;
    
    @Builder.Default
    private boolean sharedAcrossCriteria = false;
    
    // Metadata for source-specific fields
    private String documentId;
    private String segmentId;
    private String reviewerAccountId;
    private String externalSourceUrl;
    private String manualNoteContent;
    
    public void validate() {
        if (referenceId == null || referenceId.isBlank()) {
            throw new BusinessValidationException("referenceId is required");
        }
        if (sourceType == null) {
            throw new BusinessValidationException("sourceType is required");
        }
        if (sqlSourceId != null && mongoSourceId != null) {
            throw new BusinessValidationException("sqlSourceId and mongoSourceId are mutually exclusive");
        }
        if (sqlSourceId == null && mongoSourceId == null) {
            throw new BusinessValidationException("Either sqlSourceId or mongoSourceId must be provided");
        }
        if (projectId == null) {
            throw new BusinessValidationException("projectId is required");
        }
        if (companyProfileId == null || companyProfileId.isBlank()) {
            throw new BusinessValidationException("companyProfileId is required");
        }
        if (pinnedAt == null) {
            throw new BusinessValidationException("pinnedAt is required");
        }
        
        switch (sourceType) {
            case ROLE_METRIC_VERSION:
            case ROLE_METRIC_EVIDENCE_VERSION:
            case PARTNER_CONTRACT_VERSION:
            case PARTNER_CONTRACT_CLAUSE_VERSION:
                if (sqlSourceId == null) {
                    throw new BusinessValidationException("sqlSourceId is required for SQL-based source types");
                }
                assertNoMongoMetadata();
                assertNoExternalMetadata();
                assertNoManualMetadata();
                assertNoRawDocumentMetadata();
                break;
            case COMPANY_PROFILE_VERSION:
                if (mongoSourceId == null) {
                    throw new BusinessValidationException("mongoSourceId is required for COMPANY_PROFILE_VERSION");
                }
                assertNoExternalMetadata();
                assertNoManualMetadata();
                assertNoRawDocumentMetadata();
                break;
            case RAW_DOCUMENT_SEGMENT:
                if (mongoSourceId == null) {
                    throw new BusinessValidationException("mongoSourceId is required for RAW_DOCUMENT_SEGMENT");
                }
                if (documentId == null || segmentId == null) {
                    throw new BusinessValidationException("documentId and segmentId are required for RAW_DOCUMENT_SEGMENT");
                }
                assertNoExternalMetadata();
                assertNoManualMetadata();
                break;
            case EXTERNAL:
                if (mongoSourceId == null) {
                    throw new BusinessValidationException("mongoSourceId is required for EXTERNAL");
                }
                if (reviewerAccountId == null || externalSourceUrl == null) {
                    throw new BusinessValidationException("reviewerAccountId and externalSourceUrl are required for EXTERNAL");
                }
                assertNoRawDocumentMetadata();
                assertNoManualMetadata();
                break;
            case MANUAL_NOTE:
                if (mongoSourceId == null) {
                    throw new BusinessValidationException("mongoSourceId is required for MANUAL_NOTE");
                }
                if (reviewerAccountId == null || manualNoteContent == null) {
                    throw new BusinessValidationException("reviewerAccountId and manualNoteContent are required for MANUAL_NOTE");
                }
                assertNoRawDocumentMetadata();
                assertNoExternalMetadata();
                break;
        }
    }
    
    private void assertNoMongoMetadata() {
        if (mongoSourceId != null) throw new BusinessValidationException("mongoSourceId not allowed for this source type");
    }
    
    private void assertNoExternalMetadata() {
        if (externalSourceUrl != null) throw new BusinessValidationException("externalSourceUrl not allowed for this source type");
    }
    
    private void assertNoManualMetadata() {
        if (manualNoteContent != null) throw new BusinessValidationException("manualNoteContent not allowed for this source type");
    }
    
    private void assertNoRawDocumentMetadata() {
        if (documentId != null || segmentId != null) throw new BusinessValidationException("documentId and segmentId not allowed for this source type");
    }
}
