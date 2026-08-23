package com.apms.domain.project.service;

import com.apms.common.enums.TaskStatus;
import com.apms.common.enums.TaskType;
import com.apms.domain.project.ProjectKeyResult;
import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.repository.sql.ProjectTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TaskGeneratorService {

    private final ProjectTaskRepository projectTaskRepository;
    private final com.apms.domain.audit.service.AuditLogService auditLogService;

    public List<ProjectTask> generateTasksForProject(List<ProjectKeyResult> keyResults, Long actorId) {
        if (keyResults == null || keyResults.isEmpty()) {
            return List.of();
        }

        List<ProjectTask> generatedTasks = keyResults.stream()
                .filter(kr -> kr.getType() != null)
                .map(this::generateTaskForKr)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());

        if (!generatedTasks.isEmpty()) {
            projectTaskRepository.saveAll(generatedTasks);
            for (ProjectTask task : generatedTasks) {
                auditLogService.log(actorId, com.apms.common.enums.AuditAction.PROJECT_TASK_CREATED, "ProjectTask", String.valueOf(task.getId()), "Task automatically generated from KR " + task.getKeyResult().getType().name());
            }
            log.info("Generated {} tasks for {} key results in project {}", generatedTasks.size(), keyResults.size(), keyResults.get(0).getProject().getId());
        }

        return generatedTasks;
    }

    private ProjectTask generateTaskForKr(ProjectKeyResult kr) {
        String title;
        String description;
        TaskType taskType;

        switch (kr.getType()) {
            case BASIC_COMPANY_INFORMATION:
                title = "Research Basic Company Information";
                description = "Collect and prepare the required basic information for the target company.";
                taskType = TaskType.COMPANY_DATA_PREPARATION;
                break;
            case FINANCIAL_INFORMATION:
                title = "Research Financial Information";
                description = "Collect and verify available financial information for the target company.";
                taskType = TaskType.COMPANY_DATA_PREPARATION;
                break;
            case MANAGEMENT_MEMBERS:
                title = "Research Company Management Members";
                description = "Collect and verify the target company's management/key member information.";
                taskType = TaskType.COMPANY_MEMBER_RESEARCH;
                break;
            case CONTRACT_INFORMATION:
                title = "Collect Contract Information";
                description = "Collect relevant contract information between the organization and target company.";
                taskType = TaskType.PARTNER_CONTRACT_COLLECTION;
                break;
            default:
                log.warn("No task mapping defined for ProjectKeyResultType: {}", kr.getType());
                return null;
        }

        return ProjectTask.builder()
                .project(kr.getProject())
                .keyResult(kr)
                .taskType(taskType)
                .title(title)
                .description(description)
                .status(TaskStatus.AVAILABLE)
                .assignedToAccount(null)
                .createdByAccount(kr.getProject().getCreatedByAccount())
                .targetCompanyProfileId(kr.getProject().getTargetCompanyProfileId())
                .dueDate(kr.getProject().getPlannedEndDate() != null ? kr.getProject().getPlannedEndDate().atTime(23, 59, 59) : null)
                .priority(com.apms.common.enums.TaskPriority.MEDIUM)
                .build();
    }
}
