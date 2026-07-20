package com.apms.domain.contract.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "apms.contract-approval-sync.enabled", havingValue = "true")
@RequiredArgsConstructor
public class PartnerContractApprovalSyncScheduler {

    private final PartnerContractApprovalSyncService syncService;

    @Scheduled(fixedDelay = 5000)
    public void scheduleSyncProcessing() {
        syncService.processNextPendingSyncs();
    }
}
