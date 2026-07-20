package com.apms.domain.contract.service;

import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.contract.config.ContractExtractionProperties;
import com.apms.domain.contract.entity.PartnerContractExtractionDraft.ContractExtractionFieldResult;
import com.apms.domain.contract.dto.ApplyExtractionRequest;
import com.apms.domain.contract.dto.ReviewExtractionClauseRequest;
import com.apms.domain.contract.dto.ReviewExtractionFieldRequest;
import com.apms.domain.contract.entity.PartnerContract;
import com.apms.domain.contract.entity.PartnerContractExtractionDraft;
import com.apms.domain.contract.entity.PartnerContractExtractionDraft.ClauseCandidate;
import com.apms.domain.contract.enums.ContractExtractionApplicationStatus;
import com.apms.domain.contract.enums.ContractExtractionReviewDecision;
import com.apms.domain.contract.enums.ContractExtractionReviewStatus;
import com.apms.domain.contract.enums.ContractReviewStatus;
import com.apms.domain.contract.repository.mongo.PartnerContractExtractionDraftRepository;
import com.apms.domain.contract.repository.sql.PartnerContractRepository;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.apms.domain.audit.service.AuditLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class PartnerContractExtractionServiceTest {

    @InjectMocks
    private PartnerContractExtractionService extractionService;

    @Mock
    private PartnerContractExtractionDraftRepository draftRepository;

    @Mock
    private PartnerContractRepository contractRepository;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private com.apms.domain.document.repository.mongo.RawDocumentRepository rawDocumentRepository;

    @Mock
    private AuditLogService auditService;

    @Mock
    private ContractExtractionProperties config;

    private PartnerContractExtractionDraft draft;
    private PartnerContract contract;
    private ContractExtractionFieldResult field;
    private ClauseCandidate clause;

    @BeforeEach
    void setup() {
        lenient().when(config.getApplyRecoveryTimeoutSeconds()).thenReturn(300);
        lenient().when(config.getSegmentChars()).thenReturn(5000);
        lenient().when(config.getSegmentOverlapChars()).thenReturn(200);
        lenient().when(config.getMaxSegments()).thenReturn(20);
        lenient().when(config.getMaxTotalChars()).thenReturn(100_000);
        lenient().when(config.getMaxClauses()).thenReturn(200);
        lenient().when(config.getProviderTimeoutSeconds()).thenReturn(30);
        lenient().when(config.getMaxRetries()).thenReturn(3);

        field = new ContractExtractionFieldResult();
        field.setFieldName("contractTitle");
        field.setNormalizedValue("Old Title");

        clause = new ClauseCandidate();
        clause.setClauseCandidateId("c1");
        clause.setClauseTitle("SLA");

        draft = new PartnerContractExtractionDraft();
        draft.setId("ext-1");
        draft.setPartnerContractId(100L);
        draft.setSourceProjectId(999L);
        draft.setContractVersionAtGeneration(1);
        draft.setExpectedNextApprovalVersion(2);
        draft.setCurrentApprovedVersion(1);
        draft.setSourceDocumentHash("hash-1");
        draft.setReviewStatus(ContractExtractionReviewStatus.REVIEWED);
        draft.setApplicationStatus(ContractExtractionApplicationStatus.NOT_APPLIED);

        List<ContractExtractionFieldResult> fields = new ArrayList<>();
        fields.add(field);
        draft.setFieldResults(fields);

        List<ClauseCandidate> clauses = new ArrayList<>();
        clauses.add(clause);
        draft.setClauseCandidates(clauses);

        contract = new PartnerContract();
        contract.setId(100L);
        contract.setVersion(1);
        contract.setSourceProjectId(999L);
        contract.setReviewStatus(ContractReviewStatus.DRAFT);
        contract.setContractTitle("Existing Title");
    }

    private void mockAccess() {
        when(projectRepository.existsByIdAndMembersAccountId(999L, 1L)).thenReturn(true);
    }

    @Test
    void shouldAcceptUsingNormalizedValue() {
        mockAccess();
        when(draftRepository.findByIdAndPartnerContractId("ext-1", 100L)).thenReturn(Optional.of(draft));
        when(draftRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ReviewExtractionFieldRequest req = new ReviewExtractionFieldRequest();
        req.setReviewDecision(ContractExtractionReviewDecision.ACCEPT);

        PartnerContractExtractionDraft res = extractionService.reviewField(100L, "ext-1", "contractTitle", req, 1L);

        assertThat(res.getFieldResults().get(0).getReviewDecision()).isEqualTo(ContractExtractionReviewDecision.ACCEPT);
        assertThat(res.getFieldResults().get(0).getReviewedValue()).isNull();
    }

    @Test
    void shouldEditRequireReviewedValue() {
        mockAccess();
        when(draftRepository.findByIdAndPartnerContractId("ext-1", 100L)).thenReturn(Optional.of(draft));

        ReviewExtractionFieldRequest req = new ReviewExtractionFieldRequest();
        req.setReviewDecision(ContractExtractionReviewDecision.EDIT);

        assertThatThrownBy(() -> extractionService.reviewField(100L, "ext-1", "contractTitle", req, 1L))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("EDIT decision requires a reviewedValue");
    }

    @Test
    void shouldEditClauseRequireComment() {
        mockAccess();
        when(draftRepository.findByIdAndPartnerContractId("ext-1", 100L)).thenReturn(Optional.of(draft));

        ReviewExtractionClauseRequest req = new ReviewExtractionClauseRequest();
        req.setReviewDecision(ContractExtractionReviewDecision.EDIT);

        assertThatThrownBy(() -> extractionService.reviewClause(100L, "ext-1", "c1", req, 1L))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("EDIT decision requires a reviewComment");
    }

    @Test
    void shouldRequireTerminalDecisionsBeforeApply() {
        mockAccess();
        when(draftRepository.findByIdAndPartnerContractId("ext-1", 100L)).thenReturn(Optional.of(draft));

        draft.setReviewStatus(ContractExtractionReviewStatus.PENDING); // Not REVIEWED

        ApplyExtractionRequest req = new ApplyExtractionRequest();

        assertThatThrownBy(() -> extractionService.applyExtraction(100L, "ext-1", req, 1L))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("All fields and clauses must have a terminal decision");
    }

    @Test
    void shouldRequireOverwriteConfirmation() {
        mockAccess();
        when(draftRepository.findByIdAndPartnerContractId("ext-1", 100L)).thenReturn(Optional.of(draft));
        when(contractRepository.findById(100L)).thenReturn(Optional.of(contract));
        when(draftRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        field.setReviewDecision(ContractExtractionReviewDecision.ACCEPT);
        clause.setReviewDecision(ContractExtractionReviewDecision.ACCEPT);

        ApplyExtractionRequest req = new ApplyExtractionRequest();
        req.setExpectedContractOptimisticVersion(1);
        req.setExpectedSourceDocumentHash("hash-1");
        req.setExpectedCurrentApprovedVersion(1);
        req.setExpectedNextApprovalVersion(2);
        req.setConfirmedOverwriteFieldKeys(List.of());

        assertThatThrownBy(() -> extractionService.applyExtraction(100L, "ext-1", req, 1L))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("Explicit confirmation required");
    }

    @Test
    void shouldApplyWhenConfirmedOverwrite() {
        mockAccess();
        when(draftRepository.findByIdAndPartnerContractId("ext-1", 100L)).thenReturn(Optional.of(draft));
        when(contractRepository.findById(100L)).thenReturn(Optional.of(contract));
        when(draftRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        field.setReviewDecision(ContractExtractionReviewDecision.ACCEPT);
        clause.setReviewDecision(ContractExtractionReviewDecision.ACCEPT);

        ApplyExtractionRequest req = new ApplyExtractionRequest();
        req.setExpectedContractOptimisticVersion(1);
        req.setExpectedSourceDocumentHash("hash-1");
        req.setExpectedCurrentApprovedVersion(1);
        req.setExpectedNextApprovalVersion(2);
        req.setConfirmedOverwriteFieldKeys(List.of("contractTitle"));

        PartnerContractExtractionDraft res = extractionService.applyExtraction(100L, "ext-1", req, 1L);
        assertThat(res.getApplicationStatus()).isEqualTo(ContractExtractionApplicationStatus.APPLIED_FROZEN);
        assertThat(contract.getContractTitle()).isEqualTo("Old Title");
        verify(contractRepository, times(1)).save(contract);
    }

    @Test
    void shouldRejectStaleContractVersion() {
        mockAccess();
        when(draftRepository.findByIdAndPartnerContractId("ext-1", 100L)).thenReturn(Optional.of(draft));
        when(contractRepository.findById(100L)).thenReturn(Optional.of(contract));

        field.setReviewDecision(ContractExtractionReviewDecision.ACCEPT);
        clause.setReviewDecision(ContractExtractionReviewDecision.ACCEPT);

        ApplyExtractionRequest req = new ApplyExtractionRequest();
        req.setExpectedContractOptimisticVersion(0); // Stale

        assertThatThrownBy(() -> extractionService.applyExtraction(100L, "ext-1", req, 1L))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("Stale revision");
    }

    @Test
    void shouldRecoverApplyPendingToFrozen() {
        // APPLY_PENDING with valid SQL linkage should just finalize
        mockAccess();
        when(draftRepository.findByIdAndPartnerContractId("ext-1", 100L)).thenReturn(Optional.of(draft));
        when(contractRepository.findById(100L)).thenReturn(Optional.of(contract));
        when(draftRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        draft.setApplicationStatus(ContractExtractionApplicationStatus.APPLY_PENDING);
        draft.setApplyOperationId("op-1");
        draft.setApplyStartedAt(LocalDateTime.now().minusMinutes(10));

        contract.setPendingExtractionId("ext-1");

        ApplyExtractionRequest req = new ApplyExtractionRequest();
        PartnerContractExtractionDraft res = extractionService.applyExtraction(100L, "ext-1", req, 1L);

        assertThat(res.getApplicationStatus()).isEqualTo(ContractExtractionApplicationStatus.APPLIED_FROZEN);
        verify(contractRepository, never()).save(contract); // SQL already linked
    }

    @Test
    void shouldNotResetBeforeTimeout() {
        mockAccess();
        when(draftRepository.findByIdAndPartnerContractId("ext-1", 100L)).thenReturn(Optional.of(draft));
        when(contractRepository.findById(100L)).thenReturn(Optional.of(contract));

        draft.setApplicationStatus(ContractExtractionApplicationStatus.APPLY_PENDING);
        draft.setApplyOperationId("op-1");
        draft.setApplyStartedAt(LocalDateTime.now().minusSeconds(60)); // Before 300s timeout

        ApplyExtractionRequest req = new ApplyExtractionRequest();

        assertThatThrownBy(() -> extractionService.applyExtraction(100L, "ext-1", req, 1L))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("Extraction is locked during apply process");
    }

    @Test
    void shouldResetAfterTimeoutIfGuardsMatch() {
        mockAccess();
        when(draftRepository.findByIdAndPartnerContractId("ext-1", 100L)).thenReturn(Optional.of(draft));
        when(contractRepository.findById(100L)).thenReturn(Optional.of(contract));
        when(draftRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        draft.setApplicationStatus(ContractExtractionApplicationStatus.APPLY_PENDING);
        draft.setApplyOperationId("op-1");
        draft.setApplyStartedAt(LocalDateTime.now().minusSeconds(600)); // Past 300s timeout

        ApplyExtractionRequest req = new ApplyExtractionRequest();
        req.setExpectedContractOptimisticVersion(1);
        req.setExpectedSourceDocumentHash("hash-1");
        req.setExpectedCurrentApprovedVersion(1);
        req.setExpectedNextApprovalVersion(2);

        field.setReviewDecision(ContractExtractionReviewDecision.ACCEPT);
        clause.setReviewDecision(ContractExtractionReviewDecision.ACCEPT);
        req.setConfirmedOverwriteFieldKeys(List.of("contractTitle"));

        // It should reset and then apply
        PartnerContractExtractionDraft res = extractionService.applyExtraction(100L, "ext-1", req, 1L);
        assertThat(res.getApplicationStatus()).isEqualTo(ContractExtractionApplicationStatus.APPLIED_FROZEN);
    }

    @Test
    void shouldNotResetAfterTimeoutIfContractChanged() {
        mockAccess();
        when(draftRepository.findByIdAndPartnerContractId("ext-1", 100L)).thenReturn(Optional.of(draft));
        when(contractRepository.findById(100L)).thenReturn(Optional.of(contract));

        draft.setApplicationStatus(ContractExtractionApplicationStatus.APPLY_PENDING);
        draft.setApplyOperationId("op-1");
        draft.setApplyStartedAt(LocalDateTime.now().minusSeconds(600)); // Past 300s timeout

        contract.setVersion(5); // Contract was updated!

        ApplyExtractionRequest req = new ApplyExtractionRequest();

        assertThatThrownBy(() -> extractionService.applyExtraction(100L, "ext-1", req, 1L))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("Extraction is locked during apply process"); // Could not recover
    }

    @Test
    void shouldDenyUnrelatedAccount() {
        when(projectRepository.existsByIdAndMembersAccountId(999L, 1L)).thenReturn(false);
        // Using getExtraction to trigger validateProjectAccess
        when(draftRepository.findByIdAndPartnerContractId("ext-1", 100L)).thenReturn(Optional.of(draft));
        assertThatThrownBy(() -> extractionService.getExtraction(100L, "ext-1", 1L))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class)
                .hasMessageContaining("Must be a project member");
    }

    @Test
    void shouldRejectRawDocumentFromWrongProject() {
        mockAccess();
        com.apms.domain.document.RawDocument doc = new com.apms.domain.document.RawDocument();
        doc.setId("doc-1");
        doc.setProjectId("888"); // Different from contract 999

        contract.setRawDocumentId("doc-1");

        when(contractRepository.findById(100L)).thenReturn(Optional.of(contract));
        when(rawDocumentRepository.findById("doc-1")).thenReturn(Optional.of(doc));

        assertThatThrownBy(() -> extractionService.generateExtraction(100L, 1L))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("RawDocument project mismatch");
    }

    @Test
    void shouldRejectRawDocumentFromWrongTask() {
        mockAccess();
        com.apms.domain.document.RawDocument doc = new com.apms.domain.document.RawDocument();
        doc.setId("doc-1");
        doc.setProjectId("999");
        doc.setTaskId("task-2");

        contract.setRawDocumentId("doc-1");
        contract.setSourceTaskId(1L);

        when(contractRepository.findById(100L)).thenReturn(Optional.of(contract));
        when(rawDocumentRepository.findById("doc-1")).thenReturn(Optional.of(doc));

        assertThatThrownBy(() -> extractionService.generateExtraction(100L, 1L))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("RawDocument task mismatch");
    }

    @Test
    void shouldRejectExtractionFromWrongContract() {
        when(draftRepository.findByIdAndPartnerContractId("ext-1", 100L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> extractionService.getExtraction(100L, "ext-1", 1L))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("Extraction not found or does not belong to contract");
    }
}
