package com.apms.domain.financial.dto;

import com.apms.domain.financial.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentContextResponse {
    private String documentId;
    private String documentName;
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
    private DocumentCompanyValidationStatus companyValidation;
    private Boolean companyVerifiedByStaff;
    private Long companyVerifiedByStaffId;
    private LocalDateTime companyVerifiedAt;
    private DocumentPeriodValidationStatus periodValidation;
    private Boolean periodVerifiedByStaff;
}
