package com.apms.domain.contract.service;

import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.contract.enums.ContractExtractionReviewDecision;
import com.apms.domain.contract.enums.ContractExtractionApplicationStatus;
import com.apms.domain.contract.dto.ApplyExtractionRequest;
import com.apms.domain.contract.dto.ReviewPartnerContractRequest;
import com.apms.domain.contract.entity.PartnerContract;
import com.apms.domain.contract.entity.PartnerContractClauseVersion;
import com.apms.domain.contract.entity.PartnerContractExtractionDraft;
import com.apms.domain.contract.enums.ContractReviewStatus;
import com.apms.domain.contract.repository.mongo.PartnerContractExtractionDraftRepository;
import com.apms.domain.contract.repository.sql.PartnerContractClauseVersionRepository;
import com.apms.domain.contract.repository.sql.PartnerContractRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.apms.ApmsIntegrationTestBase;

@SpringBootTest
public class PartnerContractExtractionIntegrationTest extends ApmsIntegrationTestBase {

    @Autowired
    private PartnerContractService contractService;

    @Autowired
    private PartnerContractExtractionService extractionService;

    @Autowired
    private PartnerContractRepository contractRepository;

    @Autowired
    private PartnerContractExtractionDraftRepository draftRepository;

    @SpyBean
    private PartnerContractClauseVersionRepository clauseVersionRepository;

    @Autowired
    private com.apms.domain.contract.repository.sql.PartnerContractApprovalSyncRepository syncRepository;

    @Autowired
    private com.apms.domain.user.repository.sql.AccountRepository accountRepository;

    @Autowired
    private com.apms.domain.project.repository.sql.ProjectRepository projectRepository;

    @Autowired
    private com.apms.domain.project.repository.sql.ProjectMemberRepository projectMemberRepository;

    @Autowired
    private com.apms.domain.audit.repository.sql.AuditLogRepository auditRepository;

    @BeforeEach
    void setup() {
        draftRepository.deleteAll();
        syncRepository.deleteAll();
        auditRepository.deleteAll();
    }

    private Long setupProjectAndManager() {
        com.apms.domain.user.Account acc = new com.apms.domain.user.Account();
        acc.setEmail("manager_" + System.currentTimeMillis() + "@test.com");
        acc.setPasswordHash("hash");
        acc = accountRepository.saveAndFlush(acc);
        Long approverId = acc.getId();

        com.apms.domain.project.Project proj = new com.apms.domain.project.Project();
        proj.setProjectName("Test Project");
        proj.setTargetCompanyProfileId("TARGET_123");
        proj.setTargetCompanyName("Target Company Name");
        proj.setCreatedByAccount(acc);
        proj.setProjectType(com.apms.common.enums.ProjectType.RESEARCH_NEW_COMPANY);
        proj = projectRepository.saveAndFlush(proj);

        com.apms.domain.project.ProjectMember member = new com.apms.domain.project.ProjectMember();
        member.setProject(proj);
        member.setAccount(acc);
        member.setMemberRole(com.apms.common.enums.MemberRole.MANAGER);
        projectMemberRepository.saveAndFlush(member);

        return approverId;
    }

    @Test
    @Transactional
    void shouldApproveManuallyWithoutExtraction() {
        Long approverId = setupProjectAndManager();
        Long projId = projectRepository.findAll().stream().filter(p -> p.getCreatedByAccount().getId().equals(approverId)).findFirst().get().getId();

        PartnerContract contract = PartnerContract.builder()
                .referenceCompanyId("COMPANY_REF")
                .partnerCompanyId("COMPANY_PARTNER")
                .sourceProjectId(projId)
                .reviewStatus(ContractReviewStatus.IN_REVIEW)
                .currentVersion(0)
                .createdByAccountId(100L)
                .version(1)
                .contractNumber("CNT-MANUAL")
                .build();
        contract = contractRepository.saveAndFlush(contract);

        ReviewPartnerContractRequest req = new ReviewPartnerContractRequest();
        req.setDecision("APPROVE");

        contractService.reviewContract(contract.getId(), req, approverId);

        List<PartnerContractClauseVersion> clauses = contractService.getApprovedClauses(contract.getId(), 1, approverId);
        assertThat(clauses).isEmpty();

        long outboxCount = syncRepository.count();
        assertThat(outboxCount).isEqualTo(0);

        long clauseCount = clauseVersionRepository.count();
        assertThat(clauseCount).isEqualTo(0);

        long versionCount = contractRepository.findById(contract.getId()).get().getCurrentVersion();
        assertThat(versionCount).isEqualTo(1);
    }

