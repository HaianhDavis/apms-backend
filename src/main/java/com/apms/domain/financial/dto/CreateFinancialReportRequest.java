package com.apms.domain.financial.dto;

import com.apms.domain.financial.FinancialDataEntryMethod;
import com.apms.domain.financial.ReportType;
import com.apms.domain.financial.ReportingPeriod;
import com.apms.domain.financial.StatementScope;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateFinancialReportRequest {
    private String title;
    private FinancialDataEntryMethod dataEntryMethod;
    private LocalDate publicationDate;
    private ReportingPeriod reportingPeriod;
    private String documentId;
    private ReportType reportType;
    private StatementScope statementScope;
}
