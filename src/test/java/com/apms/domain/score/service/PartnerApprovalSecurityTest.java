package com.apms.domain.score.service;

import com.apms.domain.company.enums.CompanyRole;
import com.apms.domain.project.Project;
import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.apms.domain.project.repository.sql.ProjectTaskRepository;
import com.apms.domain.score.draft.RoleEvaluationDraft;
import com.apms.domain.score.repository.mongo.RoleEvaluationDraftRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class PartnerApprovalSecurityTest {

    @Mock
    private RoleEvaluationDraftRepository draftRepository;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private ProjectTaskRepository taskRepository;

    @InjectMocks
    private RoleEvaluationSecurityService securityService;

    private RoleEvaluationDraft draft;
    private Project project;
    private ProjectTask task;

    @BeforeEach
    void setUp() {
        draft = new RoleEvaluationDraft();
        draft.setId("draft-1");
        draft.setProjectId(100L);
        draft.setTaskId(200L);
        draft.setEvaluatedRole(CompanyRole.PARTNER);
        draft.setTargetProfileDocumentId("target-1");

        project = new Project();
        project.setId(100L);
        project.setTargetCompanyProfileId("target-1");

        task = new ProjectTask();
        task.setId(200L);
        task.setProject(project);
    }

    @Test
    void testCanAccessDraft_Success() {
        when(draftRepository.findById("draft-1")).thenReturn(Optional.of(draft));
        when(projectRepository.findById(100L)).thenReturn(Optional.of(project));
        when(taskRepository.findById(200L)).thenReturn(Optional.of(task));

        assertTrue(securityService.canAccessDraft("draft-1", 1L));
    }

    @Test
    void testCanAccessDraft_ThrowsIfProjectTaskAlignmentFails() {
        Project wrongProject = new Project();
        wrongProject.setId(999L);
        task.setProject(wrongProject);

        when(draftRepository.findById("draft-1")).thenReturn(Optional.of(draft));
        when(projectRepository.findById(100L)).thenReturn(Optional.of(project));
        when(taskRepository.findById(200L)).thenReturn(Optional.of(task));

        SecurityException ex = assertThrows(SecurityException.class, () -> securityService.canAccessDraft("draft-1", 1L));
        assertTrue(ex.getMessage().contains("task/project alignment failed"));
    }

    @Test
    void testCanAccessDraft_ThrowsIfEvaluationProjectAlignmentFails() {
        draft.setProjectId(999L);

        when(draftRepository.findById("draft-1")).thenReturn(Optional.of(draft));

        Project foundProject = new Project();
        foundProject.setId(100L);
        when(projectRepository.findById(999L)).thenReturn(Optional.of(foundProject));
        when(taskRepository.findById(200L)).thenReturn(Optional.of(task));

        SecurityException ex = assertThrows(SecurityException.class, () -> securityService.canAccessDraft("draft-1", 1L));
        assertTrue(ex.getMessage().contains("evaluation/project alignment failed"));
    }

    @Test
    void testCanAccessDraft_ThrowsIfEvaluationTaskAlignmentFails() {
        draft.setTaskId(999L);

        when(draftRepository.findById("draft-1")).thenReturn(Optional.of(draft));
        when(projectRepository.findById(100L)).thenReturn(Optional.of(project));

        ProjectTask foundTask = new ProjectTask();
        foundTask.setId(200L); // Mismatched ID
        foundTask.setProject(project);
        when(taskRepository.findById(999L)).thenReturn(Optional.of(foundTask));

        SecurityException ex = assertThrows(SecurityException.class, () -> securityService.canAccessDraft("draft-1", 1L));
        assertTrue(ex.getMessage().contains("evaluation/task alignment failed"));
    }

    @Test
    void testCanAccessDraft_ThrowsIfTargetCompanyAlignmentFails() {
        project.setTargetCompanyProfileId("wrong-target");

        when(draftRepository.findById("draft-1")).thenReturn(Optional.of(draft));
        when(projectRepository.findById(100L)).thenReturn(Optional.of(project));
        when(taskRepository.findById(200L)).thenReturn(Optional.of(task));

        SecurityException ex = assertThrows(SecurityException.class, () -> securityService.canAccessDraft("draft-1", 1L));
        assertTrue(ex.getMessage().contains("target company alignment failed"));
    }

    @Test
    void testCanAccessDraft_ThrowsIfAssignedStaffNull() {
        when(draftRepository.findById("draft-1")).thenReturn(Optional.of(draft));
        when(projectRepository.findById(100L)).thenReturn(Optional.of(project));
        when(taskRepository.findById(200L)).thenReturn(Optional.of(task));

        SecurityException ex = assertThrows(SecurityException.class, () -> securityService.canAccessDraft("draft-1", null));
        assertTrue(ex.getMessage().contains("assigned Staff identity failed"));
    }
}
