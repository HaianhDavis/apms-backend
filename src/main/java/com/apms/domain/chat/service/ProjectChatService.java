package com.apms.domain.chat.service;

import com.apms.common.exception.BusinessValidationException;
import com.apms.common.exception.ResourceNotFoundException;
import com.apms.domain.chat.ProjectChatMessage;
import com.apms.domain.chat.dto.ChatMessageRequest;
import com.apms.domain.chat.dto.ChatMessageResponse;
import com.apms.domain.chat.repository.ProjectChatMessageRepository;
import com.apms.domain.project.repository.sql.ProjectMemberRepository;
import com.apms.domain.user.Account;
import com.apms.domain.user.repository.sql.AccountRepository;
import com.apms.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectChatService {

    private final ProjectChatMessageRepository messageRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final AccountRepository accountRepository;
    private final SimpMessagingTemplate messagingTemplate;

    @Transactional
    public ChatMessageResponse sendMessage(Long projectId, ChatMessageRequest request) {
        UserDetailsImpl currentUser = getCurrentUser();
        validateProjectMembership(projectId, currentUser);

        validateMessageContent(request.getContent());

        if (StringUtils.hasText(request.getReplyToMessageId())) {
            ProjectChatMessage repliedMessage = messageRepository.findById(request.getReplyToMessageId())
                    .orElseThrow(() -> new BusinessValidationException("Replied message not found"));
            if (!repliedMessage.getProjectId().equals(projectId)) {
                throw new BusinessValidationException("Replied message belongs to a different project");
            }
            // User instruction: deleted messages cannot be replied to unless policy says otherwise.
            if (Boolean.TRUE.equals(repliedMessage.getIsDeleted())) {
                throw new BusinessValidationException("Cannot reply to a deleted message");
            }
        }

        Account sender = accountRepository.findById(currentUser.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Account not found"));

        ProjectChatMessage message = ProjectChatMessage.builder()
                .projectId(projectId)
                .senderId(sender.getId())
                .senderName(sender.getEmail())
                .content(request.getContent().trim())
                .replyToMessageId(request.getReplyToMessageId())
                .build();

        message = messageRepository.save(message);

        ChatMessageResponse response = toResponse(message, "MESSAGE_CREATED");
        broadcast(projectId, response);
        return response;
    }

    @Transactional
    public ChatMessageResponse editMessage(Long projectId, String messageId, ChatMessageRequest request) {
        UserDetailsImpl currentUser = getCurrentUser();
        ProjectChatMessage message = validateMessageAccessAndOwnership(projectId, messageId, currentUser);

        validateMessageContent(request.getContent());

        message.setContent(request.getContent().trim());
        message.setIsEdited(true);

        message = messageRepository.save(message);

        ChatMessageResponse response = toResponse(message, "MESSAGE_UPDATED");
        broadcast(projectId, response);
        return response;
    }

    @Transactional
    public void deleteMessage(Long projectId, String messageId) {
        UserDetailsImpl currentUser = getCurrentUser();
        ProjectChatMessage message = validateMessageAccessAndOwnership(projectId, messageId, currentUser);

        message.setIsDeleted(true);
        message.setContent(""); // Erase content on soft delete for privacy
        message = messageRepository.save(message);

        ChatMessageResponse response = toResponse(message, "MESSAGE_DELETED");
        broadcast(projectId, response);
    }

    public Page<ChatMessageResponse> getHistory(Long projectId, Pageable pageable) {
        UserDetailsImpl currentUser = getCurrentUser();
        validateProjectMembership(projectId, currentUser);

        Page<ProjectChatMessage> messages = messageRepository.findByProjectIdOrderByCreatedAtDesc(projectId, pageable);
        return messages.map(msg -> toResponse(msg, null));
    }

    private ProjectChatMessage validateMessageAccessAndOwnership(Long projectId, String messageId, UserDetailsImpl user) {
        validateProjectMembership(projectId, user);

        ProjectChatMessage message = messageRepository.findById(messageId)
                .orElseThrow(() -> new ResourceNotFoundException("Message not found"));

        if (!message.getProjectId().equals(projectId)) {
            throw new BusinessValidationException("Message does not belong to the specified project");
        }

        if (Boolean.TRUE.equals(message.getIsDeleted())) {
            throw new BusinessValidationException("Message is already deleted");
        }

        if (!message.getSenderId().equals(user.getId())) {
            throw new AccessDeniedException("You can only modify your own messages");
        }

        return message;
    }

    private void validateProjectMembership(Long projectId, UserDetailsImpl user) {
        boolean hasGlobalChatAccess = user.getAuthorities().stream().anyMatch(authority ->
                authority.getAuthority().equals("ROLE_SYSTEM_ADMIN")
                        || authority.getAuthority().equals("ROLE_BUSINESS_OWNER"));
        if (hasGlobalChatAccess) {
            return;
        }
        if (!projectMemberRepository.existsByProject_IdAndAccount_Id(projectId, user.getId())) {
            throw new AccessDeniedException("User is not a member of this project");
        }
    }

    private void validateMessageContent(String content) {
        if (!StringUtils.hasText(content)) {
            throw new BusinessValidationException("Message content cannot be blank");
        }
        if (content.trim().length() > 2000) {
            throw new BusinessValidationException("Message content cannot exceed 2000 characters");
        }
    }

    private ChatMessageResponse toResponse(ProjectChatMessage message, String eventType) {
        return ChatMessageResponse.builder()
                .id(message.getId())
                .projectId(message.getProjectId())
                .senderId(message.getSenderId())
                .senderName(message.getSenderName())
                .content(Boolean.TRUE.equals(message.getIsDeleted()) ? "[Deleted]" : message.getContent())
                .replyToMessageId(message.getReplyToMessageId())
                .isEdited(message.getIsEdited())
                .isDeleted(message.getIsDeleted())
                .createdAt(message.getCreatedAt())
                .updatedAt(message.getUpdatedAt())
                .eventType(eventType)
                .build();
    }

    private void broadcast(Long projectId, ChatMessageResponse response) {
        messagingTemplate.convertAndSend("/topic/projects/" + projectId, response);
    }

    private UserDetailsImpl getCurrentUser() {
        if (SecurityContextHolder.getContext().getAuthentication() == null ||
                !(SecurityContextHolder.getContext().getAuthentication().getPrincipal() instanceof UserDetailsImpl)) {
            throw new AccessDeniedException("Unauthorized");
        }
        return (UserDetailsImpl) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
