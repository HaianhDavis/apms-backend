package com.apms.domain.chat;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "project_chat_messages")
@CompoundIndex(name = "projectId_createdAt_idx", def = "{'projectId': 1, 'createdAt': -1}")
public class ProjectChatMessage {

    @Id
    private String id;

    @Indexed
    private Long projectId;

    private Long senderId;
    private String senderName;

    private String content;

    private String replyToMessageId;

    @Builder.Default
    private Boolean isEdited = false;

    @Builder.Default
    private Boolean isDeleted = false;

    @CreatedDate
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;
}
