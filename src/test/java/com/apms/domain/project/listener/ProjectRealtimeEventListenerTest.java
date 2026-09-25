package com.apms.domain.project.listener;

import com.apms.domain.project.event.ProjectWorkflowEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ProjectRealtimeEventListenerTest {

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @InjectMocks
    private ProjectRealtimeEventListener listener;

    @Test
    void handleProjectWorkflowEvent_sendsToCorrectDestination() {
        ProjectWorkflowEvent event = ProjectWorkflowEvent.of("TASK_CLAIMED", 23L, 48L, 15L);

        listener.handleProjectWorkflowEvent(event);

        verify(messagingTemplate).convertAndSend(eq("/topic/projects/23/updates"), eq(event));
    }
}
