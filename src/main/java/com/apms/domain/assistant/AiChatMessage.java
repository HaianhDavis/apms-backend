package com.apms.domain.assistant;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import com.apms.domain.assistant.dto.AiNavigationAction;

import java.time.LocalDateTime;
import java.util.List;

/**
 * MongoDB document storing individual AI assistant chat turns.
 * Collection: ai_chat_messages
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "ai_chat_messages")
public class AiChatMessage {

    @Id
    private String id;

    /** Groups messages into a single conversation thread. */
    @Indexed
    private String sessionId;

    /** Account ID of the authenticated user who asked the question. */
    private Long userId;

    /** SQL project context for row-level access validation. */
    private Long projectId;

    /** Optional: MongoDB CompanyProfile.id this question was about. */
    private String companyProfileId;

    private String question;
    private String answer;

    /** Source labels included in the response (e.g., "company_profiles", "neo4j", "score_snapshots"). */
    private List<String> sources;

    /** Suggested follow-up actions returned to the UI. */
    private List<String> suggestedActions;

    /** Structured UI navigation metadata. */
    private List<AiNavigationAction> navigationActions;

    private LocalDateTime createdAt;
}
