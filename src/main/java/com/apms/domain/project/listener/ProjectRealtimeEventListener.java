package com.apms.domain.project.listener;

import com.apms.domain.project.event.ProjectWorkflowEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProjectRealtimeEventListener {

    private final SimpMessagingTemplate messagingTemplate;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void handleProjectWorkflowEvent(ProjectWorkflowEvent event) {
        if (event == null || event.projectId() == null) {
            return;
        }

        String destination = "/topic/projects/" + event.projectId() + "/updates";
        log.info("Broadcasting project update event [type={}, projectId={}, taskId={}, actorId={}] to destination {}",
                event.type(), event.projectId(), event.taskId(), event.actorId(), destination);

        try {
            messagingTemplate.convertAndSend(destination, event);
        } catch (Exception ex) {
            log.error("Failed to broadcast project update event to {}: {}", destination, ex.getMessage(), ex);
        }
    }
}
