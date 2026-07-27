package com.apms.domain.rolemetric.service;

import com.apms.ApmsIntegrationTestBase;
import com.apms.domain.project.Project;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.apms.domain.user.Account;
import com.apms.domain.user.repository.sql.AccountRepository;
import com.apms.domain.rolemetric.dto.CreateRoleMetricRequest;
import com.apms.domain.rolemetric.dto.ReviewRoleMetricRequest;
import com.apms.domain.rolemetric.dto.RoleMetricResponse;
import com.apms.domain.rolemetric.entity.RoleMetricRecord;
import com.apms.domain.rolemetric.enums.RoleMetricReviewDecision;
import com.apms.domain.rolemetric.repository.RoleMetricRecordRepository;
import com.apms.domain.rolemetric.repository.RoleMetricRecordVersionRepository;
import com.apms.domain.rolemetric.repository.RoleMetricEvidenceVersionRepository;
import com.apms.security.UserDetailsImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class RoleMetricRecordIntegrationTest extends ApmsIntegrationTestBase {

    @Autowired private RoleMetricRecordService service;
    @Autowired private RoleMetricRecordRepository repository;
    @Autowired private RoleMetricRecordVersionRepository versionRepo;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean private RoleMetricEvidenceVersionRepository evVersionRepo;
    @Autowired private com.apms.domain.rolemetric.repository.RoleMetricEvidenceRepository evidenceRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private com.apms.domain.score.repository.sql.ScoreSnapshotRepository scoreSnapshotRepository;
    @Autowired private com.apms.domain.score.repository.sql.RoleCriterionRuleRepository roleCriterionRuleRepository;
    @Autowired private com.apms.domain.profile.repository.mongo.CompanyProfileRepository companyProfileRepository;
    @Autowired private com.apms.domain.audit.repository.sql.AuditLogRepository auditLogRepository;

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean private com.apms.domain.graph.repository.neo4j.CompanyNodeRepository companyNodeRepository;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean private com.apms.domain.ai.service.AiExtractionService aiExtractionService;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean private com.apms.domain.score.service.ScoreService scoreService;

    private Long projectId;
    private Account account;

    @BeforeEach
    void setup() {
        String email = "integration2_" + java.util.UUID.randomUUID().toString() + "@test.com";
        account = new Account();
        account.setEmail(email);
        account.setPasswordHash("hash");
        account.setIsActive(true);
        account = accountRepository.saveAndFlush(account);

        UserDetailsImpl userDetails = new UserDetailsImpl(account.getId(), email, "password", Collections.emptyList(), true);
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
        try {
            org.springframework.security.test.context.TestSecurityContextHolder.getContext().setAuthentication(auth);
        } catch (Throwable t) {}

        Project project = new Project();
        project.setProjectName("Test Project 2");
        project.setProjectType(com.apms.common.enums.ProjectType.UPDATE_EXISTING_COMPANY);
        project.setStatus(com.apms.common.enums.ProjectStatus.DRAFT);
        project.setCreatedByAccount(account);
        project.setTargetCompanyName("Test Company");
        project.setTargetCompanyProfileId("comp-1");
        project.setTargetRelationshipType(com.apms.common.enums.RelationshipType.PARTNER_WITH);
        project = projectRepository.saveAndFlush(project);
        projectId = project.getId();
    }

    @Test
    void testConcurrentExactDuplicate() throws Exception {
        int threads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        Callable<Void> task = () -> {
            try {
                // Must set security context in each thread
                UserDetailsImpl userDetails = new UserDetailsImpl(account.getId(), account.getEmail(), "password", Collections.emptyList(), true);
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities()));

                CreateRoleMetricRequest req = new CreateRoleMetricRequest();
                req.setMetricKey("revenue_generated");
                req.setPeriodStart(LocalDate.of(2025, 1, 1));
                req.setPeriodEnd(LocalDate.of(2025, 12, 31));
                req.setTargetNumericValue(new BigDecimal("1000"));

                latch.await(); // wait for all threads
                service.createDraft(projectId, req);
                successCount.incrementAndGet();
            } catch (Exception e) {
                failCount.incrementAndGet();
            }
            return null;
        };

        Future<Void> f1 = executor.submit(task);
        Future<Void> f2 = executor.submit(task);
        latch.countDown(); // release
        f1.get(); f2.get();

        assertEquals(1, successCount.get(), "Only one insert should succeed for exact duplicates");
        assertEquals(1, failCount.get());

        long count = repository.findAll().stream()
            .filter(r -> r.getProjectId().equals(projectId) && "revenue_generated".equals(r.getMetricKey()))
            .count();
        assertEquals(1, count, "Exactly one matching SQL row should exist");
    }

    @Test
    void testConcurrentNonIdenticalOverlap() throws Exception {
        int threads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        Callable<Void> task1 = () -> {
            try {
                UserDetailsImpl userDetails = new UserDetailsImpl(account.getId(), account.getEmail(), "password", Collections.emptyList(), true);
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities()));

                CreateRoleMetricRequest req = new CreateRoleMetricRequest();
                req.setMetricKey("revenue_generated");
                req.setPeriodStart(LocalDate.of(2025, 7, 1));
                req.setPeriodEnd(LocalDate.of(2025, 7, 31));
                req.setTargetNumericValue(new BigDecimal("1000"));
                latch.await();
                service.createDraft(projectId, req);
                successCount.incrementAndGet();
            } catch (Exception e) {
                failCount.incrementAndGet();
            }
            return null;
        };

        Callable<Void> task2 = () -> {
            try {
                UserDetailsImpl userDetails = new UserDetailsImpl(account.getId(), account.getEmail(), "password", Collections.emptyList(), true);
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities()));

                CreateRoleMetricRequest req = new CreateRoleMetricRequest();
                req.setMetricKey("revenue_generated");
                req.setPeriodStart(LocalDate.of(2025, 7, 15));
                req.setPeriodEnd(LocalDate.of(2025, 8, 15));
                req.setTargetNumericValue(new BigDecimal("1000"));
                latch.await();
                service.createDraft(projectId, req);
                successCount.incrementAndGet();
            } catch (Exception e) {
                failCount.incrementAndGet();
            }
            return null;
        };

        Future<Void> f1 = executor.submit(task1);
        Future<Void> f2 = executor.submit(task2);
        latch.countDown();
        f1.get(); f2.get();

        assertEquals(1, successCount.get(), "Only one insert should succeed for overlapping ranges");
        assertEquals(1, failCount.get());

        long count = repository.findAll().stream()
            .filter(r -> r.getProjectId().equals(projectId) && "revenue_generated".equals(r.getMetricKey()))
            .count();
        assertEquals(1, count, "Exactly one matching SQL row should exist");
    }

    @Test
    void testPointInTimeDuplicate() throws Exception {
        int threads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        Callable<Void> task = () -> {
            try {
                UserDetailsImpl userDetails = new UserDetailsImpl(account.getId(), account.getEmail(), "password", Collections.emptyList(), true);
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities()));

                CreateRoleMetricRequest req = new CreateRoleMetricRequest();
                req.setMetricKey("nps_score");
                req.setMeasurementDate(LocalDate.of(2025, 6, 1));
                req.setTargetNumericValue(new BigDecimal("50"));
                latch.await();
                service.createDraft(projectId, req);
                successCount.incrementAndGet();
            } catch (Exception e) {
                failCount.incrementAndGet();
            }
            return null;
        };

        Future<Void> f1 = executor.submit(task);
        Future<Void> f2 = executor.submit(task);
        latch.countDown();
        f1.get(); f2.get();

        assertEquals(1, successCount.get(), "Exactly one concurrent operation should succeed");
        assertEquals(1, failCount.get(), "Exactly one concurrent operation should fail");

        List<RoleMetricRecord> records = repository.findAll().stream()
            .filter(r -> r.getProjectId().equals(projectId) && "nps_score".equals(r.getMetricKey()))
            .collect(Collectors.toList());

        assertEquals(1, records.size(), "Exactly one matching SQL row should exist");
        assertEquals("AT:2025-06-01", records.get(0).getPeriodKey(), "Persisted periodKey should be AT:2025-06-01");
    }

    @Test
    @Transactional
    void testFullLifecycleAndApproval() {
        long snapshotsBefore = scoreSnapshotRepository.count();
        long rulesBefore = roleCriterionRuleRepository.count();
        long profilesBefore = companyProfileRepository.count();
        long versionsBefore = versionRepo.count();

        CreateRoleMetricRequest req = new CreateRoleMetricRequest();
        req.setMetricKey("revenue_generated");
        req.setPeriodStart(LocalDate.of(2026, 1, 1));
        req.setPeriodEnd(LocalDate.of(2026, 12, 31));
        req.setTargetNumericValue(new BigDecimal("1000"));
        req.setCurrencyCode("USD");

        RoleMetricResponse draft = service.createDraft(projectId, req);

        com.apms.domain.rolemetric.entity.RoleMetricEvidence evidence = new com.apms.domain.rolemetric.entity.RoleMetricEvidence();
        evidence.setRoleMetricRecordId(draft.getId());
        evidence.setValueScope(com.apms.domain.rolemetric.enums.RoleMetricEvidenceValueScope.BOTH);
        evidence.setSourceType(com.apms.domain.rolemetric.enums.RoleMetricEvidenceSourceType.MANUAL_NOTE);
        evidence.setCreatedByAccountId(account.getId());
        evidence.setUpdatedByAccountId(account.getId());
        evidence.setCreatedAt(java.time.LocalDateTime.now());
        evidence.setUpdatedAt(java.time.LocalDateTime.now());
        evidenceRepository.save(evidence);

        service.submitForReview(projectId, draft.getId());

        ReviewRoleMetricRequest reviewReq = new ReviewRoleMetricRequest();
        reviewReq.setDecision(RoleMetricReviewDecision.APPROVE);
        RoleMetricResponse approved = service.reviewMetric(projectId, draft.getId(), reviewReq);

        assertEquals("APPROVED", approved.getStatus().name());
        assertNotNull(approved.getCurrentApprovedVersionId());

        RoleMetricRecord record = repository.findById(draft.getId()).orElseThrow();
        assertEquals(1, record.getCurrentApprovedVersionNumber());
        assertEquals(versionsBefore + 1, versionRepo.count());

        assertEquals(snapshotsBefore, scoreSnapshotRepository.count());
        assertEquals(rulesBefore, roleCriterionRuleRepository.count());
        assertEquals(profilesBefore, companyProfileRepository.count());

        // Assert no Neo4j relationship mutation or AI classification occurred
        org.mockito.Mockito.verifyNoInteractions(companyNodeRepository);
        org.mockito.Mockito.verifyNoInteractions(aiExtractionService);
        org.mockito.Mockito.verifyNoInteractions(scoreService);

        // Repeated approve
        service.reviewMetric(projectId, draft.getId(), reviewReq);
        assertEquals(versionsBefore + 1, versionRepo.count()); // idempotent
    }

    @Test
    void testRollbackOnSnapshotFailure() {
        long versionsBefore = versionRepo.count();
        long evVersionsBefore = evVersionRepo.count();

        CreateRoleMetricRequest req = new CreateRoleMetricRequest();
        req.setMetricKey("revenue_generated");
        req.setPeriodStart(LocalDate.of(2027, 1, 1));
        req.setPeriodEnd(LocalDate.of(2027, 12, 31));
        req.setTargetNumericValue(new BigDecimal("2000"));

        RoleMetricResponse draft = service.createDraft(projectId, req);

        com.apms.domain.rolemetric.entity.RoleMetricEvidence evidence = new com.apms.domain.rolemetric.entity.RoleMetricEvidence();
        evidence.setRoleMetricRecordId(draft.getId());
        evidence.setValueScope(com.apms.domain.rolemetric.enums.RoleMetricEvidenceValueScope.BOTH);
        evidence.setSourceType(com.apms.domain.rolemetric.enums.RoleMetricEvidenceSourceType.MANUAL_NOTE);
        // Force SQL failure: document_hash is VARCHAR(64)
        evidence.setDocumentHash("A".repeat(100));
        evidence.setCreatedByAccountId(account.getId());
        evidence.setUpdatedByAccountId(account.getId());
        evidence.setCreatedAt(java.time.LocalDateTime.now());
        evidence.setUpdatedAt(java.time.LocalDateTime.now());
        evidenceRepository.save(evidence);

        service.submitForReview(projectId, draft.getId());

        long auditsBeforeReview = auditLogRepository.count();

        ReviewRoleMetricRequest reviewReq = new ReviewRoleMetricRequest();
        reviewReq.setDecision(RoleMetricReviewDecision.APPROVE);

        // Simulating SQL error on snapshot save
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataIntegrityViolationException("Simulated SQL duplicate error"))
                .when(evVersionRepo).save(org.mockito.ArgumentMatchers.any());

        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () ->
            service.reviewMetric(projectId, draft.getId(), reviewReq)
        );

        // Verify rollback using fresh transaction/query - requires clearing persistence context since this test is NOT @Transactional at method level
        // But since service.reviewMetric ran in its own transaction, our current thread has no active transaction and no first-level cache for this record!
        // We can just fetch it directly.
        RoleMetricRecord record = repository.findById(draft.getId()).orElseThrow();
        assertEquals(com.apms.domain.rolemetric.enums.RoleMetricStatus.SUBMITTED, record.getStatus(), "Should rollback to SUBMITTED");
        assertNull(record.getCurrentApprovedVersionId(), "currentApprovedVersionId should remain unchanged (null)");
        assertNull(record.getCurrentApprovedVersionNumber(), "currentApprovedVersionNumber should remain unchanged (null)");
        assertEquals(1, evidenceRepository.count(), "Draft evidence should still exist");
        assertEquals(versionsBefore, versionRepo.count(), "No version should be saved");
        assertEquals(evVersionsBefore, evVersionRepo.count(), "No EvidenceVersion should be saved");
        assertEquals(auditsBeforeReview, auditLogRepository.count(), "No ROLE_METRIC_APPROVED audit should be saved");
    }
}
