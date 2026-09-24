package com.apms.domain.project.service;

import com.apms.common.enums.ProjectStatus;
import com.apms.common.enums.ProjectType;
import com.apms.common.enums.TaskStatus;
import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.project.Project;
import com.apms.domain.project.ProjectMember;
import com.apms.domain.project.dto.CreateProjectRequest;
import com.apms.domain.project.dto.ProjectResponse;
import com.apms.domain.project.dto.UpdateProjectRequest;
import com.apms.domain.project.dto.*;
import com.apms.domain.project.repository.sql.ProjectKeyResultRepository;
import com.apms.domain.project.repository.sql.ProjectMemberRepository;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.apms.domain.project.repository.sql.ProjectTaskRepository;
import com.apms.domain.user.Account;
import com.apms.domain.user.repository.sql.AccountRepository;
import com.apms.domain.user.repository.sql.UserProfileRepository;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.profile.service.OwnerOrganizationService;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import org.mockito.ArgumentCaptor;

@ExtendWith(MockitoExtension.class)
public class ProjectServiceProgressTest {

    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private ProjectMemberRepository projectMemberRepository;
    @Mock
    private AccountRepository accountRepository;
    @Mock
    private Neo4jClient neo4jClient;


    @Mock
    private UserProfileRepository userProfileRepository;

    @Mock
    private ProjectKeyResultRepository projectKeyResultRepository;

    @Mock
    private ProjectTaskRepository projectTaskRepository;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private OwnerOrganizationService ownerOrganizationService;
    @Mock
    private CompanyProfileRepository companyProfileRepository;
    @Mock
    private ProjectTargetProfileResolver projectTargetProfileResolver;

    @InjectMocks
    private ProjectService projectService;

    private Account mockAccount;
    private Project mockProject;

    @BeforeEach
    void setUp() {
        mockAccount = new Account();
        mockAccount.setId(1L);

        mockProject = new Project();
        mockProject.setId(100L);
        mockProject.setProjectName("Test Project");
        mockProject.setProjectType(ProjectType.RESEARCH_NEW_COMPANY);
        mockProject.setTargetCompanyName("Target");
        mockProject.setCreatedAt(LocalDateTime.now().minusDays(5));
        mockProject.setCreatedByAccount(mockAccount);
    }

    @Test
    void createProject_missingPlannedEndDate_throwsException() {
        // missing planned end date is handled by @NotNull validation in controller,
        // but let's test end date before start date validation in service
        CreateProjectRequest req = new CreateProjectRequest();
        req.setProjectName("Name");
        req.setProjectType(ProjectType.RESEARCH_NEW_COMPANY);
        req.setTargetCompanyName("Target");
        req.setPlannedEndDate(LocalDate.now().minusDays(1)); // past date

        assertThrows(BusinessValidationException.class, () -> projectService.createProject(req, 1L));
    }

    @Test
    void createProject_endDateEqualToStart_createsSuccessfully() {
        CreateProjectRequest req = new CreateProjectRequest();
        req.setProjectName("Name");
        req.setProjectType(ProjectType.RESEARCH_NEW_COMPANY);
        req.setTargetCompanyName("Target");
        req.setTargetRelationshipType(com.apms.common.enums.RelationshipType.PARTNER_WITH);
        req.setPlannedEndDate(LocalDate.now()); 

        when(accountRepository.getReferenceById(1L)).thenReturn(mockAccount);
        when(projectRepository.save(any(Project.class))).thenAnswer(i -> {
            Project p = i.getArgument(0);
            p.setId(200L);
            return p;
        });
        CompanyProfile shell = new CompanyProfile();
        shell.setCompanyId("shell-200");
        when(projectTargetProfileResolver.getOrCreateProjectProfileShell(any(Project.class), eq(1L))).thenReturn(shell);

        ProjectResponse res = projectService.createProject(req, 1L);
        assertNotNull(res);
        assertEquals(req.getPlannedEndDate(), res.getPlannedEndDate());
        assertNotNull(res.getTargetCompanyProfileId());
        assertEquals("shell-200", res.getTargetCompanyProfileId());
    }

