package com.apms.domain.project.dto;

import com.apms.common.enums.ProjectStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OpenProjectCheckResponse {
    private boolean hasOpenProject;
    private Long projectId;
    private String projectName;
    private ProjectStatus status;
    private String companyName;
}
