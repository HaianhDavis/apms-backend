package com.apms.domain.project.dto;

import com.apms.common.enums.ProjectStatus;
import com.apms.common.enums.RelationshipType;
import com.apms.common.enums.ProjectType;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.List;

@Data
@Builder
public class ProjectResponse {

    private Long id;
    private String projectName;
    private ProjectType projectType;
    private String targetCompanyProfileId;
    private String targetCompanyName;
    private String targetCompanyTaxCode;
    private RelationshipType targetRelationshipType;
    private RelationshipType currentRelationshipType;
    private RelationshipType originalRelationshipType;
    private String description;
    private String objective;
    private ProjectStatus status;
    private List<ProjectKeyResultResponse> keyResults;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDate plannedEndDate;
    private Long managerId;
    private String managerName;
    private Integer totalTasks;
    private Integer completedTasks;
    private Integer progressPercentage;
    private Boolean isOverdue;
    private List<ProjectMemberResponse> members;

    private LocalDateTime closedAt;
    private String closeReason;
    private Long closedBy;
}
