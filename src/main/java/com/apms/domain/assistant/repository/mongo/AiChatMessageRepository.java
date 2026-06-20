package com.apms.domain.assistant.repository.mongo;

import com.apms.domain.assistant.AiChatMessage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface AiChatMessageRepository extends MongoRepository<AiChatMessage, String> {

    List<AiChatMessage> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    Page<AiChatMessage> findByUserIdAndProjectId(Long userId, Long projectId, Pageable pageable);
}
