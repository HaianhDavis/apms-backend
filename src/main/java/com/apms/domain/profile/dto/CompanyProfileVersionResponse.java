package com.apms.domain.profile.dto;

import com.apms.domain.profile.enums.CompanyProfileChangeSource;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompanyProfileVersionResponse {
    private String id;
    private String companyProfileId;
    private String companyId;
    private Integer majorVersion;
    private Integer revision;
    private String version;
    private String versionLabel;
    private CompanyProfileChangeSource changeSource;
    private List<String> changedFieldPaths;
    private Map<String, Object> beforeValues;
    private Map<String, Object> afterValues;
    private String changeNote;
    private Map<String, Object> snapshot;
    private String createdFromProposalId;
    private Long createdFromProjectId;
    private String projectName;
    private Long createdFromTaskId;
    private List<String> sourceDocumentIds;
    private String changeSummary;
    private Long createdBy;
    private String createdByName;
    private LocalDateTime createdAt;
}