    @Test
    void testCreateProject_ValidationFailure_PastEndDate() {
        CreateProjectRequest req = new CreateProjectRequest();
        req.setProjectName("Name");
        req.setProjectType(ProjectType.RESEARCH_NEW_COMPANY);
        req.setTargetCompanyName("Target");
        req.setTargetRelationshipType(com.apms.common.enums.RelationshipType.PARTNER_WITH);
        req.setPlannedEndDate(LocalDate.now().minusDays(1)); // Invalid

        assertThrows(com.apms.common.exception.BusinessValidationException.class, () -> {
            projectService.createProject(req, 1L);
        });
    }

    @Test
    void testUpdateProject_ValidationFailure_BeforeStartDate() {
        Project p = new Project();
        p.setId(10L);
        p.setCreatedAt(LocalDateTime.now().minusDays(10));
        
        UpdateProjectRequest req = new UpdateProjectRequest();
        req.setPlannedEndDate(LocalDate.now().minusDays(15)); // Before start date

        when(projectRepository.findById(10L)).thenReturn(Optional.of(p));

        assertThrows(com.apms.common.exception.BusinessValidationException.class, () -> {
            projectService.updateProject(10L, req);
        });
    }

    @Test
    void updateProject_endDateValid_updatesSuccessfully() {
        UpdateProjectRequest req = new UpdateProjectRequest();
        LocalDate newDate = mockProject.getCreatedAt().toLocalDate().plusDays(10);
        req.setPlannedEndDate(newDate);

        when(projectRepository.findById(100L)).thenReturn(Optional.of(mockProject));
        when(projectRepository.save(any(Project.class))).thenReturn(mockProject);
        when(projectMemberRepository.findByProject_Id(100L)).thenReturn(Collections.emptyList());
        when(projectTaskRepository.getProjectTaskStatsIn(any(), any(), any())).thenReturn(Collections.emptyList());

        ProjectResponse res = projectService.updateProject(100L, req);
        assertEquals(newDate, res.getPlannedEndDate());
    }

    private ProjectTaskRepository.ProjectTaskStats mockStats(Long projectId, Long total, Long completed) {
        return new ProjectTaskRepository.ProjectTaskStats() {
            @Override
            public Long getProjectId() { return projectId; }
            @Override
            public Long getTotalTasks() { return total; }
            @Override
            public Long getCompletedTasks() { return completed; }
        };
    }

    @Test
    void getProjectById_noTasks_returnsZeroProgress() {
        when(projectRepository.findById(100L)).thenReturn(Optional.of(mockProject));
        when(projectMemberRepository.findByProject_Id(100L)).thenReturn(Collections.emptyList());
        when(projectTaskRepository.getProjectTaskStatsIn(List.of(100L), TaskStatus.DONE, TaskStatus.CANCELLED))
                .thenReturn(List.of(mockStats(100L, 0L, 0L)));

        ProjectResponse res = projectService.getProjectById(100L);
        assertEquals(0, res.getProgressPercentage());
        assertEquals(0, res.getTotalTasks());
        assertEquals(0, res.getCompletedTasks());
    }

    @Test
    void getProjectById_3DoneOf4Eligible_returns75Progress() {
        when(projectRepository.findById(100L)).thenReturn(Optional.of(mockProject));
        when(projectTaskRepository.getProjectTaskStatsIn(List.of(100L), TaskStatus.DONE, TaskStatus.CANCELLED))
                .thenReturn(List.of(mockStats(100L, 4L, 3L)));

        ProjectResponse res = projectService.getProjectById(100L);
        assertEquals(75, res.getProgressPercentage());
        assertEquals(4, res.getTotalTasks());
        assertEquals(3, res.getCompletedTasks());
    }

    @Test
    void getProjectById_allDone_returns100Progress() {
        when(projectRepository.findById(100L)).thenReturn(Optional.of(mockProject));
        when(projectTaskRepository.getProjectTaskStatsIn(List.of(100L), TaskStatus.DONE, TaskStatus.CANCELLED))
                .thenReturn(List.of(mockStats(100L, 5L, 5L)));

        ProjectResponse res = projectService.getProjectById(100L);
        assertEquals(100, res.getProgressPercentage());
    }

