package com.apms.domain.profile.dto;

import lombok.Builder;
import lombok.Data;

import java.util.Set;

@Data
@Builder
public class ProfileSourcesResponse {
    private String companyId;
    private Set<String> projectIds;
    private Set<String> importJobIds;
    private Set<String> rawDocumentIds;
    private Set<String> candidateIds;
}
