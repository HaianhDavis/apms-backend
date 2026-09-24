package com.apms.domain.project.service;

import com.apms.common.enums.TaskStatus;
import com.apms.common.enums.TaskType;
import com.apms.domain.project.Project;
import com.apms.domain.project.ProjectKeyResult;
import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.repository.sql.ProjectTaskRepository;
import com.apms.common.enums.ProjectKeyResultType;
import com.apms.domain.user.Account;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class TaskGeneratorServiceTest {

    @Mock
    private ProjectTaskRepository projectTaskRepository;

    @Mock
    private com.apms.domain.audit.service.AuditLogService auditLogService;

    @InjectMocks
    private TaskGeneratorService taskGeneratorService;

    private Project project;
    private Account creator;

    @BeforeEach
    void setUp() {
        creator = new Account();
        creator.setId(10L);

        project = Project.builder()
                .id(100L)
                .createdByAccount(creator)
                .targetCompanyProfileId("company123")
                .plannedEndDate(LocalDate.now().plusDays(30))
                .build();
    }

    @Test
    void createOkrProjectGeneratesOneTaskPerKeyResult() {
        ProjectKeyResult kr1 = ProjectKeyResult.builder().project(project).type(ProjectKeyResultType.BASIC_COMPANY_INFORMATION).build();
        ProjectKeyResult kr2 = ProjectKeyResult.builder().project(project).type(ProjectKeyResultType.FINANCIAL_INFORMATION).build();
        ProjectKeyResult kr3 = ProjectKeyResult.builder().project(project).type(ProjectKeyResultType.MANAGEMENT_MEMBERS).build();
        ProjectKeyResult kr4 = ProjectKeyResult.builder().project(project).type(ProjectKeyResultType.CONTRACT_INFORMATION).build();

        List<ProjectTask> generated = taskGeneratorService.generateTasksForProject(List.of(kr1, kr2, kr3, kr4), 10L);

        assertThat(generated).hasSize(4);
        verify(projectTaskRepository).saveAll(generated);
    }

    @Test
    void generatedOkrTaskUsesExistingCreateAudit() {
        ProjectKeyResult kr = ProjectKeyResult.builder().project(project).type(ProjectKeyResultType.BASIC_COMPANY_INFORMATION).build();
        List<ProjectTask> generated = taskGeneratorService.generateTasksForProject(List.of(kr), 10L);
        
        assertThat(generated).hasSize(1);
        verify(auditLogService).log(eq(10L), eq(com.apms.common.enums.AuditAction.PROJECT_TASK_CREATED), eq("ProjectTask"), anyString(), contains("Task automatically generated from KR BASIC_COMPANY_INFORMATION"));
    }

    @Test
    void generatedTaskStartsAvailableAndUnassigned() {
        ProjectKeyResult kr = ProjectKeyResult.builder().project(project).type(ProjectKeyResultType.BASIC_COMPANY_INFORMATION).build();

        List<ProjectTask> generated = taskGeneratorService.generateTasksForProject(List.of(kr), 10L);

        ProjectTask task = generated.get(0);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.AVAILABLE);
        assertThat(task.getAssignedToAccount()).isNull();
    }

    @Test
    void generatedTaskReferencesCorrectProjectAndKr() {
        ProjectKeyResult kr = ProjectKeyResult.builder().project(project).type(ProjectKeyResultType.BASIC_COMPANY_INFORMATION).build();

        List<ProjectTask> generated = taskGeneratorService.generateTasksForProject(List.of(kr), 10L);

        ProjectTask task = generated.get(0);
        assertThat(task.getProject()).isEqualTo(project);
        assertThat(task.getKeyResult()).isEqualTo(kr);
        assertThat(task.getTargetCompanyProfileId()).isEqualTo("company123");
        assertThat(task.getCreatedByAccount()).isEqualTo(creator);
        assertThat(task.getDueDate()).isNotNull();
    }

    @Test
    void basicCompanyInformationMapsToCorrectTaskType() {
        ProjectKeyResult kr = ProjectKeyResult.builder().project(project).type(ProjectKeyResultType.BASIC_COMPANY_INFORMATION).build();
        List<ProjectTask> generated = taskGeneratorService.generateTasksForProject(List.of(kr), 10L);
        
        assertThat(generated.get(0).getTaskType()).isEqualTo(TaskType.COMPANY_DATA_PREPARATION);
        assertThat(generated.get(0).getTitle()).isEqualTo("Research Basic Company Information");
    }

    @Test
    void financialInformationMapsToCorrectTaskType() {
        ProjectKeyResult kr = ProjectKeyResult.builder().project(project).type(ProjectKeyResultType.FINANCIAL_INFORMATION).build();
        List<ProjectTask> generated = taskGeneratorService.generateTasksForProject(List.of(kr), 10L);
        
        assertThat(generated.get(0).getTaskType()).isEqualTo(TaskType.COMPANY_DATA_PREPARATION);
        assertThat(generated.get(0).getTitle()).isEqualTo("Research Financial Information");
    }

    @Test
    void managementMembersMapsToCorrectTaskType() {
        ProjectKeyResult kr = ProjectKeyResult.builder().project(project).type(ProjectKeyResultType.MANAGEMENT_MEMBERS).build();
        List<ProjectTask> generated = taskGeneratorService.generateTasksForProject(List.of(kr), 10L);
        
        assertThat(generated.get(0).getTaskType()).isEqualTo(TaskType.COMPANY_MEMBER_RESEARCH);
    }

    @Test
    void contractInformationMapsToCorrectExistingTaskType() {
        ProjectKeyResult kr = ProjectKeyResult.builder().project(project).type(ProjectKeyResultType.CONTRACT_INFORMATION).build();
        List<ProjectTask> generated = taskGeneratorService.generateTasksForProject(List.of(kr), 10L);
        
        assertThat(generated.get(0).getTaskType()).isEqualTo(TaskType.PARTNER_CONTRACT_COLLECTION);
    }

    @Test
    void generatedTasksAreDistinctEvenWhenTwoKrsUseSameTaskType() {
        ProjectKeyResult kr1 = ProjectKeyResult.builder().project(project).type(ProjectKeyResultType.BASIC_COMPANY_INFORMATION).build();
        ProjectKeyResult kr2 = ProjectKeyResult.builder().project(project).type(ProjectKeyResultType.FINANCIAL_INFORMATION).build();

        List<ProjectTask> generated = taskGeneratorService.generateTasksForProject(List.of(kr1, kr2), 10L);

        assertThat(generated).hasSize(2);
        
        ProjectTask task1 = generated.get(0);
        ProjectTask task2 = generated.get(1);

        assertThat(task1.getTaskType()).isEqualTo(TaskType.COMPANY_DATA_PREPARATION);
        assertThat(task2.getTaskType()).isEqualTo(TaskType.COMPANY_DATA_PREPARATION);
        
        assertThat(task1.getTitle()).isNotEqualTo(task2.getTitle());
        assertThat(task1.getKeyResult()).isEqualTo(kr1);
        assertThat(task2.getKeyResult()).isEqualTo(kr2);
    }

    @Test
    void legacyProjectWithoutKrsGeneratesNoAutomaticTasks() {
        List<ProjectTask> generated = taskGeneratorService.generateTasksForProject(List.of(), 10L);
        assertThat(generated).isEmpty();
        verify(projectTaskRepository, never()).saveAll(any());
    }
}
