package com.apms.domain.chat.repository;

import com.apms.domain.chat.ProjectChatMessage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProjectChatMessageRepository extends MongoRepository<ProjectChatMessage, String> {
    Page<ProjectChatMessage> findByProjectIdOrderByCreatedAtDesc(Long projectId, Pageable pageable);
}
