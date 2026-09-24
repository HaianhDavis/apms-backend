package com.apms.domain.ai.repository.mongo;

import com.apms.domain.ai.AiExtractionCache;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface AiExtractionCacheRepository extends MongoRepository<AiExtractionCache, String> {

    Optional<AiExtractionCache> findTopByImportJobIdOrderByCreatedAtDesc(Long importJobId);
}
