package com.apms.domain.companymember.dto;

import com.apms.domain.companymember.CompanyMemberResearchItem;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class CompanyMemberResearchDraftResponse {
    private String id;
    private Long projectId;
    private Long taskId;
    private String companyProfileId;
    private Long createdByAccountId;
    private Long submissionId;
    private List<CompanyMemberResearchItem> members;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
