package com.apms.domain.monitoring.repository;

import com.apms.common.enums.RelationshipChangeStatus;
import com.apms.domain.monitoring.model.CompanyRelationshipChangeProposal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CompanyRelationshipChangeProposalRepository extends JpaRepository<CompanyRelationshipChangeProposal, Long> {
    
    boolean existsByCompanyProfileIdAndStatus(String companyProfileId, RelationshipChangeStatus status);
    
    List<CompanyRelationshipChangeProposal> findByCompanyProfileIdAndStatus(String companyProfileId, RelationshipChangeStatus status);
}
