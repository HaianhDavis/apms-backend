package com.apms.domain.news.service;

import com.apms.common.enums.AuditAction;
import com.apms.common.enums.SubmissionType;
import com.apms.common.enums.SystemRole;
import com.apms.common.enums.TaskStatus;
import com.apms.common.enums.TaskType;
import com.apms.common.exception.BusinessValidationException;
import com.apms.common.exception.ResourceNotFoundException;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.news.dto.SubmitCompanyNewsResearchRequest;
import com.apms.domain.news.entity.CompanyNewsResearchDraft;
import com.apms.domain.news.entity.CompanyNewsResearchSubmissionPayload;
import com.apms.domain.news.enums.NewsDraftStatus;
import com.apms.domain.news.repository.CompanyNewsResearchDraftRepository;
import com.apms.domain.news.repository.CompanyNewsResearchSubmissionPayloadRepository;
import com.apms.domain.project.Project;
import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.dto.CreateProjectTaskSubmissionRequest;
import com.apms.domain.project.dto.ProjectTaskSubmissionResponse;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.apms.domain.project.repository.sql.ProjectTaskRepository;
import com.apms.domain.project.service.ProjectTaskSubmissionService;
import com.apms.domain.user.Account;
import com.apms.security.UserDetailsImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CompanyNewsResearchSubmissionServiceTest {

    @Mock
    private CompanyNewsResearchDraftRepository draftRepository;
    @Mock
    private CompanyNewsResearchSubmissionPayloadRepository payloadRepository;
    @Mock
    private ProjectTaskRepository taskRepository;
    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private ProjectTaskSubmissionService submissionService;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private SecurityContext securityContext;
    @Mock
    private Authentication authentication;

    @InjectMocks
    private CompanyNewsResearchSubmissionService service;

    private UserDetailsImpl userDetails;
    private ProjectTask task;
    private Project project;
    private Account assignedAccount;

    @BeforeEach
    void setUp() {
        assignedAccount = new Account();
        assignedAccount.setId(10L);

        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_" + SystemRole.BUSINESS_DEVELOPMENT_STAFF.name()));
        userDetails = new UserDetailsImpl(10L, "staff@test.com", "hash", authorities, true);

        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.getPrincipal()).thenReturn(userDetails);
        SecurityContextHolder.setContext(securityContext);

        project = new Project();
        project.setId(100L);

        task = new ProjectTask();
        task.setId(200L);
        task.setProject(project);
        task.setTaskType(TaskType.COMPANY_NEWS_RESEARCH);
        task.setStatus(TaskStatus.IN_PROGRESS);
        task.setAssignedToAccount(assignedAccount);
        task.setTargetCompanyProfileId("COMP123");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void emptyNewsDraftIdsRejected() {
        when(taskRepository.findById(200L)).thenReturn(Optional.of(task));
        when(projectRepository.existsByIdAndMembersAccountId(100L, 10L)).thenReturn(true);

        SubmitCompanyNewsResearchRequest request = new SubmitCompanyNewsResearchRequest();
        request.setNewsDraftIds(new ArrayList<>());

        assertThrows(BusinessValidationException.class, () -> service.submitNewsResearch(100L, 200L, request));
    }

    @Test
    void draftFromAnotherTaskRejected() {
        when(taskRepository.findById(200L)).thenReturn(Optional.of(task));
        when(projectRepository.existsByIdAndMembersAccountId(100L, 10L)).thenReturn(true);

        SubmitCompanyNewsResearchRequest request = new SubmitCompanyNewsResearchRequest();
        request.setNewsDraftIds(List.of("draft1"));

        when(draftRepository.findByIdAndTaskIdAndIsDeletedFalse("draft1", 200L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.submitNewsResearch(100L, 200L, request));
    }

    @Test
    void alreadySubmittedDraftRejected() {
        when(taskRepository.findById(200L)).thenReturn(Optional.of(task));
        when(projectRepository.existsByIdAndMembersAccountId(100L, 10L)).thenReturn(true);

        SubmitCompanyNewsResearchRequest request = new SubmitCompanyNewsResearchRequest();
        request.setNewsDraftIds(List.of("draft1"));

        CompanyNewsResearchDraft draft = new CompanyNewsResearchDraft();
        draft.setId("draft1");
        draft.setReviewStatus(NewsDraftStatus.SUBMITTED);
        when(draftRepository.findByIdAndTaskIdAndIsDeletedFalse("draft1", 200L)).thenReturn(Optional.of(draft));

        assertThrows(BusinessValidationException.class, () -> service.submitNewsResearch(100L, 200L, request));
    }

    @Test
    void onlyAssignedStaffMaySubmit() {
        // change user to another one
        UserDetailsImpl otherUser = new UserDetailsImpl(99L, "other@test.com", "hash", List.of(new SimpleGrantedAuthority("ROLE_" + SystemRole.BUSINESS_DEVELOPMENT_STAFF.name())), true);
        when(authentication.getPrincipal()).thenReturn(otherUser);

        when(taskRepository.findById(200L)).thenReturn(Optional.of(task));
        when(projectRepository.existsByIdAndMembersAccountId(100L, 99L)).thenReturn(true);

        SubmitCompanyNewsResearchRequest request = new SubmitCompanyNewsResearchRequest();
        request.setNewsDraftIds(List.of("draft1"));

        assertThrows(AccessDeniedException.class, () -> service.submitNewsResearch(100L, 200L, request));
    }

    @Test
    void submissionSucceeds() {
        when(taskRepository.findById(200L)).thenReturn(Optional.of(task));
        when(projectRepository.existsByIdAndMembersAccountId(100L, 10L)).thenReturn(true);

        SubmitCompanyNewsResearchRequest request = new SubmitCompanyNewsResearchRequest();
        request.setNewsDraftIds(List.of("draft1", "draft2"));

        CompanyNewsResearchDraft draft1 = new CompanyNewsResearchDraft();
        draft1.setId("draft1");
        draft1.setReviewStatus(NewsDraftStatus.DRAFT);

        CompanyNewsResearchDraft draft2 = new CompanyNewsResearchDraft();
        draft2.setId("draft2");
        draft2.setReviewStatus(NewsDraftStatus.DRAFT);

        when(draftRepository.findByIdAndTaskIdAndIsDeletedFalse("draft1", 200L)).thenReturn(Optional.of(draft1));
        when(draftRepository.findByIdAndTaskIdAndIsDeletedFalse("draft2", 200L)).thenReturn(Optional.of(draft2));

        CompanyNewsResearchSubmissionPayload savedPayload = CompanyNewsResearchSubmissionPayload.builder().id("payloadId1").build();
        when(payloadRepository.save(any())).thenReturn(savedPayload);

        ProjectTaskSubmissionResponse submissionResponse = ProjectTaskSubmissionResponse.builder().id(500L).build();
        when(submissionService.submitTask(eq(100L), eq(200L), any())).thenReturn(submissionResponse);

        service.submitNewsResearch(100L, 200L, request);

        // Verify payload created
        ArgumentCaptor<CompanyNewsResearchSubmissionPayload> payloadCaptor = ArgumentCaptor.forClass(CompanyNewsResearchSubmissionPayload.class);
        verify(payloadRepository, times(2)).save(payloadCaptor.capture());
        CompanyNewsResearchSubmissionPayload payload = payloadCaptor.getAllValues().get(0);
        assertEquals(100L, payload.getProjectId());
        assertEquals(200L, payload.getTaskId());
        assertEquals("COMP123", payload.getTargetCompanyProfileId());
        assertEquals(List.of("draft1", "draft2"), payload.getNewsDraftIds());

        // Verify submission called
        ArgumentCaptor<CreateProjectTaskSubmissionRequest> subReqCaptor = ArgumentCaptor.forClass(CreateProjectTaskSubmissionRequest.class);
        verify(submissionService).submitTask(eq(100L), eq(200L), subReqCaptor.capture());
        CreateProjectTaskSubmissionRequest subReq = subReqCaptor.getValue();
        assertEquals(SubmissionType.COMPANY_NEWS_RESEARCH, subReq.getSubmissionType());
        assertEquals("CompanyNewsResearchSubmissionPayload", subReq.getTargetEntityType());
        assertEquals("payloadId1", subReq.getTargetEntityId());

        // Verify drafts updated
        assertEquals(NewsDraftStatus.SUBMITTED, draft1.getReviewStatus());
        assertEquals(NewsDraftStatus.SUBMITTED, draft2.getReviewStatus());
        verify(draftRepository).saveAll(any());

        verify(auditLogService).log(eq(10L), eq(AuditAction.COMPANY_NEWS_RESEARCH_SUBMITTED), any(), any(), any());
    }
}
