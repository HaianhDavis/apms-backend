package com.apms.domain.contract.service;

import com.apms.common.enums.AuditAction;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.contract.entity.PartnerContractApprovalSyncRecord;
import com.apms.domain.contract.entity.PartnerContractExtractionDraft;
import com.apms.domain.contract.enums.ContractExtractionApprovalSyncStatus;
import com.apms.domain.contract.repository.mongo.PartnerContractExtractionDraftRepository;
import com.apms.domain.contract.repository.sql.PartnerContractApprovalSyncRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PartnerContractApprovalSyncService {

    private final PartnerContractApprovalSyncRepository syncRepository;
    private final PartnerContractExtractionDraftRepository draftRepository;
    private final AuditLogService auditService;

    private static final int MAX_RETRIES = 3;

    @Transactional
    public void processNextPendingSyncs() {
        String workerId = UUID.randomUUID().toString();

        List<PartnerContractApprovalSyncRecord> records = syncRepository.findProcessableRecords(
                List.of("PENDING", "RETRY"),
                LocalDateTime.now(),
                PageRequest.of(0, 10)
        );

        for (PartnerContractApprovalSyncRecord record : records) {
            try {
                processRecord(record, workerId);
            } catch (Exception e) {
                log.error("Unexpected error processing sync record {}", record.getId(), e);
            }
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processRecord(PartnerContractApprovalSyncRecord record, String workerId) {
        // Claim the record
        record.setStatus("PROCESSING");
        record.setLockedAt(LocalDateTime.now());
        record.setLockedBy(workerId);
        PartnerContractApprovalSyncRecord claimedRecord;
        try {
            claimedRecord = syncRepository.saveAndFlush(record);
        } catch (org.springframework.orm.ObjectOptimisticLockingFailureException e) {
            log.info("Record {} was claimed by another worker", record.getId());
            return;
        }

        try {
            PartnerContractExtractionDraft draft = draftRepository.findById(claimedRecord.getExtractionDraftId()).orElse(null);

            if (draft != null) {
                draft.setApprovalSyncStatus(ContractExtractionApprovalSyncStatus.SYNCED);
                draftRepository.save(draft);
                auditService.log(1L, AuditAction.PARTNER_CONTRACT_APPROVAL_SYNC_COMPLETED, "PartnerContractExtractionDraft", draft.getId(), "Successfully synced approval to Mongo");
            } else {
                log.warn("Draft not found for sync record {}", claimedRecord.getId());
            }

            claimedRecord.setStatus("COMPLETED");
            claimedRecord.setProcessedAt(LocalDateTime.now());
            syncRepository.save(claimedRecord);

        } catch (Exception e) {
            log.error("Failed to sync record {}", claimedRecord.getId(), e);
            claimedRecord.setRetryCount(claimedRecord.getRetryCount() + 1);
            claimedRecord.setLastError(e.getMessage() != null ? e.getMessage().substring(0, Math.min(e.getMessage().length(), 1000)) : "Unknown error");

            if (claimedRecord.getRetryCount() >= MAX_RETRIES) {
                claimedRecord.setStatus("FAILED");
                auditService.log(1L, AuditAction.PARTNER_CONTRACT_APPROVAL_SYNC_FAILED, "PartnerContractExtractionDraft", claimedRecord.getExtractionDraftId(), "Sync failed permanently after " + MAX_RETRIES + " retries");
            } else {
                claimedRecord.setStatus("RETRY");
                claimedRecord.setNextAttemptAt(LocalDateTime.now().plusMinutes(5 * claimedRecord.getRetryCount()));
            }

            claimedRecord.setLockedAt(null);
            claimedRecord.setLockedBy(null);
            syncRepository.save(claimedRecord);
        }
    }
}
