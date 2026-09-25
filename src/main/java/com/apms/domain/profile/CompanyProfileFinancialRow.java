package com.apms.domain.profile;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Document(collection = "company_profile_financial_rows")
@CompoundIndexes({
    @CompoundIndex(name = "profile_source_metric_idx", def = "{'companyProfileId': 1, 'sourceMetricId': 1}"),
    @CompoundIndex(name = "profile_year_quarter_idx", def = "{'companyProfileId': 1, 'year': -1, 'quarter': -1}")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompanyProfileFinancialRow {

    @Id
    private String id;

    @Indexed
    private String companyProfileId;

    private String metricName;
    private String normalizedKey;
    private BigDecimal value;
    private String unit;
    private Integer year;
    private String quarter;
    private Integer displayOrder;

    /**
     * PROMOTED (from approved FinancialResearch) or MANUAL (created directly by Manager).
     */
    private String sourceType;

    // Provenance references (populated for PROMOTED rows, null for MANUAL rows)
    private String sourceResearchId;
    private String sourceReportId;
    private String sourceReportTitle;
    private String sourceDocumentId;
    private Integer sourcePage;
    private String sourceMetricId;
    private String publicationDate;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long lastModifiedBy;
}