    @Test
    void getProjectById_overdueIncomplete_returnsOverdueTrue() {
        mockProject.setStatus(ProjectStatus.ACTIVE);
        mockProject.setPlannedEndDate(LocalDate.now().minusDays(1)); // overdue
        when(projectRepository.findById(100L)).thenReturn(Optional.of(mockProject));
        when(projectTaskRepository.getProjectTaskStatsIn(List.of(100L), TaskStatus.DONE, TaskStatus.CANCELLED))
                .thenReturn(List.of(mockStats(100L, 4L, 3L))); // 75%

        ProjectResponse res = projectService.getProjectById(100L);
        assertTrue(res.getIsOverdue());
    }

    @Test
    void getProjectById_overdueComplete_returnsOverdueFalse() {
        mockProject.setPlannedEndDate(LocalDate.now().minusDays(1)); // past deadline
        when(projectRepository.findById(100L)).thenReturn(Optional.of(mockProject));
        when(projectTaskRepository.getProjectTaskStatsIn(List.of(100L), TaskStatus.DONE, TaskStatus.CANCELLED))
                .thenReturn(List.of(mockStats(100L, 4L, 4L))); // 100%

        ProjectResponse res = projectService.getProjectById(100L);
        assertFalse(res.getIsOverdue());
    }

    @Test
    void getProjectById_futureDeadline_returnsOverdueFalse() {
        mockProject.setPlannedEndDate(LocalDate.now().plusDays(1)); // future deadline
        when(projectRepository.findById(100L)).thenReturn(Optional.of(mockProject));
        when(projectTaskRepository.getProjectTaskStatsIn(List.of(100L), TaskStatus.DONE, TaskStatus.CANCELLED))
                .thenReturn(List.of(mockStats(100L, 4L, 0L))); // 0%

        ProjectResponse res = projectService.getProjectById(100L);
        assertFalse(res.getIsOverdue());
    }

    @Test
    void getProjectById_nullDeadline_returnsOverdueFalse() {
        mockProject.setPlannedEndDate(null); // legacy project
        when(projectRepository.findById(100L)).thenReturn(Optional.of(mockProject));
        when(projectTaskRepository.getProjectTaskStatsIn(List.of(100L), TaskStatus.DONE, TaskStatus.CANCELLED))
                .thenReturn(List.of(mockStats(100L, 4L, 0L))); // 0%

        ProjectResponse res = projectService.getProjectById(100L);
        assertFalse(res.getIsOverdue());
        assertNull(res.getPlannedEndDate());
    }

    @Test
    void getAllProjects_fetchesBatchStats() {
        Project p1 = new Project(); p1.setId(1L); p1.setProjectName("P1"); p1.setCreatedAt(LocalDateTime.now());
        Project p2 = new Project(); p2.setId(2L); p2.setProjectName("P2"); p2.setCreatedAt(LocalDateTime.now());

        Page<Project> page = new PageImpl<>(List.of(p1, p2));
        when(projectRepository.findAll(any(PageRequest.class))).thenReturn(page);
        
        when(projectTaskRepository.getProjectTaskStatsIn(List.of(1L, 2L), TaskStatus.DONE, TaskStatus.CANCELLED))
                .thenReturn(List.of(
                        mockStats(1L, 10L, 5L),
                        mockStats(2L, 5L, 5L)
                ));

        Page<ProjectResponse> res = projectService.getAllProjects(null, null, PageRequest.of(0, 10));
        
        assertEquals(2, res.getContent().size());
        
        ProjectResponse r1 = res.getContent().get(0);
        assertEquals(50, r1.getProgressPercentage());
        assertEquals(10, r1.getTotalTasks());
        
        ProjectResponse r2 = res.getContent().get(1);
        assertEquals(100, r2.getProgressPercentage());
        assertEquals(5, r2.getTotalTasks());
    }

