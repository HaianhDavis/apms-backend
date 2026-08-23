package com.apms.domain.project.service;

import com.apms.common.enums.ProjectStatus;
import com.apms.common.enums.TaskStatus;
import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.project.ProjectKeyResult;
import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.dto.ProjectKeyResultResponse;
import com.apms.domain.project.dto.ProjectResponse;
import com.apms.domain.project.dto.UpdateProjectStatusRequest;
import com.apms.domain.project.repository.sql.ProjectKeyResultRepository;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.apms.domain.project.repository.sql.ProjectTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class ProjectOkrProgressTest {

    @Mock
    private ProjectRepository projectRepository;
    
    @Mock
    private ProjectKeyResultRepository projectKeyResultRepository;
    
    @Mock
    private ProjectTaskRepository projectTaskRepository;

    @Mock
    private com.apms.domain.project.repository.sql.ProjectMemberRepository projectMemberRepository;
    
    @Mock
    private com.apms.domain.user.repository.sql.AccountRepository accountRepository;
    
    @Mock
    private com.apms.domain.user.repository.sql.UserProfileRepository userProfileRepository;
    
    @Mock
    private com.apms.domain.audit.service.AuditLogService auditLogService;

    @InjectMocks
    private ProjectService projectService;
    
    private com.apms.domain.project.Project project;

    @BeforeEach
    void setUp() {
        project = new com.apms.domain.project.Project();
        project.setId(100L);
        project.setProjectName("OKR Progress Test Project");
        project.setStatus(ProjectStatus.ACTIVE);
    }

    private ProjectKeyResult createKr(Long id, int weight) {
        ProjectKeyResult kr = new ProjectKeyResult();
        kr.setId(id);
        kr.setWeight(weight);
        return kr;
    }

    private ProjectTask createTask(Long id, TaskStatus status, ProjectKeyResult kr) {
        ProjectTask task = new ProjectTask();
        task.setId(id);
        task.setStatus(status);
        task.setKeyResult(kr);
        return task;
    }

    @Test
    void newOkrProjectStartsAtZeroProgress() {
        // Scenario 1: Four KRs, all tasks AVAILABLE -> progress 0
        ProjectKeyResult kr1 = createKr(1L, 25);
        ProjectKeyResult kr2 = createKr(2L, 25);
        ProjectKeyResult kr3 = createKr(3L, 25);
        ProjectKeyResult kr4 = createKr(4L, 25);
        
        List<ProjectKeyResult> krs = List.of(kr1, kr2, kr3, kr4);
        List<ProjectTask> tasks = List.of(
            createTask(10L, TaskStatus.AVAILABLE, kr1),
            createTask(11L, TaskStatus.AVAILABLE, kr2),
            createTask(12L, TaskStatus.AVAILABLE, kr3),
            createTask(13L, TaskStatus.AVAILABLE, kr4)
        );
        
        when(projectRepository.findById(100L)).thenReturn(Optional.of(project));
        when(projectKeyResultRepository.findByProject_Id(100L)).thenReturn(krs);
        when(projectTaskRepository.findByProject_Id(100L)).thenReturn(tasks);
        when(projectMemberRepository.findByProject_Id(100L)).thenReturn(List.of());
        when(projectTaskRepository.getProjectTaskStatsIn(any(), any(), any())).thenReturn(List.of());

        ProjectResponse response = projectService.getProjectById(100L);
        
        assertEquals(0, response.getProgressPercentage());
        assertEquals(4, response.getKeyResults().size());
        assertTrue(response.getKeyResults().stream().allMatch(r -> r.getProgress() == 0));
    }

    @Test
    void doneTaskCompletesSingleTaskKr() {
        // Scenario 2: KR has one Task, Task DONE -> KR progress 100
        ProjectKeyResult kr = createKr(1L, 100);
        when(projectRepository.findById(100L)).thenReturn(Optional.of(project));
        when(projectKeyResultRepository.findByProject_Id(100L)).thenReturn(List.of(kr));
        when(projectTaskRepository.findByProject_Id(100L)).thenReturn(List.of(createTask(10L, TaskStatus.DONE, kr)));
        when(projectMemberRepository.findByProject_Id(100L)).thenReturn(List.of());
        when(projectTaskRepository.getProjectTaskStatsIn(any(), any(), any())).thenReturn(List.of());

        ProjectResponse response = projectService.getProjectById(100L);
        assertEquals(100, response.getProgressPercentage());
        assertEquals(100, response.getKeyResults().get(0).getProgress());
    }

    @Test
    void incompleteTasksDoNotCompleteKr() {
        // Scenarios 3, 4, 5, 6
        ProjectKeyResult kr = createKr(1L, 100);
        when(projectRepository.findById(100L)).thenReturn(Optional.of(project));
        when(projectKeyResultRepository.findByProject_Id(100L)).thenReturn(List.of(kr));
        when(projectMemberRepository.findByProject_Id(100L)).thenReturn(List.of());
        
        TaskStatus[] statuses = {TaskStatus.AVAILABLE, TaskStatus.IN_PROGRESS, TaskStatus.IN_REVIEW, TaskStatus.CANCELLED};
        for (TaskStatus status : statuses) {
            when(projectTaskRepository.findByProject_Id(100L)).thenReturn(List.of(createTask(10L, status, kr)));
            ProjectResponse response = projectService.getProjectById(100L);
            assertEquals(0, response.getProgressPercentage(), "Failed for status: " + status);
        }
    }

    @Test
    void krWithNoTasksIsNotCompleted() {
        // Scenario 7: krWithNoTasksIsNotCompleted
        ProjectKeyResult kr = createKr(1L, 100);
        when(projectRepository.findById(100L)).thenReturn(Optional.of(project));
        when(projectKeyResultRepository.findByProject_Id(100L)).thenReturn(List.of(kr));
        when(projectTaskRepository.findByProject_Id(100L)).thenReturn(List.of()); // No tasks
        when(projectMemberRepository.findByProject_Id(100L)).thenReturn(List.of());
        
        ProjectResponse response = projectService.getProjectById(100L);
        assertEquals(0, response.getProgressPercentage());
    }

    @Test
    void krWithMultipleTasksRequiresAllDone() {
        // Scenario 8: DONE + IN_PROGRESS -> 0, then DONE + DONE -> 100
        ProjectKeyResult kr = createKr(1L, 100);
        when(projectRepository.findById(100L)).thenReturn(Optional.of(project));
        when(projectKeyResultRepository.findByProject_Id(100L)).thenReturn(List.of(kr));
        when(projectMemberRepository.findByProject_Id(100L)).thenReturn(List.of());
        
        when(projectTaskRepository.findByProject_Id(100L)).thenReturn(List.of(
            createTask(10L, TaskStatus.DONE, kr),
            createTask(11L, TaskStatus.IN_PROGRESS, kr)
        ));
        assertEquals(0, projectService.getProjectById(100L).getProgressPercentage());

        when(projectTaskRepository.findByProject_Id(100L)).thenReturn(List.of(
            createTask(10L, TaskStatus.DONE, kr),
            createTask(11L, TaskStatus.DONE, kr)
        ));
        assertEquals(100, projectService.getProjectById(100L).getProgressPercentage());
    }

    @Test
    void projectProgressUsesCompletedKrWeights() {
        // Scenario 9: 30 DONE, 25 DONE, 20 IN_PROGRESS, 25 AVAILABLE -> 55
        ProjectKeyResult kr1 = createKr(1L, 30);
        ProjectKeyResult kr2 = createKr(2L, 25);
        ProjectKeyResult kr3 = createKr(3L, 20);
        ProjectKeyResult kr4 = createKr(4L, 25);
        
        when(projectRepository.findById(100L)).thenReturn(Optional.of(project));
        when(projectKeyResultRepository.findByProject_Id(100L)).thenReturn(List.of(kr1, kr2, kr3, kr4));
        when(projectTaskRepository.findByProject_Id(100L)).thenReturn(List.of(
            createTask(10L, TaskStatus.DONE, kr1),
            createTask(11L, TaskStatus.DONE, kr2),
            createTask(12L, TaskStatus.IN_PROGRESS, kr3),
            createTask(13L, TaskStatus.AVAILABLE, kr4)
        ));
        when(projectMemberRepository.findByProject_Id(100L)).thenReturn(List.of());
        
        ProjectResponse response = projectService.getProjectById(100L);
        assertEquals(55, response.getProgressPercentage());
    }
    
    @Test
    void projectProgressDoesNotUseTaskType() {
        // Scenario 10
        ProjectKeyResult kr1 = createKr(1L, 40);
        ProjectKeyResult kr2 = createKr(2L, 60);
        ProjectTask task1 = createTask(10L, TaskStatus.DONE, kr1);
        task1.setTaskType(com.apms.common.enums.TaskType.COMPANY_DATA_PREPARATION);
        ProjectTask task2 = createTask(11L, TaskStatus.IN_PROGRESS, kr2);
        task2.setTaskType(com.apms.common.enums.TaskType.COMPANY_DATA_PREPARATION);

        when(projectRepository.findById(100L)).thenReturn(Optional.of(project));
        when(projectKeyResultRepository.findByProject_Id(100L)).thenReturn(List.of(kr1, kr2));
        when(projectTaskRepository.findByProject_Id(100L)).thenReturn(List.of(task1, task2));
        when(projectMemberRepository.findByProject_Id(100L)).thenReturn(List.of());
        
        ProjectResponse response = projectService.getProjectById(100L);
        assertEquals(40, response.getProgressPercentage());
    }

    @Test
    void repeatedProgressCalculationDoesNotDoubleCount() {
        // Scenario 11
        ProjectKeyResult kr = createKr(1L, 100);
        when(projectRepository.findById(100L)).thenReturn(Optional.of(project));
        when(projectKeyResultRepository.findByProject_Id(100L)).thenReturn(List.of(kr));
        when(projectTaskRepository.findByProject_Id(100L)).thenReturn(List.of(createTask(10L, TaskStatus.DONE, kr)));
        when(projectMemberRepository.findByProject_Id(100L)).thenReturn(List.of());
        
        assertEquals(100, projectService.getProjectById(100L).getProgressPercentage());
        assertEquals(100, projectService.getProjectById(100L).getProgressPercentage()); // Same result
    }

    @Test
    void allKrsCompletedProduces100Progress() {
        // Scenario 13
        ProjectKeyResult kr1 = createKr(1L, 50);
        ProjectKeyResult kr2 = createKr(2L, 50);
        when(projectRepository.findById(100L)).thenReturn(Optional.of(project));
        when(projectKeyResultRepository.findByProject_Id(100L)).thenReturn(List.of(kr1, kr2));
        when(projectTaskRepository.findByProject_Id(100L)).thenReturn(List.of(
            createTask(10L, TaskStatus.DONE, kr1),
            createTask(11L, TaskStatus.DONE, kr2)
        ));
        when(projectMemberRepository.findByProject_Id(100L)).thenReturn(List.of());
        
        assertEquals(100, projectService.getProjectById(100L).getProgressPercentage());
    }

    @Test
    void legacyProjectKeepsExistingProgressBehavior() {
        // Scenario 14
        when(projectRepository.findById(100L)).thenReturn(Optional.of(project));
        when(projectKeyResultRepository.findByProject_Id(100L)).thenReturn(List.of()); // No KRs
        when(projectMemberRepository.findByProject_Id(100L)).thenReturn(List.of());
        
        ProjectTaskRepository.ProjectTaskStats stats = mock(ProjectTaskRepository.ProjectTaskStats.class);
        when(stats.getTotalTasks()).thenReturn(4L);
        when(stats.getCompletedTasks()).thenReturn(1L);
        
        when(projectTaskRepository.getProjectTaskStatsIn(List.of(100L), TaskStatus.DONE, TaskStatus.CANCELLED))
            .thenReturn(List.of(stats));
            
        assertEquals(25, projectService.getProjectById(100L).getProgressPercentage());
    }

    @Test
    void legacyTaskWithoutKeyResultDoesNotAffectOkrProgress() {
        // Scenario 15
        ProjectKeyResult kr = createKr(1L, 100);
        ProjectTask legacyTask = createTask(10L, TaskStatus.DONE, null);
        ProjectTask okrTask = createTask(11L, TaskStatus.IN_PROGRESS, kr);
        
        when(projectRepository.findById(100L)).thenReturn(Optional.of(project));
        when(projectKeyResultRepository.findByProject_Id(100L)).thenReturn(List.of(kr));
        when(projectTaskRepository.findByProject_Id(100L)).thenReturn(List.of(legacyTask, okrTask));
        when(projectMemberRepository.findByProject_Id(100L)).thenReturn(List.of());
        
        ProjectResponse response = projectService.getProjectById(100L);
        assertEquals(0, response.getProgressPercentage()); // Legacy task does not bump it to 100
    }

    @Test
    void okrProjectCannotBeCompletedAt55Percent() {
        // Scenario 16
        UpdateProjectStatusRequest req = new UpdateProjectStatusRequest();
        req.setStatus(ProjectStatus.COMPLETED);
        
        ProjectKeyResult kr1 = createKr(1L, 55);
        ProjectKeyResult kr2 = createKr(2L, 45);
        
        when(projectRepository.findById(100L)).thenReturn(Optional.of(project));
        when(projectTaskRepository.countByProjectIdAndStatusIn(any(), any())).thenReturn(0);
        when(projectKeyResultRepository.findByProject_Id(100L)).thenReturn(List.of(kr1, kr2));
        when(projectTaskRepository.findByProject_Id(100L)).thenReturn(List.of(
            createTask(10L, TaskStatus.DONE, kr1),
            createTask(11L, TaskStatus.IN_PROGRESS, kr2) // Missing completion
        ));
        
        BusinessValidationException ex = assertThrows(BusinessValidationException.class, 
            () -> projectService.updateProjectStatus(100L, req, 1L));
        assertTrue(ex.getMessage().contains("OKR progress is 100%"));
    }

    @Test
    void okrProjectCanBeCompletedAt100Percent() {
        // Scenario 17
        UpdateProjectStatusRequest req = new UpdateProjectStatusRequest();
        req.setStatus(ProjectStatus.COMPLETED);
        
        ProjectKeyResult kr1 = createKr(1L, 100);
        
        when(projectRepository.findById(100L)).thenReturn(Optional.of(project));
        when(projectTaskRepository.countByProjectIdAndStatusIn(any(), any())).thenReturn(0);
        when(projectKeyResultRepository.findByProject_Id(100L)).thenReturn(List.of(kr1));
        when(projectTaskRepository.findByProject_Id(100L)).thenReturn(List.of(createTask(10L, TaskStatus.DONE, kr1)));
        when(projectMemberRepository.findByProject_Id(100L)).thenReturn(List.of());
        when(projectRepository.save(any())).thenReturn(project);
        
        ProjectResponse response = projectService.updateProjectStatus(100L, req, 1L);
        assertNotNull(response);
    }
}
