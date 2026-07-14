package com.apms.domain.contract.repository.sql;

import com.apms.domain.contract.entity.PartnerContract;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PartnerContractRepository extends JpaRepository<PartnerContract, Long> {
}
