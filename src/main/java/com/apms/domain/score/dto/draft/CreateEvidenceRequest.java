package com.apms.domain.score.dto.draft;

import com.apms.domain.score.enums.EvidenceReliability;
import com.apms.domain.score.enums.EvidenceSourceType;
import lombok.Data;

import java.time.LocalDate;

@Data
public class CreateEvidenceRequest {
    private String criterionKey;
    private EvidenceSourceType sourceType;
    
    private String rawDocumentId;
    private String companyId;
    private String profileDocumentId;
    private Integer profileVersion;
    
    private String externalUrl;
    private LocalDate evidenceDate;
    
    private String extractedFieldPath;
    private String evidenceCategory;
    private EvidenceReliability reliability;
    
    private String note;
    
    // For growthMomentumScore
    private LocalDate periodStart;
    private LocalDate periodEnd;
    private String metricName;
    private String metricUnit;
}
