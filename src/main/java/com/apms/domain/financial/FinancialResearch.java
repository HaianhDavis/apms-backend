package com.apms.domain.financial;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Document(collection = "financial_researches")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FinancialResearch {
    @Id
    private String id;

    @Indexed(unique = true)
    private Long taskId;

    private Long projectId;
    private String companyProfileId;

    private ReportingPeriod targetResearchPeriod;

    @Builder.Default
    private List<FinancialReportEntry> reports = new ArrayList<>();

    @Builder.Default
    private List<FinancialMetric> metrics = new ArrayList<>();

    @Builder.Default
    private FinancialResearchStatus status = FinancialResearchStatus.DRAFT;

    @Builder.Default
    private List<String> submittedReportIds = new ArrayList<>();

    private LocalDateTime submittedAt;
    private Long reviewedBy;
    private LocalDateTime reviewedAt;
    private String reviewReason;

    @CreatedDate
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;
}
