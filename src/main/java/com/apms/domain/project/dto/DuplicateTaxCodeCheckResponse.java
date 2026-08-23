package com.apms.domain.project.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DuplicateTaxCodeCheckResponse {
    private boolean exists;
    private String matchType; // "COMPANY_PROFILE" or "ACTIVE_PROJECT" or null
    private String companyProfileId;
    private Long projectId;
    private String companyName;
    private String taxCode;
}
