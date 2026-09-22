package com.apms.domain.contract.repository.mongo;

import com.apms.domain.contract.enums.ContractResearchStatus;
import com.apms.domain.contract.model.ContractResearch;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ContractResearchRepository extends MongoRepository<ContractResearch, String> {
    Optional<ContractResearch> findByTaskId(Long taskId);
    List<ContractResearch> findByCompanyProfileIdAndStatus(String companyProfileId, ContractResearchStatus status);
    List<ContractResearch> findByCompanyProfileIdInAndStatus(java.util.Collection<String> companyProfileIds, ContractResearchStatus status);
    List<ContractResearch> findByProjectIdAndStatus(Long projectId, ContractResearchStatus status);
}
