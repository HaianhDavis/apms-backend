package com.apms.domain.companymember.service;

import com.apms.common.enums.SubmissionStatus;
import com.apms.common.enums.SubmissionType;
import com.apms.common.enums.SystemRole;
import com.apms.common.enums.TaskStatus;
import com.apms.common.enums.TaskType;
import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.companymember.CompanyMemberResearchDraft;
import com.apms.domain.companymember.CompanyMemberResearchItem;
import com.apms.domain.companymember.dto.CompanyMemberResearchDraftRequest;
import com.apms.domain.companymember.dto.CompanyMemberResearchItemRequest;
import com.apms.domain.companymember.repository.CompanyMemberResearchDraftRepository;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import com.apms.domain.profile.repository.mongo.CompanyProfileVersionRepository;
import com.apms.domain.project.Project;
import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.ProjectTaskSubmission;
import com.apms.domain.project.dto.CreateProjectTaskSubmissionRequest;
import com.apms.domain.project.dto.ProjectTaskSubmissionResponse;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.apms.domain.project.repository.sql.ProjectTaskRepository;
import com.apms.domain.project.service.ProjectTaskSubmissionService;
import com.apms.domain.user.Account;
import com.apms.security.UserDetailsImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CompanyMemberResearchServiceTest {

    @Mock
    private CompanyMemberResearchDraftRepository draftRepository;
    @Mock
    private ProjectTaskRepository taskRepository;
    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private CompanyProfileRepository profileRepository;
    @Mock
    private CompanyProfileVersionRepository profileVersionRepository;
    @Mock
    private ProjectTaskSubmissionService submissionService;
    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private CompanyMemberResearchService service;

    private UserDetailsImpl userDetails;
    private Project project;
    private ProjectTask task;

    @BeforeEach
    void setUp() {
        userDetails = new UserDetailsImpl(
                1L, "staff@example.com", "pass",
                List.of(new SimpleGrantedAuthority("ROLE_BUSINESS_DEVELOPMENT_STAFF")), true
        );
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities())
        );

        project = new Project();
        project.setId(10L);
        project.setTargetCompanyProfileId("profile-1");

        Account assignedTo = new Account();
        assignedTo.setId(1L);

        task = new ProjectTask();
        task.setId(100L);
        task.setProject(project);
        task.setTaskType(TaskType.COMPANY_MEMBER_RESEARCH);
        task.setStatus(TaskStatus.IN_PROGRESS);
        task.setAssignedToAccount(assignedTo);
    }

    @Test
    void saveDraft_Success() {
        when(taskRepository.findById(100L)).thenReturn(Optional.of(task));
        when(projectRepository.existsByIdAndMembersAccountId(10L, 1L)).thenReturn(true);
        when(draftRepository.findByTaskId(100L)).thenReturn(Optional.empty());
        when(draftRepository.save(any())).thenAnswer(inv -> {
            CompanyMemberResearchDraft arg = inv.getArgument(0);
            arg.setId("draft-1");
            return arg;
        });

        CompanyMemberResearchDraftRequest request = new CompanyMemberResearchDraftRequest();
        CompanyMemberResearchItemRequest itemReq = new CompanyMemberResearchItemRequest();
        itemReq.setFullName("John Doe");
        request.setMembers(List.of(itemReq));

        var response = service.saveDraft(10L, 100L, request);

        assertNotNull(response);
        assertEquals("draft-1", response.getId());
        assertEquals(1, response.getMembers().size());
        assertEquals("John Doe", response.getMembers().get(0).getFullName());
        verify(draftRepository).save(any());
        verify(auditLogService).log(eq(1L), any(), eq("CompanyMemberResearchDraft"), eq("draft-1"), any());
    }

    @Test
    void saveDraft_AccessDenied_NotAssigned() {
        task.getAssignedToAccount().setId(99L); // Assigned to someone else
        when(taskRepository.findById(100L)).thenReturn(Optional.of(task));
        when(projectRepository.existsByIdAndMembersAccountId(10L, 1L)).thenReturn(true);

        CompanyMemberResearchDraftRequest request = new CompanyMemberResearchDraftRequest();
        assertThrows(AccessDeniedException.class, () -> service.saveDraft(10L, 100L, request));
    }

    @Test
    void submitDraft_Success() {
        when(taskRepository.findById(100L)).thenReturn(Optional.of(task));
        when(projectRepository.existsByIdAndMembersAccountId(10L, 1L)).thenReturn(true);

        CompanyMemberResearchDraft draft = new CompanyMemberResearchDraft();
        draft.setId("draft-1");
        draft.setMembers(List.of(new CompanyMemberResearchItem()));
        when(draftRepository.findByTaskId(100L)).thenReturn(Optional.of(draft));

        ProjectTaskSubmissionResponse subRes = ProjectTaskSubmissionResponse.builder().id(50L).build();
        when(submissionService.submitTask(eq(10L), eq(100L), any())).thenReturn(subRes);

        service.submitDraft(10L, 100L);

        ArgumentCaptor<CompanyMemberResearchDraft> draftCaptor = ArgumentCaptor.forClass(CompanyMemberResearchDraft.class);
        verify(draftRepository).save(draftCaptor.capture());
        assertEquals(50L, draftCaptor.getValue().getSubmissionId());
    }

    @Test
    void submitDraft_EmptyMembers_ThrowsException() {
        when(taskRepository.findById(100L)).thenReturn(Optional.of(task));
        when(projectRepository.existsByIdAndMembersAccountId(10L, 1L)).thenReturn(true);

        CompanyMemberResearchDraft draft = new CompanyMemberResearchDraft();
        draft.setId("draft-1");
        draft.setMembers(new ArrayList<>());
        when(draftRepository.findByTaskId(100L)).thenReturn(Optional.of(draft));

        assertThrows(BusinessValidationException.class, () -> service.submitDraft(10L, 100L));
    }

    @Test
    void handleApproval_MergesNonDuplicates() {
        ProjectTaskSubmission sub = new ProjectTaskSubmission();
        sub.setSubmissionType(SubmissionType.COMPANY_MEMBER_RESEARCH);
        sub.setTargetEntityId("draft-1");
        sub.setProjectTask(task);
        sub.setProject(project);

        CompanyMemberResearchDraft draft = new CompanyMemberResearchDraft();
        draft.setId("draft-1");
        draft.setCompanyProfileId("profile-1");
        draft.setTaskId(100L);
        draft.setCreatedByAccountId(1L);
        draft.setMembers(List.of(
                CompanyMemberResearchItem.builder().fullName("Jane Doe").position("CEO").build(),
                CompanyMemberResearchItem.builder().fullName("John Doe").position("CTO").build()
        ));

        when(draftRepository.findById("draft-1")).thenReturn(Optional.of(draft));

        CompanyProfile profile = new CompanyProfile();
        profile.setId("profile-1");
        profile.setCompanyId("company-1");
        profile.setVersion("1.0");
        profile.setCompanyMembers(new ArrayList<>(List.of(
                CompanyProfile.CompanyMember.builder().fullName("Jane Doe").position("CEO").build()
        )));

        when(profileRepository.findByCompanyId("profile-1")).thenReturn(Optional.of(profile));

        service.handleApproval(sub, 2L, "Looks good");

        ArgumentCaptor<CompanyProfile> profileCaptor = ArgumentCaptor.forClass(CompanyProfile.class);
        verify(profileRepository).save(profileCaptor.capture());

        CompanyProfile saved = profileCaptor.getValue();
        assertEquals(2, saved.getCompanyMembers().size()); // 1 existing + 1 new (CTO)
        assertEquals(2, saved.getVersion());
        assertEquals("2", saved.getMetadata().getLastModifiedBy());

        verify(profileVersionRepository).save(any());
        verify(auditLogService, times(2)).log(any(), any(), any(), any(), any());
    }
}
