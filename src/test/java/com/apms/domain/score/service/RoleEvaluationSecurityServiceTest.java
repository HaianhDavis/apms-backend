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
import org.mockito.Mockito;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RoleEvaluationSecurityServiceTest {

    private RoleEvaluationDraftRepository draftRepository;
    private ProjectRepository projectRepository;
    private ProjectTaskRepository taskRepository;
    private RoleEvaluationSecurityService securityService;

    @BeforeEach
    void setUp() {
        draftRepository = mock(RoleEvaluationDraftRepository.class);
        projectRepository = mock(ProjectRepository.class);
        taskRepository = mock(ProjectTaskRepository.class);
        securityService = new RoleEvaluationSecurityService(draftRepository, projectRepository, taskRepository);
    }

    @Test
    void testSecurityValidatesAlignment() {
        RoleEvaluationDraft draft = new RoleEvaluationDraft();
        draft.setId("draft1");
        draft.setProjectId(1L);
        draft.setTaskId(10L);
        draft.setEvaluatedRole(CompanyRole.PARTNER);
        draft.setTargetProfileDocumentId("prof1");

        Project project = new Project();
        project.setId(1L);
        project.setTargetCompanyProfileId("prof1");

        ProjectTask task = new ProjectTask();
        task.setId(10L);
        task.setProject(project);

        when(draftRepository.findById("draft1")).thenReturn(Optional.of(draft));
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(taskRepository.findById(10L)).thenReturn(Optional.of(task));

        assertTrue(securityService.canAccessDraft("draft1", 100L));
    }

    @Test
    void testSecurityRejectsTaskProjectMisalignment() {
        RoleEvaluationDraft draft = new RoleEvaluationDraft();
        draft.setId("draft1");
        draft.setProjectId(1L);
        draft.setTaskId(10L);
        draft.setEvaluatedRole(CompanyRole.PARTNER);
        draft.setTargetProfileDocumentId("prof1");

        Project project = new Project();
        project.setId(1L);
        project.setTargetCompanyProfileId("prof1");

        Project otherProject = new Project();
        otherProject.setId(2L);

        ProjectTask task = new ProjectTask();
        task.setId(10L);
        task.setProject(otherProject);

        when(draftRepository.findById("draft1")).thenReturn(Optional.of(draft));
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(taskRepository.findById(10L)).thenReturn(Optional.of(task));

        SecurityException ex = assertThrows(SecurityException.class, () -> securityService.canAccessDraft("draft1", 100L));
        assertTrue(ex.getMessage().contains("task/project alignment failed"));
    }
}
