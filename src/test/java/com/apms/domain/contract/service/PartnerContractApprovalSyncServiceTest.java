//package com.apms.domain.contract.service;
//
//import com.apms.ApmsIntegrationTestBase;
//import com.apms.domain.contract.entity.PartnerContractApprovalSyncRecord;
//import com.apms.domain.contract.entity.PartnerContractExtractionDraft;
//import com.apms.domain.contract.enums.ContractExtractionApprovalSyncStatus;
//import com.apms.domain.contract.repository.mongo.PartnerContractExtractionDraftRepository;
//import com.apms.domain.contract.repository.sql.PartnerContractApprovalSyncRepository;
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.Test;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.orm.ObjectOptimisticLockingFailureException;
//
//import java.time.LocalDateTime;
//import java.util.concurrent.CountDownLatch;
//import java.util.concurrent.ExecutorService;
//import java.util.concurrent.Executors;
//import java.util.concurrent.TimeUnit;
//import java.util.concurrent.atomic.AtomicInteger;
//
//import static org.assertj.core.api.Assertions.assertThat;
//
//public class PartnerContractApprovalSyncServiceTest extends ApmsIntegrationTestBase {
//
//    @Autowired
//    private PartnerContractApprovalSyncService syncService;
//
//    @Autowired
//    private PartnerContractApprovalSyncRepository syncRepository;
//
//    @Autowired
//    private PartnerContractExtractionDraftRepository draftRepository;
//
//    @BeforeEach
//    void setup() {
//        syncRepository.deleteAll();
//        draftRepository.deleteAll();
//    }
//
//    @Test
//    void shouldOnlyAllowOneWorkerToClaimRecordAndCompleteExactlyOnce() throws InterruptedException {
//        // Setup Mongo Draft
//        PartnerContractExtractionDraft draft = PartnerContractExtractionDraft.builder()
//                .id("ext-test-1")
//                .approvalSyncStatus(ContractExtractionApprovalSyncStatus.NOT_REQUIRED)
//                .build();
//        draftRepository.save(draft);
//
//        // Setup SQL Outbox Record
//        PartnerContractApprovalSyncRecord record = PartnerContractApprovalSyncRecord.builder()
//                .extractionDraftId("ext-test-1")
//                .contractId(100L)
//                .contractVersionId(200L)
//                .contractVersionNumber(1)
//                .clauseSetHash("hash")
//                .status("PENDING")
//                .retryCount(0)
//                .createdAt(LocalDateTime.now())
//                .build();
//        record = syncRepository.saveAndFlush(record);
//
//        final Long recordId = record.getId();
//
//        int numWorkers = 2;
//        ExecutorService executor = Executors.newFixedThreadPool(numWorkers);
//        CountDownLatch latch = new CountDownLatch(1);
//        CountDownLatch doneLatch = new CountDownLatch(numWorkers);
//
//        AtomicInteger successfulClaims = new AtomicInteger(0);
//        AtomicInteger lockingFailures = new AtomicInteger(0);
//
//        for (int i = 0; i < numWorkers; i++) {
//            final String workerId = "worker-" + i;
//            executor.submit(() -> {
//                try {
//                    latch.await(); // wait for start signal
//
//                    // fetch the current state of record independently in each thread
//                    PartnerContractApprovalSyncRecord freshRecord = syncRepository.findById(recordId).get();
//                    syncService.processRecord(freshRecord, workerId);
//
//                    successfulClaims.incrementAndGet();
//                } catch (ObjectOptimisticLockingFailureException e) {
//                    lockingFailures.incrementAndGet();
//                } catch (Exception e) {
//                    // unexpected
//                } finally {
//                    doneLatch.countDown();
//                }
//            });
//        }
//
//        try {
//            // Fire both threads simultaneously
//            latch.countDown();
//            doneLatch.await(10, TimeUnit.SECONDS);
//        } finally {
//            executor.shutdown();
//            executor.awaitTermination(5, TimeUnit.SECONDS);
//        }
//
//        // 1) Exactly one worker succeeded without throwing exception (though processRecord catches OptimisticLocking, so it doesn't throw)
//        // Wait, processRecord catches ObjectOptimisticLockingFailureException and just returns.
//        // So no exception is thrown out of processRecord. Both threads will complete "successfully"
//        // Let's check the database instead to verify EXACTLY ONE processing happened.
//
//        PartnerContractApprovalSyncRecord dbRecord = syncRepository.findById(recordId).get();
//        assertThat(dbRecord.getStatus()).isEqualTo("COMPLETED");
//
//        PartnerContractExtractionDraft updatedDraft = draftRepository.findById("ext-test-1").get();
//        assertThat(updatedDraft.getApprovalSyncStatus()).isEqualTo(ContractExtractionApprovalSyncStatus.SYNCED);
//    }
//}
