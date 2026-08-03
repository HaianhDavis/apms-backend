package com.apms.domain.news.repository;

import com.apms.domain.news.entity.CompanyIntelligenceArticle;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CompanyIntelligenceArticleRepository extends MongoRepository<CompanyIntelligenceArticle, String> {
    Page<CompanyIntelligenceArticle> findByCompanyProfileIdAndIsDeletedFalseAndApprovedAtIsNotNull(String companyProfileId, Pageable pageable);
    Optional<CompanyIntelligenceArticle> findByIdAndCompanyProfileIdAndIsDeletedFalseAndApprovedAtIsNotNull(String id, String companyProfileId);
    boolean existsBySourceDraftId(String sourceDraftId);
}
