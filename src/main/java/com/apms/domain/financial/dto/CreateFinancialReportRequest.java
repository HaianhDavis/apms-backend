package com.apms.domain.financial.dto;

import com.apms.domain.financial.ReportType;
import com.apms.domain.financial.ReportingPeriod;
import com.apms.domain.financial.StatementScope;
import lombok.Data;

import java.time.LocalDate;

@Data
public class CreateFinancialReportRequest {
    private String title;
    private LocalDate publicationDate;
    private ReportingPeriod reportingPeriod;
    private String documentId;
    private ReportType reportType;
    private StatementScope statementScope;
}
