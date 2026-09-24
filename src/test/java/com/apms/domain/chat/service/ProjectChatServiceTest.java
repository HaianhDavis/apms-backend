package com.apms.domain.chat.service;

import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.chat.ProjectChatMessage;
import com.apms.domain.chat.dto.ChatMessageRequest;
import com.apms.domain.chat.dto.ChatMessageResponse;
import com.apms.domain.chat.repository.ProjectChatMessageRepository;
import com.apms.domain.project.repository.sql.ProjectMemberRepository;
import com.apms.domain.user.Account;
import com.apms.domain.user.repository.sql.AccountRepository;
import com.apms.security.UserDetailsImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProjectChatServiceTest {

    @Mock
    private ProjectChatMessageRepository messageRepository;
    @Mock
    private ProjectMemberRepository projectMemberRepository;
    @Mock
    private AccountRepository accountRepository;
    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @InjectMocks
    private ProjectChatService service;

    private UserDetailsImpl userDetails;

    @BeforeEach
    void setUp() {
        userDetails = new UserDetailsImpl(
                1L, "user@example.com", "pass",
                List.of(new SimpleGrantedAuthority("ROLE_BUSINESS_DEVELOPMENT_STAFF")), true
        );
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities())
        );
    }

    @Test
    void sendMessage_Success() {
        when(projectMemberRepository.existsByProject_IdAndAccount_Id(10L, 1L)).thenReturn(true);
        Account sender = new Account();
        sender.setId(1L);
        sender.setEmail("user@example.com");
        when(accountRepository.findById(1L)).thenReturn(Optional.of(sender));

        when(messageRepository.save(any())).thenAnswer(inv -> {
            ProjectChatMessage msg = inv.getArgument(0);
            msg.setId("msg-1");
            return msg;
        });

        ChatMessageRequest request = new ChatMessageRequest();
        request.setContent("Hello World");

        var response = service.sendMessage(10L, request);

        assertNotNull(response);
        assertEquals("msg-1", response.getId());
        assertEquals("Hello World", response.getContent());
        assertEquals("MESSAGE_CREATED", response.getEventType());

        verify(messageRepository).save(any());
        verify(messagingTemplate).convertAndSend(eq("/topic/projects/10"), any(ChatMessageResponse.class));
    }

    @Test
    void sendMessage_NotProjectMember_ThrowsAccessDenied() {
        when(projectMemberRepository.existsByProject_IdAndAccount_Id(10L, 1L)).thenReturn(false);

        ChatMessageRequest request = new ChatMessageRequest();
        request.setContent("Hello World");

        assertThrows(AccessDeniedException.class, () -> service.sendMessage(10L, request));
    }

    @Test
    void getHistory_BusinessOwner_DoesNotRequireMembership() {
        UserDetailsImpl owner = new UserDetailsImpl(
                2L, "owner@example.com", "pass",
                List.of(new SimpleGrantedAuthority("ROLE_BUSINESS_OWNER")), true
        );
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(owner, null, owner.getAuthorities())
        );
        PageRequest pageable = PageRequest.of(0, 20);
        when(messageRepository.findByProjectIdOrderByCreatedAtDesc(10L, pageable)).thenReturn(Page.empty(pageable));

        Page<ChatMessageResponse> result = service.getHistory(10L, pageable);

        assertTrue(result.isEmpty());
        verify(projectMemberRepository, never()).existsByProject_IdAndAccount_Id(anyLong(), anyLong());
    }

    @Test
    void editMessage_Success() {
        when(projectMemberRepository.existsByProject_IdAndAccount_Id(10L, 1L)).thenReturn(true);

        ProjectChatMessage existing = new ProjectChatMessage();
        existing.setId("msg-1");
        existing.setProjectId(10L);
        existing.setSenderId(1L);
        existing.setContent("Old Content");
        when(messageRepository.findById("msg-1")).thenReturn(Optional.of(existing));

        when(messageRepository.save(any())).thenReturn(existing);

        ChatMessageRequest request = new ChatMessageRequest();
        request.setContent("New Content");

        var response = service.editMessage(10L, "msg-1", request);

        assertEquals("New Content", response.getContent());
        assertTrue(response.getIsEdited());
        assertEquals("MESSAGE_UPDATED", response.getEventType());
    }

    @Test
    void editMessage_NotSender_ThrowsAccessDenied() {
        when(projectMemberRepository.existsByProject_IdAndAccount_Id(10L, 1L)).thenReturn(true);

        ProjectChatMessage existing = new ProjectChatMessage();
        existing.setId("msg-1");
        existing.setProjectId(10L);
        existing.setSenderId(2L); // Different sender
        when(messageRepository.findById("msg-1")).thenReturn(Optional.of(existing));

        ChatMessageRequest request = new ChatMessageRequest();
        request.setContent("New Content");

        assertThrows(AccessDeniedException.class, () -> service.editMessage(10L, "msg-1", request));
    }

    @Test
    void deleteMessage_Success_ErasesContent() {
        when(projectMemberRepository.existsByProject_IdAndAccount_Id(10L, 1L)).thenReturn(true);

        ProjectChatMessage existing = new ProjectChatMessage();
        existing.setId("msg-1");
        existing.setProjectId(10L);
        existing.setSenderId(1L);
        existing.setContent("Secret Message");
        when(messageRepository.findById("msg-1")).thenReturn(Optional.of(existing));
        when(messageRepository.save(any())).thenReturn(existing);

        service.deleteMessage(10L, "msg-1");

        ArgumentCaptor<ProjectChatMessage> msgCaptor = ArgumentCaptor.forClass(ProjectChatMessage.class);
        verify(messageRepository).save(msgCaptor.capture());

        ProjectChatMessage saved = msgCaptor.getValue();
        assertTrue(saved.getIsDeleted());
        assertEquals("", saved.getContent()); // Soft delete masked

        verify(messagingTemplate).convertAndSend(eq("/topic/projects/10"), any(ChatMessageResponse.class));
    }
}
