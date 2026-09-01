package com.apms.domain.financial;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentContext {
    private String documentId;
    private String documentName;

    // AI-detected fields
    private String companyName;
    private String reportType;
    private Integer year;
    private ReportingPeriodType periodType;
    private String period;
    private String asOfDate;
    private String currency;
    private String scale;
    private String statementScope;
    private String industryContext;

    // System-controlled validation
    private DocumentCompanyValidationStatus companyValidation;
    @Builder.Default
    private Boolean companyVerifiedByStaff = false;

    private DocumentPeriodValidationStatus periodValidation;
    @Builder.Default
    private Boolean periodVerifiedByStaff = false;
}
