package com.apms.domain.contract.repository.sql;

import com.apms.domain.contract.entity.PartnerContractClauseVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PartnerContractClauseVersionRepository extends JpaRepository<PartnerContractClauseVersion, Long> {
    List<PartnerContractClauseVersion> findByPartnerContractVersionId(Long partnerContractVersionId);
    java.util.Optional<PartnerContractClauseVersion> findByIdAndPartnerContractVersionId(Long id, Long partnerContractVersionId);
}
