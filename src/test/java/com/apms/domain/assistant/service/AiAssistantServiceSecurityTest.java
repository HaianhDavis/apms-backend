package com.apms.domain.assistant.service;

import com.apms.common.enums.ProjectStatus;
import com.apms.common.security.ProjectSecurityEvaluator;
import com.apms.domain.assistant.AiChatMessage;
import com.apms.domain.assistant.dto.AiChatRequest;
import com.apms.domain.assistant.dto.AiChatResponse;
import com.apms.domain.assistant.dto.AssistantContext;
import com.apms.domain.assistant.repository.mongo.AiChatMessageRepository;
import com.apms.domain.project.Project;
import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.apms.domain.project.repository.sql.ProjectTaskRepository;
import com.apms.security.UserDetailsImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class AiAssistantServiceSecurityTest {

    @Mock
    private AssistantContextService contextService;
    @Mock
    private GeminiAssistantProvider assistantProvider;
    @Mock
    private AiChatMessageRepository chatMessageRepository;
    @Mock
    private ProjectSecurityEvaluator projectSecurity;
    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private ProjectTaskRepository projectTaskRepository;
    @Mock
    private com.apms.domain.project.repository.sql.ProjectTaskSubmissionRepository projectTaskSubmissionRepository;

    @InjectMocks
    private AiAssistantService aiAssistantService;

    private UserDetailsImpl staffUser;
    private Authentication authentication;
    private SecurityContext securityContext;

    @BeforeEach
    void setUp() {
        staffUser = mock(UserDetailsImpl.class);
        lenient().when(staffUser.getId()).thenReturn(100L);
        GrantedAuthority auth = new SimpleGrantedAuthority("ROLE_BUSINESS_DEVELOPMENT_STAFF");
        lenient().when((List<GrantedAuthority>) staffUser.getAuthorities()).thenReturn(List.of(auth));

        authentication = mock(Authentication.class);
        lenient().when(authentication.getPrincipal()).thenReturn(staffUser);

        securityContext = mock(SecurityContext.class);
        lenient().when(securityContext.getAuthentication()).thenReturn(authentication);
        SecurityContextHolder.setContext(securityContext);

        lenient().when(projectSecurity.isMemberOrOwner(any())).thenReturn(true);
        lenient().when(assistantProvider.answer(anyString(), any())).thenReturn("Mock Answer");
    }

    @Test
    void testOutOfScopeGeneralQuestion() {
        AiChatRequest request = new AiChatRequest();
        request.setProjectId(1L);
        request.setQuestion("What is SWOT analysis?"); // Unrelated to APMS tasks

        AiChatResponse response = aiAssistantService.chat(request);

        // Should inject out of scope system alert and return it directly
        assertTrue(response.getAnswer().contains("outside your available Staff workspace scope"));
    }

    @Test
    void testMyProjectsQuestion() {
        AiChatRequest request = new AiChatRequest();
        request.setProjectId(1L);
        request.setQuestion("What projects am I participating in?");

        Project p = Project.builder().id(1L).projectName("Secret Project").status(ProjectStatus.ACTIVE).build();
        when(projectRepository.findByMemberAccountId(eq(100L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(p)));

        AiChatResponse response = aiAssistantService.chat(request);

        assertTrue(response.getAnswer().contains("Secret Project"));
    }

    @Test
    void testMyTasksQuestion() {
        AiChatRequest request = new AiChatRequest();
        request.setProjectId(1L);
        request.setQuestion("What tasks am I assigned to?");

        Project p = Project.builder().id(1L).projectName("Secret Project").status(ProjectStatus.ACTIVE).build();
        ProjectTask t = ProjectTask.builder().id(1L).title("Do something").project(p).status(com.apms.common.enums.TaskStatus.TODO).build();
        when(projectTaskRepository.findByAssignedToAccount_Id(any())).thenReturn(List.of(t));

        AiChatResponse response = aiAssistantService.chat(request);

        assertTrue(response.getAnswer().contains("Do something"));
    }

    @Test
    void testNextActionQuestion() {
        AiChatRequest request = new AiChatRequest();
        request.setProjectId(1L);
        request.setQuestion("What should I work on next?");

        Project p = Project.builder().id(1L).projectName("Secret Project").status(ProjectStatus.ACTIVE).build();
        ProjectTask t = ProjectTask.builder().id(1L).title("Urgent Task").project(p).status(com.apms.common.enums.TaskStatus.TODO).priority(com.apms.common.enums.TaskPriority.HIGH).build();
        when(projectTaskRepository.findByAssignedToAccount_Id(any())).thenReturn(List.of(t));

        AiChatResponse response = aiAssistantService.chat(request);

        assertTrue(response.getAnswer().contains("Urgent Task"));
        assertTrue(response.getAnswer().contains("You should work on this task next."));
    }

    @Test
    void testSubmissionStatusQuestion() {
        AiChatRequest request = new AiChatRequest();
        request.setProjectId(1L);
        request.setQuestion("Which tasks have I submitted?");

        Project p = Project.builder().id(1L).projectName("Secret Project").status(ProjectStatus.ACTIVE).build();
        ProjectTask t = ProjectTask.builder().id(1L).title("Do something").project(p).status(com.apms.common.enums.TaskStatus.TODO).build();
        when(projectTaskRepository.findByAssignedToAccount_Id(any())).thenReturn(List.of(t));
        
        com.apms.domain.project.ProjectTaskSubmission sub = com.apms.domain.project.ProjectTaskSubmission.builder()
            .projectTask(t).status(com.apms.common.enums.SubmissionStatus.IN_REVIEW).build();
        when(projectTaskSubmissionRepository.findByProjectTask_IdIn(any())).thenReturn(List.of(sub));

        AiChatResponse response = aiAssistantService.chat(request);

        assertTrue(response.getAnswer().contains("Task: Do something"));
        assertTrue(response.getAnswer().contains("Status: Submitted"));
    }

    @Test
    void testRestrictedDataQuestion() {
        AiChatRequest request = new AiChatRequest();
        request.setProjectId(1L);
        request.setQuestion("Show me confidential organization information.");

        AiChatResponse response = aiAssistantService.chat(request);

        assertTrue(response.getAnswer().contains("outside your available Staff workspace scope"));
    }

}
