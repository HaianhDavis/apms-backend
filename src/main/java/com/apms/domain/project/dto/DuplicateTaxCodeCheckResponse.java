package com.apms.domain.project.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DuplicateTaxCodeCheckResponse {
    private boolean exists;
    private String matchType; // "COMPANY_PROFILE" or "OPEN_RESEARCH_PROJECT" or "ACTIVE_PROJECT" or null
    private String companyProfileId;
    private Long projectId;
    private String companyName;
    private String taxCode;
    private boolean hasOpenProject;
    private Long openProjectId;
    private String openProjectName;
    private com.apms.common.enums.ProjectStatus openProjectStatus;
    private boolean existingOfficialCompany;
    private boolean openResearchProject;
    private Boolean canCurrentManagerManage;
}
