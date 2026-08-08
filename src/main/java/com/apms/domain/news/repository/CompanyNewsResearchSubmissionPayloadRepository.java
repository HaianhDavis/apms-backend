package com.apms.domain.news.repository;

import com.apms.domain.news.entity.CompanyNewsResearchSubmissionPayload;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CompanyNewsResearchSubmissionPayloadRepository extends MongoRepository<CompanyNewsResearchSubmissionPayload, String> {
    Optional<CompanyNewsResearchSubmissionPayload> findBySubmissionId(Long submissionId);
}
