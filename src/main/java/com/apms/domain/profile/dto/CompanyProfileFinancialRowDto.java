package com.apms.domain.profile.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompanyProfileFinancialRowDto {
    private String id;
    private String companyProfileId;
    private String metricName;
    private String normalizedKey;
    private BigDecimal value;
    private String unit;
    private Integer year;
    private String quarter;
    private String sourceType;
    private Integer displayOrder;

    // Provenance references
    private String sourceResearchId;
    private String sourceReportId;
    private String sourceReportTitle;
    private String sourceDocumentId;
    private Integer sourcePage;
    private String sourceMetricId;
    private String publicationDate;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
