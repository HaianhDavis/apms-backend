package com.apms.domain.profile.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class CompanyProfileVersionResponse {
    private String id;
    private String companyProfileId;
    private String companyId;
    private Integer version;
    private Map<String, Object> snapshot;
    private String createdFromProposalId;
    private Long createdFromProjectId;
    private Long createdFromTaskId;
    private List<String> sourceDocumentIds;
    private String changeSummary;
    private Long createdBy;
    private LocalDateTime createdAt;
}
