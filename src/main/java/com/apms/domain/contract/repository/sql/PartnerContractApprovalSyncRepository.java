package com.apms.domain.contract.repository.sql;

import com.apms.domain.contract.entity.PartnerContractApprovalSyncRecord;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface PartnerContractApprovalSyncRepository extends JpaRepository<PartnerContractApprovalSyncRecord, Long> {

    @Query("SELECT r FROM PartnerContractApprovalSyncRecord r WHERE r.status IN :statuses AND (r.nextAttemptAt IS NULL OR r.nextAttemptAt <= :now) ORDER BY r.createdAt ASC")
    List<PartnerContractApprovalSyncRecord> findProcessableRecords(@Param("statuses") List<String> statuses, @Param("now") LocalDateTime now, Pageable pageable);

}
