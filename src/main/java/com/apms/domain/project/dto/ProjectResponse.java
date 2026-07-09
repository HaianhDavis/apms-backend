package com.apms.domain.project.dto;

import com.apms.common.enums.ProjectStatus;
import com.apms.common.enums.RelationshipType;
import com.apms.common.enums.ProjectType;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class ProjectResponse {

    private Long id;
    private String projectName;
    private ProjectType projectType;
    private String targetCompanyProfileId;
    private String targetCompanyName;
    private RelationshipType targetRelationshipType;
    private String description;
    private ProjectStatus status;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<ProjectMemberResponse> members;
}
