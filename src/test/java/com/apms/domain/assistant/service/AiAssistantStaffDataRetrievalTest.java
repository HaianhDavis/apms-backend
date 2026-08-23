package com.apms.domain.assistant.service;

import com.apms.common.enums.SubmissionStatus;
import com.apms.common.enums.TaskPriority;
import com.apms.common.enums.TaskStatus;
import com.apms.common.security.ProjectSecurityEvaluator;
import com.apms.domain.assistant.dto.AiChatRequest;
import com.apms.domain.assistant.dto.AiChatResponse;
import com.apms.domain.assistant.repository.mongo.AiChatMessageRepository;
import com.apms.domain.project.Project;
import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.ProjectTaskSubmission;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.apms.domain.project.repository.sql.ProjectTaskRepository;
import com.apms.domain.project.repository.sql.ProjectTaskSubmissionRepository;
import com.apms.security.UserDetailsImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class AiAssistantStaffDataRetrievalTest {

    @Mock private AssistantContextService contextService;
    @Mock private GeminiAssistantProvider assistantProvider;
    @Mock private AiChatMessageRepository chatMessageRepository;
    @Mock private ProjectSecurityEvaluator projectSecurity;
    @Mock private ProjectRepository projectRepository;
    @Mock private ProjectTaskRepository projectTaskRepository;
    @Mock private ProjectTaskSubmissionRepository projectTaskSubmissionRepository;

    @InjectMocks
    private AiAssistantService aiAssistantService;

    private UserDetailsImpl staffUser;
    private Project project;
    private ProjectTask task1;
    private ProjectTask task2;

    @BeforeEach
    void setUp() {
        staffUser = mock(UserDetailsImpl.class);
        lenient().when(staffUser.getId()).thenReturn(300L);
        lenient().when((java.util.Collection<GrantedAuthority>) staffUser.getAuthorities())
                 .thenReturn(List.of((GrantedAuthority) () -> "ROLE_BUSINESS_DEVELOPMENT_STAFF"));

        Authentication authentication = mock(Authentication.class);
        lenient().when(authentication.getPrincipal()).thenReturn(staffUser);
        SecurityContext securityContext = mock(SecurityContext.class);
        lenient().when(securityContext.getAuthentication()).thenReturn(authentication);
        SecurityContextHolder.setContext(securityContext);

        project = new Project();
        project.setId(10L);
        project.setProjectName("Test Project");

        task1 = new ProjectTask();
        task1.setId(101L);
        task1.setTitle("Task 1");
        task1.setProject(project);
        task1.setStatus(TaskStatus.IN_PROGRESS);
        task1.setPriority(TaskPriority.HIGH);
        task1.setDueDate(LocalDateTime.now().plusDays(2));

        task2 = new ProjectTask();
        task2.setId(102L);
        task2.setTitle("Task 2");
        task2.setProject(project);
        task2.setStatus(TaskStatus.TODO);
        task2.setPriority(TaskPriority.MEDIUM);
        task2.setDueDate(LocalDateTime.now().plusDays(5));
    }

    @Test
    void testMyTasks_NoLazyInitializationException() {
        when(projectSecurity.isMemberOrOwner(any())).thenReturn(true);
        when(projectTaskRepository.findByAssignedToAccount_Id(eq(300L))).thenReturn(List.of(task1, task2));

        AiChatRequest request = new AiChatRequest();
        request.setQuestion("What tasks am I assigned to?");

        AiChatResponse response = aiAssistantService.chat(request);

        assertTrue(response.getAnswer().contains("You currently have 2 assigned task(s)"));
        assertTrue(response.getAnswer().contains("Test Project"));
        verify(assistantProvider, never()).answer(anyString(), any());
    }

    @Test
    void testDeadlinePriority_ReturnsClosestTask() {
        when(projectSecurity.isMemberOrOwner(any())).thenReturn(true);
        when(projectTaskRepository.findByAssignedToAccount_Id(eq(300L))).thenReturn(List.of(task1, task2));

        AiChatRequest request = new AiChatRequest();
        request.setQuestion("Which task has the closest deadline?");

        AiChatResponse response = aiAssistantService.chat(request);

        assertTrue(response.getAnswer().contains("Task 1"));
        assertTrue(response.getAnswer().contains("Test Project"));
        verify(assistantProvider, never()).answer(anyString(), any());
    }

    @Test
    void testNextAction_ReturnsCorrectTask() {
        when(projectSecurity.isMemberOrOwner(any())).thenReturn(true);
        when(projectTaskRepository.findByAssignedToAccount_Id(eq(300L))).thenReturn(List.of(task1, task2));

        AiChatRequest request = new AiChatRequest();
        request.setQuestion("What should I work on next?");

        AiChatResponse response = aiAssistantService.chat(request);

        assertTrue(response.getAnswer().contains("Task 1"));
        assertTrue(response.getAnswer().contains("This is your highest-priority executable task with the nearest deadline"));
    }

    @Test
    void testSubmissionStatus_NoLazyInitializationException() {
        when(projectSecurity.isMemberOrOwner(any())).thenReturn(true);
        when(projectTaskRepository.findByAssignedToAccount_Id(eq(300L))).thenReturn(List.of(task1));

        ProjectTaskSubmission submission = new ProjectTaskSubmission();
        submission.setId(1001L);
        submission.setProjectTask(task1);
        submission.setStatus(SubmissionStatus.SUBMITTED);
        
        when(projectTaskSubmissionRepository.findByProjectTask_IdIn(anyList())).thenReturn(List.of(submission));

        AiChatRequest request = new AiChatRequest();
        request.setQuestion("What is my submission status?");

        AiChatResponse response = aiAssistantService.chat(request);

        assertTrue(response.getAnswer().contains("Your submission status is"));
        assertTrue(response.getAnswer().contains("Task 1"));
    }

    @Test
    void testReturnedWork_NoLazyInitializationException() {
        when(projectSecurity.isMemberOrOwner(any())).thenReturn(true);
        when(projectTaskRepository.findByAssignedToAccount_Id(eq(300L))).thenReturn(List.of(task1));

        ProjectTaskSubmission submission = new ProjectTaskSubmission();
        submission.setId(1001L);
        submission.setProjectTask(task1);
        submission.setStatus(SubmissionStatus.REVISION_REQUESTED);
        
        when(projectTaskSubmissionRepository.findByProjectTask_IdIn(anyList())).thenReturn(List.of(submission));

        AiChatRequest request = new AiChatRequest();
        request.setQuestion("Has any of my work been returned?");

        AiChatResponse response = aiAssistantService.chat(request);

        assertTrue(response.getAnswer().contains("require revision"));
        assertTrue(response.getAnswer().contains("Test Project"));
    }

    @Test
    void testSecurity_NoLeakage() {
        when(projectSecurity.isMemberOrOwner(any())).thenReturn(true);
        when(projectTaskRepository.findByAssignedToAccount_Id(eq(300L))).thenReturn(List.of(task1));

        AiChatRequest request = new AiChatRequest();
        request.setQuestion("What tasks am I assigned to?");

        AiChatResponse response = aiAssistantService.chat(request);

        assertTrue(response.getAnswer().contains("You currently have 1 assigned task(s)"));
        assertTrue(response.getAnswer().contains("Task 1"));
    }
}
