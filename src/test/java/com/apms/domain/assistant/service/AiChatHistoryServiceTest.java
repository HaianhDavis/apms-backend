package com.apms.domain.assistant.service;

import com.apms.domain.assistant.AiChatMessage;
import com.apms.domain.assistant.dto.AiChatMessageResponse;
import com.apms.domain.assistant.repository.mongo.AiChatMessageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class AiChatHistoryServiceTest {

    @Mock
    private AiChatMessageRepository aiChatMessageRepository;

    @InjectMocks
    private AiChatHistoryService aiChatHistoryService;

    @Test
    void oldChatMessageWithoutNavigationActions_historyStillLoads() {
        AiChatMessage oldMessage = new AiChatMessage();
        oldMessage.setId("msg-1");
        oldMessage.setSessionId("sess-1");
        oldMessage.setUserId(100L);
        oldMessage.setProjectId(10L);
        oldMessage.setQuestion("Old question");
        oldMessage.setAnswer("Old answer");
        oldMessage.setCreatedAt(LocalDateTime.now());
        // navigationActions is null
        
        when(aiChatMessageRepository.findByUserIdAndSessionIdOrderByCreatedAtAsc(100L, "sess-1"))
                .thenReturn(List.of(oldMessage));

        List<AiChatMessageResponse> responses = aiChatHistoryService.getProjectAssistantMessages(100L, "sess-1");

        assertNotNull(responses);
        assertEquals(1, responses.size());
        assertNotNull(responses.get(0).getNavigationActions());
        assertTrue(responses.get(0).getNavigationActions().isEmpty());
    }
}
