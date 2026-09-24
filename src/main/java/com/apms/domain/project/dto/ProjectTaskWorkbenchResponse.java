package com.apms.domain.project.dto;

import com.apms.common.enums.ProjectStatus;
import com.apms.common.enums.ProjectType;
import com.apms.common.enums.RelationshipType;
import com.apms.common.enums.TaskAction;
import com.apms.common.enums.TaskStatus;
import com.apms.common.enums.TaskType;
import com.apms.domain.document.dto.ImportJobResponse;
import com.apms.domain.financial.dto.FinancialResearchResponse;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Read-only aggregate response for the task detail workbench screen.
 * Lists all drafts generated for this task as reviewable alternatives.
 * Staff chooses one draft for submission; this endpoint never mutates data.
 */
@Data
@Builder
public class ProjectTaskWorkbenchResponse {
    private Long projectId;
    private Long taskId;
    private String taskTitle;
    private TaskType taskType;
    private TaskStatus taskStatus;

    private ProjectType projectType;
    private ProjectStatus projectStatus;
    private String targetCompanyName;
    private String targetCompanyProfileId;
    private RelationshipType targetRelationshipType;

    private List<TaskAction> availableActions;

    private List<WorkbenchDocumentResponse> documents;
    /** All candidate drafts generated for this task — staff-created from selected extractions */
    private List<CandidateDraftSummary> candidateDrafts;
    /** All proposal drafts generated for this task — staff-created from selected extractions */
    private List<ProposalDraftSummary> profileUpdateProposalDrafts;
    private List<ProjectTaskSubmissionResponse> submissions;
    private FinancialResearchResponse financialResearch;
}

