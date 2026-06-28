package com.apms.domain.report.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class CompanyReportItemResponse {
    private String companyProfileId;
    private String companyId;
    private String name;
    private List<String> industries;
    private List<String> markets;
    private List<String> relationshipTypes;
    private Integer partnerFitScore;
    private Integer competitionScore;
    private Integer riskScore;
    private Integer relationshipScore;
    private LocalDateTime lastScoreDate;
}
