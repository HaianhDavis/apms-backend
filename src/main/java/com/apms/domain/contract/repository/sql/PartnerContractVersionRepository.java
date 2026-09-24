package com.apms.domain.contract.repository.sql;

import com.apms.domain.contract.entity.PartnerContractVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PartnerContractVersionRepository extends JpaRepository<PartnerContractVersion, Long> {
    List<PartnerContractVersion> findByContractIdOrderByVersionDesc(Long contractId);
    java.util.Optional<PartnerContractVersion> findTopByContractIdOrderByVersionDesc(Long contractId);
    java.util.Optional<PartnerContractVersion> findByContractIdAndVersion(Long contractId, Integer version);
}
