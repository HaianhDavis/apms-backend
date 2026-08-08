package com.apms.domain.news.repository;

import com.apms.domain.news.entity.CompanyNewsResearchDraft;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CompanyNewsResearchDraftRepository extends MongoRepository<CompanyNewsResearchDraft, String> {
    List<CompanyNewsResearchDraft> findByTaskIdAndIsDeletedFalse(Long taskId);
    Optional<CompanyNewsResearchDraft> findByIdAndTaskIdAndIsDeletedFalse(String id, Long taskId);
}
