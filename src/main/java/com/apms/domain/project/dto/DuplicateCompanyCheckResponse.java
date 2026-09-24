package com.apms.domain.project.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class DuplicateCompanyCheckResponse {

    private boolean duplicate;
    private List<MatchingProject> matchingProjects;

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class MatchingProject {
        private Long id;
        private String projectName;
        private String targetCompanyName;
        private String status;
        private String projectType;
    }
}