    @Test
    void createProject_updateExistingCompany_autoPopulatesTaxCodeFromProfile_preservesLeadingZeros() {
        CreateProjectRequest req = new CreateProjectRequest();
        req.setProjectName("Vietjet Project");
        req.setProjectType(ProjectType.UPDATE_EXISTING_COMPANY);
        req.setTargetCompanyProfileId("vj-profile-001");
        req.setTargetCompanyName("VIETJET AIR");
        req.setTargetRelationshipType(com.apms.common.enums.RelationshipType.PARTNER_WITH);
        req.setPlannedEndDate(LocalDate.now().plusDays(30));
        req.setTargetCompanyTaxCode(null);

        CompanyProfile profile = new CompanyProfile();
        profile.setCompanyId("vj-profile-001");
        CompanyProfile.Identity identity = new CompanyProfile.Identity();
        identity.setLegalName("VIETJET AIR");
        identity.setTaxCode("0000124456328");
        profile.setIdentity(identity);

        when(companyProfileRepository.findByCompanyId("vj-profile-001")).thenReturn(Optional.of(profile));
        when(accountRepository.getReferenceById(1L)).thenReturn(mockAccount);
        when(projectRepository.save(any(Project.class))).thenAnswer(i -> {
            Project p = i.getArgument(0);
            p.setId(301L);
            return p;
        });

        ProjectResponse res = projectService.createProject(req, 1L);
        assertNotNull(res);
        assertEquals("0000124456328", res.getTargetCompanyTaxCode());

        ArgumentCaptor<Project> captor = ArgumentCaptor.forClass(Project.class);
        verify(projectRepository, atLeastOnce()).save(captor.capture());
        Project savedProject = captor.getValue();
        assertEquals("0000124456328", savedProject.getTargetCompanyTaxCode());
        assertTrue(savedProject.getTargetCompanyTaxCode().startsWith("0000"));
    }

    @Test
    void getProjectById_existingProjectMissingTaxCode_healsTaxCodeFromProfile() {
        mockProject.setProjectType(ProjectType.UPDATE_EXISTING_COMPANY);
        mockProject.setTargetCompanyProfileId("vj-profile-001");
        mockProject.setTargetCompanyTaxCode(null);

        CompanyProfile profile = new CompanyProfile();
        profile.setCompanyId("vj-profile-001");
        CompanyProfile.Identity identity = new CompanyProfile.Identity();
        identity.setTaxCode("0000124456328");
        profile.setIdentity(identity);

        when(projectRepository.findById(100L)).thenReturn(Optional.of(mockProject));
        when(companyProfileRepository.findByCompanyId("vj-profile-001")).thenReturn(Optional.of(profile));
        when(projectRepository.save(any(Project.class))).thenAnswer(i -> i.getArgument(0));

        ProjectResponse res = projectService.getProjectById(100L);
        assertNotNull(res);
        assertEquals("0000124456328", res.getTargetCompanyTaxCode());
        assertEquals("0000124456328", mockProject.getTargetCompanyTaxCode());
        verify(projectRepository).save(mockProject);
    }

    @Test
    void updateProject_existingCompany_doesNotThrowFalseTaxCodeDuplicate() {
        mockProject.setProjectType(ProjectType.UPDATE_EXISTING_COMPANY);
        mockProject.setStatus(ProjectStatus.DRAFT);
        mockProject.setTargetCompanyProfileId("vj-profile-001");
        mockProject.setTargetCompanyTaxCode("0000124456328");

        when(projectRepository.findById(100L)).thenReturn(Optional.of(mockProject));
        when(projectRepository.save(any(Project.class))).thenAnswer(i -> i.getArgument(0));

        UpdateProjectRequest updateReq = new UpdateProjectRequest();
        updateReq.setTargetCompanyTaxCode("0000124456328");

        ProjectResponse res = projectService.updateProject(100L, updateReq);
        assertNotNull(res);
        assertEquals("0000124456328", res.getTargetCompanyTaxCode());
    }
}