    @Test
    @Transactional
    void shouldApproveWithAppliedExtraction() {
        Long approverId = setupProjectAndManager();
        Long projId = projectRepository.findAll().stream().filter(p -> p.getCreatedByAccount().getId().equals(approverId)).findFirst().get().getId();

        PartnerContract contract = PartnerContract.builder()
                .referenceCompanyId("COMPANY_REF")
                .partnerCompanyId("COMPANY_PARTNER")
                .sourceProjectId(projId)
                .reviewStatus(ContractReviewStatus.IN_REVIEW)
                .currentVersion(0)
                .createdByAccountId(100L)
                .version(1)
                .contractNumber("CNT-EXTRACT")
                .pendingExtractionId("ext-123")
                .pendingClauseSetHash("mock-hash-123")
                .build();
        contract = contractRepository.saveAndFlush(contract);

        PartnerContractExtractionDraft.ClauseCandidate clause = new PartnerContractExtractionDraft.ClauseCandidate();
        clause.setClauseCandidateId("clause-1");
        clause.setReviewDecision(ContractExtractionReviewDecision.ACCEPT);
        clause.setClauseTitle("Liability");

        PartnerContractExtractionDraft draft = PartnerContractExtractionDraft.builder()
                .id("ext-123")
                .partnerContractId(contract.getId())
                .applicationStatus(ContractExtractionApplicationStatus.APPLIED_FROZEN)
                .clauseSetHash("mock-hash-123")
                .clauseCandidates(List.of(clause))
                .build();
        draftRepository.save(draft);

        ReviewPartnerContractRequest req = new ReviewPartnerContractRequest();
        req.setDecision("APPROVE");

        contractService.reviewContract(contract.getId(), req, approverId);

        List<PartnerContractClauseVersion> clauses = contractService.getApprovedClauses(contract.getId(), 1, approverId);
        assertThat(clauses).hasSize(1);
        assertThat(clauses.get(0).getClauseIdentity()).isEqualTo("clause-1");

        long outboxCount = syncRepository.count();
        assertThat(outboxCount).isEqualTo(1);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void shouldRollbackApprovalOnClauseFailure() {
        Long approverId = setupProjectAndManager();
        Long projId = projectRepository.findAll().stream().filter(p -> p.getCreatedByAccount().getId().equals(approverId)).findFirst().get().getId();

        PartnerContract contract = PartnerContract.builder()
                .referenceCompanyId("COMPANY_REF")
                .partnerCompanyId("COMPANY_PARTNER")
                .sourceProjectId(projId)
                .reviewStatus(ContractReviewStatus.IN_REVIEW)
                .currentVersion(0)
                .createdByAccountId(100L)
                .version(1)
                .contractNumber("CNT-FAIL")
                .pendingExtractionId("ext-fail")
                .pendingClauseSetHash("mock-hash-fail")
                .build();
        PartnerContract savedContract = contractRepository.saveAndFlush(contract);
        final Long contractId = savedContract.getId();

        PartnerContractExtractionDraft.ClauseCandidate clause = new PartnerContractExtractionDraft.ClauseCandidate();
        clause.setClauseCandidateId("clause-fail");
        clause.setReviewDecision(ContractExtractionReviewDecision.ACCEPT);
        clause.setClauseTitle("Fail");

        PartnerContractExtractionDraft draft = PartnerContractExtractionDraft.builder()
                .id("ext-fail")
                .partnerContractId(contractId)
                .applicationStatus(ContractExtractionApplicationStatus.APPLIED_FROZEN)
                .clauseSetHash("mock-hash-fail")
                .clauseCandidates(List.of(clause))
                .build();
        draftRepository.save(draft);

        org.mockito.Mockito.doThrow(new RuntimeException("Forced DB Error"))
                .when(clauseVersionRepository).save(org.mockito.ArgumentMatchers.any());

        ReviewPartnerContractRequest req = new ReviewPartnerContractRequest();
        req.setDecision("APPROVE");

        assertThatThrownBy(() -> contractService.reviewContract(contractId, req, approverId))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Forced DB Error");

        // Verify Rollback
        PartnerContract dbContract = contractRepository.findById(contractId).get();
        assertThat(dbContract.getReviewStatus()).isEqualTo(ContractReviewStatus.IN_REVIEW);
        assertThat(dbContract.getCurrentVersion()).isEqualTo(0);

        assertThat(clauseVersionRepository.count()).isEqualTo(0);
        assertThat(syncRepository.count()).isEqualTo(0);
    }

    @Test
    @Transactional
    void shouldBeIdempotentOnRepeatedApproval() {
        Long approverId = setupProjectAndManager();
        Long projId = projectRepository.findAll().stream().filter(p -> p.getCreatedByAccount().getId().equals(approverId)).findFirst().get().getId();

        PartnerContract contract = PartnerContract.builder()
                .referenceCompanyId("COMPANY_REF")
                .partnerCompanyId("COMPANY_PARTNER")
                .sourceProjectId(projId)
                .reviewStatus(ContractReviewStatus.IN_REVIEW)
                .currentVersion(0)
                .createdByAccountId(100L)
                .version(1)
                .contractNumber("CNT-IDEMPOTENT")
                .pendingExtractionId("ext-idem")
                .pendingClauseSetHash("hash-idem")
                .build();
        contract = contractRepository.saveAndFlush(contract);

        PartnerContractExtractionDraft.ClauseCandidate clause = new PartnerContractExtractionDraft.ClauseCandidate();
        clause.setClauseCandidateId("c-idem");
        clause.setReviewDecision(ContractExtractionReviewDecision.ACCEPT);
        clause.setClauseTitle("Title Idem");

        PartnerContractExtractionDraft draft = PartnerContractExtractionDraft.builder()
                .id("ext-idem")
                .partnerContractId(contract.getId())
                .applicationStatus(ContractExtractionApplicationStatus.APPLIED_FROZEN)
                .clauseSetHash("hash-idem")
                .clauseCandidates(List.of(clause))
                .build();
        draftRepository.save(draft);

        ReviewPartnerContractRequest req = new ReviewPartnerContractRequest();
        req.setDecision("APPROVE");

        // First approval
        contractService.reviewContract(contract.getId(), req, approverId);

        PartnerContract firstDbContract = contractRepository.findById(contract.getId()).get();
        LocalDateTime firstApprovedAt = firstDbContract.getApprovedAt();
        Long firstApprovedBy = firstDbContract.getApprovedByAccountId();

        // Second approval (should be no-op)
        contractService.reviewContract(contract.getId(), req, approverId);

        // Verify exactly one iteration of side-effects
        PartnerContract dbContract = contractRepository.findById(contract.getId()).get();
        assertThat(dbContract.getCurrentVersion()).isEqualTo(1);
        assertThat(dbContract.getApprovedAt()).isEqualTo(firstApprovedAt);
        assertThat(dbContract.getApprovedByAccountId()).isEqualTo(firstApprovedBy);

        long clauseCount = clauseVersionRepository.count();
        assertThat(clauseCount).isEqualTo(1);

        long outboxCount = syncRepository.count();
        assertThat(outboxCount).isEqualTo(1);

        // Check Audits
        long contractApprovedCount = auditRepository.findAll().stream()
                .filter(a -> a.getAction() == com.apms.common.enums.AuditAction.PARTNER_CONTRACT_APPROVED)
                .count();
        assertThat(contractApprovedCount).isEqualTo(1);

        long clausesApprovedCount = auditRepository.findAll().stream()
                .filter(a -> a.getAction() == com.apms.common.enums.AuditAction.PARTNER_CONTRACT_CLAUSES_APPROVED)
                .count();
        assertThat(clausesApprovedCount).isEqualTo(1);
    }
}
