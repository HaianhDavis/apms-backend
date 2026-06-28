package com.apms.domain.document.repository.mongo;

import com.apms.domain.document.RawDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface RawDocumentRepository extends MongoRepository<RawDocument, String> {
    Optional<RawDocument> findByImportJobId(String importJobId);
    java.util.List<RawDocument> findByProjectIdAndIsHiddenTrue(String projectId);
}
