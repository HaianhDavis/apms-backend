package com.apms.domain.project.event;

import java.time.Instant;

/**
 * Lightweight project workflow notification event.
 * Broadcast over WebSocket (/topic/projects/{projectId}/updates) after DB commit.
 */
public record ProjectWorkflowEvent(
        String type,
        Long projectId,
        Long taskId,
        Long actorId,
        String timestamp
) {
    public static ProjectWorkflowEvent of(String type, Long projectId, Long taskId, Long actorId) {
        return new ProjectWorkflowEvent(type, projectId, taskId, actorId, Instant.now().toString());
    }

    public static ProjectWorkflowEvent of(String type, Long projectId, Long actorId) {
        return new ProjectWorkflowEvent(type, projectId, null, actorId, Instant.now().toString());
    }
}
