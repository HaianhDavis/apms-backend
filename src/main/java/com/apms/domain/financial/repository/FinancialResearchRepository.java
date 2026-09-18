package com.apms.domain.financial.repository;

import com.apms.domain.financial.FinancialResearch;
import com.apms.domain.financial.FinancialResearchStatus;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FinancialResearchRepository extends MongoRepository<FinancialResearch, String> {
    Optional<FinancialResearch> findByTaskId(Long taskId);
    List<FinancialResearch> findByCompanyProfileIdAndStatus(String companyProfileId, FinancialResearchStatus status);
    List<FinancialResearch> findByCompanyProfileIdInAndStatus(java.util.Collection<String> companyProfileIds, FinancialResearchStatus status);
    List<FinancialResearch> findByProjectId(Long projectId);
    boolean existsByTaskId(Long taskId);
}
