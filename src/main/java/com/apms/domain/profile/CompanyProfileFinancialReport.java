package com.apms.domain.profile;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompanyProfileFinancialReport {
    private String reportType;
    private String periodType;
    private Integer reportYear;
    private String reportPeriod;
    private String itemsJson;
    private String sourceUrl;
}
