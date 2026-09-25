package com.apms.domain.ai.repository.mongo;

import com.apms.domain.ai.entity.AiApiKeyDocument;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AiApiKeyRepository extends MongoRepository<AiApiKeyDocument, String> {
    List<AiApiKeyDocument> findByActiveTrueOrderByOrderIndexAsc();
    List<AiApiKeyDocument> findAllByOrderByOrderIndexAscCreatedAtAsc();
}
