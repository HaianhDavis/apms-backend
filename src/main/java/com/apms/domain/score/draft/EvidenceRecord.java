package com.apms.domain.score.draft;

import com.apms.domain.score.enums.EvidenceReliability;
import com.apms.domain.score.enums.EvidenceSourceType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvidenceRecord {

    private String evidenceId;

    private String criterionKey;

    private EvidenceSourceType sourceType;

    private String rawDocumentId;

    // Original stable business ID
    private String companyId;

    // Exact profile document _id used for provenance
    private String profileDocumentId;

    private Integer profileVersion;

    private String externalUrl;

    private LocalDate evidenceDate;

    private String extractedFieldPath;

    private String evidenceCategory;

    private EvidenceReliability reliability;

    private Long preparedByAccountId;
    private LocalDateTime preparedAt;

    private Long reviewedByAccountId;
    private LocalDateTime reviewedAt;

    private String note;

    // Structured Threat Evidence
    private String eventType;
    private String referenceCompanyId;
    private String targetCompanyId;
    private String directCompetitiveClaim;

    private java.util.Map<String, Object> eventDetails;
}
