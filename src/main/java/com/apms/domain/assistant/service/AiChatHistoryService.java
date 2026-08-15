package com.apms.domain.assistant.service;

import com.apms.domain.assistant.AiChatMessage;
import com.apms.domain.assistant.dto.AiChatMessageResponse;
import com.apms.domain.assistant.dto.AiChatSessionResponse;
import com.apms.domain.assistant.repository.mongo.AiChatMessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AiChatHistoryService {

    private final AiChatMessageRepository aiChatMessageRepository;

    public List<AiChatSessionResponse> listProjectAssistantSessions(Long currentUserId) {
        List<AiChatMessage> allMessages = aiChatMessageRepository.findByUserIdOrderByCreatedAtAsc(currentUserId);

        List<AiChatMessage> projectMessages = allMessages.stream()
                .filter(m -> m.getProjectId() != null)
                .collect(Collectors.toList());

        return groupMessagesIntoSessions(projectMessages);
    }

    public List<AiChatMessageResponse> getProjectAssistantMessages(Long currentUserId, String sessionId) {
        return aiChatMessageRepository.findByUserIdAndSessionIdOrderByCreatedAtAsc(currentUserId, sessionId).stream()
                .filter(m -> m.getProjectId() != null)
                .map(this::mapToMessageResponse)
                .collect(Collectors.toList());
    }

    public List<AiChatSessionResponse> listOwnerAssistantSessions(Long currentUserId) {
        List<AiChatMessage> allMessages = aiChatMessageRepository.findByUserIdOrderByCreatedAtAsc(currentUserId);

        List<AiChatMessage> ownerMessages = allMessages.stream()
                .filter(m -> m.getProjectId() == null)
                .collect(Collectors.toList());

        return groupMessagesIntoSessions(ownerMessages);
    }

    public List<AiChatMessageResponse> getOwnerAssistantMessages(Long currentUserId, String sessionId) {
        return aiChatMessageRepository.findByUserIdAndSessionIdOrderByCreatedAtAsc(currentUserId, sessionId).stream()
                .filter(m -> m.getProjectId() == null)
                .map(this::mapToMessageResponse)
                .collect(Collectors.toList());
    }

    private List<AiChatSessionResponse> groupMessagesIntoSessions(List<AiChatMessage> messages) {
        Map<String, List<AiChatMessage>> sessionsMap = messages.stream()
                .filter(m -> m.getSessionId() != null)
                .collect(Collectors.groupingBy(AiChatMessage::getSessionId));

        List<AiChatSessionResponse> sessionResponses = new ArrayList<>();

        for (Map.Entry<String, List<AiChatMessage>> entry : sessionsMap.entrySet()) {
            String sessionId = entry.getKey();
            List<AiChatMessage> sessionMessages = entry.getValue();

            if (sessionMessages.isEmpty()) continue;

            AiChatMessage firstMessage = sessionMessages.get(0);
            AiChatMessage lastMessage = sessionMessages.get(sessionMessages.size() - 1);

            String answerPreview = lastMessage.getAnswer();
            if (answerPreview != null && answerPreview.length() > 150) {
                answerPreview = answerPreview.substring(0, 150) + "...";
            }

            sessionResponses.add(AiChatSessionResponse.builder()
                    .sessionId(sessionId)
                    .firstQuestion(firstMessage.getQuestion())
                    .lastQuestion(lastMessage.getQuestion())
                    .lastAnswerPreview(answerPreview)
                    .messageCount(sessionMessages.size())
                    .startedAt(firstMessage.getCreatedAt())
                    .lastMessageAt(lastMessage.getCreatedAt())
                    .projectId(firstMessage.getProjectId())
                    .companyProfileId(firstMessage.getCompanyProfileId())
                    .build());
        }

        // Order sessions by lastMessageAt descending
        sessionResponses.sort((s1, s2) -> s2.getLastMessageAt().compareTo(s1.getLastMessageAt()));

        return sessionResponses;
    }

    private AiChatMessageResponse mapToMessageResponse(AiChatMessage message) {
        return AiChatMessageResponse.builder()
                .id(message.getId())
                .sessionId(message.getSessionId())
                .userId(message.getUserId())
                .projectId(message.getProjectId())
                .companyProfileId(message.getCompanyProfileId())
                .question(message.getQuestion())
                .answer(message.getAnswer())
                .sources(message.getSources())
                .suggestedActions(message.getSuggestedActions())
                .navigationActions(message.getNavigationActions() == null ? java.util.Collections.emptyList() : message.getNavigationActions())
                .createdAt(message.getCreatedAt())
                .build();
    }
}
