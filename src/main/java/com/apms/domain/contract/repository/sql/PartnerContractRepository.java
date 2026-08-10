package com.apms.domain.contract.repository.sql;

import com.apms.domain.contract.entity.PartnerContract;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PartnerContractRepository extends JpaRepository<PartnerContract, Long> {
    java.util.Optional<PartnerContract> findByExtractionDraftId(String extractionDraftId);
    org.springframework.data.domain.Page<PartnerContract> findByPartnerCompanyIdAndReviewStatus(String partnerCompanyId, com.apms.domain.contract.enums.ContractReviewStatus reviewStatus, org.springframework.data.domain.Pageable pageable);
    java.util.List<PartnerContract> findByPartnerCompanyIdAndReviewStatus(String partnerCompanyId, com.apms.domain.contract.enums.ContractReviewStatus reviewStatus);

    java.util.List<PartnerContract> findByReferenceCompanyIdAndPartnerCompanyIdInAndReviewStatusAndLifecycleStatus(
            String referenceCompanyId, 
            java.util.Collection<String> partnerCompanyIds, 
            com.apms.domain.contract.enums.ContractReviewStatus reviewStatus, 
            com.apms.domain.contract.enums.ContractLifecycleStatus lifecycleStatus
    );
}
