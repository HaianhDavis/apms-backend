package com.apms.domain.companymember.repository;

import com.apms.domain.companymember.CompanyMemberResearchDraft;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CompanyMemberResearchDraftRepository extends MongoRepository<CompanyMemberResearchDraft, String> {
    Optional<CompanyMemberResearchDraft> findByTaskId(Long taskId);
}
