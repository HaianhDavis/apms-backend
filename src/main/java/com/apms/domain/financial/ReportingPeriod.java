package com.apms.domain.financial;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportingPeriod {
    private Integer year;
    private ReportingPeriodType periodType;
    private String period;     // Q1, Q2, H1, H2, FY, 9M, etc.
    private String asOfDate;   // YYYY-MM-DD for AS_OF_DATE type
}
