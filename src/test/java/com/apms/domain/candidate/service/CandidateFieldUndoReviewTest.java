package com.apms.domain.candidate.service;

import com.apms.common.enums.AuditAction;
import com.apms.common.enums.CandidateStatus;
import com.apms.common.enums.FieldApprovalStatus;
import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.ai.dto.ExtractionFieldResult;
import com.apms.domain.ai.dto.ExtractionReviewStatus;
import com.apms.domain.ai.dto.StaffFieldReviewStatus;
import com.apms.domain.candidate.CompanyCandidate;
import com.apms.domain.candidate.dto.CandidateResponse;
import com.apms.domain.candidate.dto.CandidateReviewRequest;
import com.apms.domain.candidate.repository.mongo.CompanyCandidateRepository;
import com.apms.domain.project.fieldapproval.FieldApprovalRecord;
import com.apms.domain.project.fieldapproval.FieldApprovalService;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CandidateFieldUndoReviewTest {

    @Mock private CompanyCandidateRepository candidateRepository;
    @Mock private com.apms.domain.document.repository.sql.ImportJobRepository importJobRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private com.apms.domain.ai.service.AiExtractionService aiExtractionService;
    @Mock private org.springframework.context.ApplicationEventPublisher eventPublisher;
    @Mock private com.apms.domain.profile.service.OwnerOrganizationService ownerOrganizationService;
    @Mock private com.apms.domain.project.fieldapproval.FieldApprovalGuard fieldApprovalGuard;
    @Mock private FieldApprovalService fieldApprovalService;
    @Mock private com.apms.domain.project.repository.sql.ProjectTaskRepository projectTaskRepository;
    @Mock private com.apms.domain.document.repository.mongo.RawDocumentRepository rawDocumentRepository;
    @Mock private com.apms.domain.candidate.repository.mongo.CandidateDraftSequenceRepository draftSequenceRepository;
    @Mock private com.apms.domain.audit.service.AuditLogService auditLogService;
    @Mock private com.apms.domain.financial.service.DocumentCompanyMatcher companyMatcher;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private CandidateService candidateService;

    private CompanyCandidate buildTestCandidate(String id, CandidateStatus status, boolean isManual) {
        Map<String, ExtractionFieldResult> fieldResults = new HashMap<>();
        for (String field : CandidateService.STAFF_REVIEWABLE_FIELDS) {
            fieldResults.put(com.apms.domain.ai.service.FieldKeyCodec.encode(field),
                    ExtractionFieldResult.builder()
                            .fieldName(field)
                            .staffReviewStatus(StaffFieldReviewStatus.PENDING)
                            .managerReviewStatus(ExtractionReviewStatus.PENDING)
                            .build());
        }

        CompanyCandidate.ExtractionSource source = null;
        if (isManual) {
            source = CompanyCandidate.ExtractionSource.builder()
                    .extractionMethod("MANUAL")
                    .build();
        }

        return CompanyCandidate.builder()
                .id(id)
                .projectId("10")
                .status(status)
                .revisionNumber(1)
                .extractionSource(source)
                .fieldResults(fieldResults)
                .fieldApprovals(new ArrayList<>())
                .identity(CompanyCandidate.Identity.builder()
                        .legalName("Acme Corp")
                        .tradeName("Acme Corp")
                        .build())
                .contact(CompanyCandidate.Contact.builder()
                        .website("https://acme.com")
                        .addresses(new ArrayList<>(List.of(
                                CompanyCandidate.Address.builder()
                                        .fullAddress("123 Street")
                                        .build()
                        )))
                        .build())
                .build();
    }

    private CandidateReviewRequest buildManagerDecisionRequest(String fieldPath, ExtractionReviewStatus status, String comment) {
        CandidateReviewRequest request = new CandidateReviewRequest();
        CandidateReviewRequest.FieldReviewUpdate update = new CandidateReviewRequest.FieldReviewUpdate();
        update.setManagerReviewStatus(status);
        update.setManagerReviewComment(comment);
        update.setManager(true);
        request.setFields(Map.of(fieldPath, update));
        return request;
    }

    @Test
    @DisplayName("Test 1: Field PENDING -> Manager approves -> APPROVED -> Undo -> PENDING")
    void testApproveThenUndo() {
        CompanyCandidate candidate = buildTestCandidate("cand-1", CandidateStatus.PENDING_REVIEW, true);
        when(candidateRepository.findById("cand-1")).thenReturn(Optional.of(candidate));
        when(candidateRepository.save(any(CompanyCandidate.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // 1. Approve Website
        CandidateReviewRequest approveReq = buildManagerDecisionRequest("contact.website", ExtractionReviewStatus.ACCEPTED, null);
        CandidateResponse resp1 = candidateService.reviewCandidate("10", "cand-1", approveReq, 99L);

        ExtractionFieldResult websiteResult1 = resp1.getFieldResults().get("contact.website");
        assertThat(websiteResult1.getManagerReviewStatus()).isEqualTo(ExtractionReviewStatus.ACCEPTED);
        assertThat(candidate.getFieldApprovals()).hasSize(1);
        assertThat(candidate.getFieldApprovals().get(0).getStatus()).isEqualTo(FieldApprovalStatus.APPROVED);

        // 2. Undo Approval
        CandidateReviewRequest undoReq = buildManagerDecisionRequest("contact.website", ExtractionReviewStatus.PENDING, null);
        CandidateResponse resp2 = candidateService.reviewCandidate("10", "cand-1", undoReq, 99L);

        ExtractionFieldResult websiteResult2 = resp2.getFieldResults().get("contact.website");
        assertThat(websiteResult2.getManagerReviewStatus()).isEqualTo(ExtractionReviewStatus.PENDING);
        assertThat(websiteResult2.getManagerReviewedByUserId()).isNull();
        assertThat(websiteResult2.getManagerReviewedAt()).isNull();

        FieldApprovalRecord websiteRecord = candidate.getFieldApprovals().get(0);
        assertThat(websiteRecord.getStatus()).isEqualTo(FieldApprovalStatus.PENDING_REVIEW);
        assertThat(websiteRecord.getReviewedByAccountId()).isNull();
        assertThat(websiteRecord.getReviewedAt()).isNull();
        assertThat(websiteRecord.getApprovedValueHash()).isNull();

        verify(auditLogService).log(eq(99L), eq(AuditAction.FIELD_UNDO_APPROVAL), eq("CompanyCandidate"), eq("cand-1"), contains("approval"));
    }

    @Test
    @DisplayName("Test 2: Field REJECTED -> Undo -> PENDING")
    void testRejectThenUndo() {
        CompanyCandidate candidate = buildTestCandidate("cand-2", CandidateStatus.PENDING_REVIEW, true);
        when(candidateRepository.findById("cand-2")).thenReturn(Optional.of(candidate));
        when(candidateRepository.save(any(CompanyCandidate.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // 1. Reject Address
        CandidateReviewRequest rejectReq = buildManagerDecisionRequest("contact.addresses", ExtractionReviewStatus.REJECTED, "Invalid street format");
        CandidateResponse resp1 = candidateService.reviewCandidate("10", "cand-2", rejectReq, 99L);

        ExtractionFieldResult addressResult1 = resp1.getFieldResults().get("contact.addresses");
        assertThat(addressResult1.getManagerReviewStatus()).isEqualTo(ExtractionReviewStatus.REJECTED);
        assertThat(addressResult1.getManagerReviewComment()).isEqualTo("Invalid street format");
        assertThat(candidate.getFieldApprovals().get(0).getStatus()).isEqualTo(FieldApprovalStatus.REJECTED);

        // 2. Undo Rejection
        CandidateReviewRequest undoReq = buildManagerDecisionRequest("contact.addresses", ExtractionReviewStatus.PENDING, null);
        CandidateResponse resp2 = candidateService.reviewCandidate("10", "cand-2", undoReq, 99L);

        ExtractionFieldResult addressResult2 = resp2.getFieldResults().get("contact.addresses");
        assertThat(addressResult2.getManagerReviewStatus()).isEqualTo(ExtractionReviewStatus.PENDING);
        assertThat(addressResult2.getManagerReviewComment()).isNull();
        assertThat(candidate.getFieldApprovals().get(0).getStatus()).isEqualTo(FieldApprovalStatus.PENDING_REVIEW);

        verify(auditLogService).log(eq(99L), eq(AuditAction.FIELD_UNDO_REJECTION), eq("CompanyCandidate"), eq("cand-2"), contains("rejection"));
    }

    @Test
    @DisplayName("Test 3: Active-review guard rejects Undo when Candidate is not PENDING_REVIEW or CORRECTED")
    void testActiveReviewGuardRejectsNonReviewStates() {
        CompanyCandidate draftCandidate = buildTestCandidate("cand-draft", CandidateStatus.DRAFT, true);
        when(candidateRepository.findById("cand-draft")).thenReturn(Optional.of(draftCandidate));

        CandidateReviewRequest undoReq = buildManagerDecisionRequest("contact.website", ExtractionReviewStatus.PENDING, null);

        assertThatThrownBy(() -> candidateService.reviewCandidate("10", "cand-draft", undoReq, 99L))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("Field decisions can only be modified during an active candidate review.");

        CompanyCandidate approvedCandidate = buildTestCandidate("cand-approved", CandidateStatus.APPROVED, true);
        when(candidateRepository.findById("cand-approved")).thenReturn(Optional.of(approvedCandidate));

        assertThatThrownBy(() -> candidateService.reviewCandidate("10", "cand-approved", undoReq, 99L))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("Field decisions can only be modified during an active candidate review.");

        CompanyCandidate revisionCandidate = buildTestCandidate("cand-rev", CandidateStatus.REVISION_REQUIRED, true);
        when(candidateRepository.findById("cand-rev")).thenReturn(Optional.of(revisionCandidate));

        assertThatThrownBy(() -> candidateService.reviewCandidate("10", "cand-rev", undoReq, 99L))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("Field decisions can only be modified during an active candidate review.");
    }

    @Test
    @DisplayName("Test 4: Unsupported field-state test (already PENDING, NEEDS_REVIEW, STALE) rejects Undo without audit log")
    void testUndoRejectedOnNonDecidedField() {
        CompanyCandidate candidate = buildTestCandidate("cand-4", CandidateStatus.PENDING_REVIEW, true);
        when(candidateRepository.findById("cand-4")).thenReturn(Optional.of(candidate));

        // Already PENDING
        CandidateReviewRequest undoReq = buildManagerDecisionRequest("contact.website", ExtractionReviewStatus.PENDING, null);
        assertThatThrownBy(() -> candidateService.reviewCandidate("10", "cand-4", undoReq, 99L))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("Only an approved or rejected field can be returned to Pending Review.");

        // STALE record
        candidate.getFieldApprovals().add(FieldApprovalRecord.builder()
                .fieldPath("contact.website")
                .status(FieldApprovalStatus.STALE)
                .build());

        assertThatThrownBy(() -> candidateService.reviewCandidate("10", "cand-4", undoReq, 99L))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("Only an approved or rejected field can be returned to Pending Review.");

        // Verify no audit log was created
        verify(auditLogService, never()).log(anyLong(), eq(AuditAction.FIELD_UNDO_APPROVAL), anyString(), anyString(), anyString());
        verify(auditLogService, never()).log(anyLong(), eq(AuditAction.FIELD_UNDO_REJECTION), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Test 5: Batch approve -> Undo single field -> only that field is PENDING, others remain APPROVED")
    void testBatchApproveThenUndoSingleField() {
        CompanyCandidate candidate = buildTestCandidate("cand-5", CandidateStatus.PENDING_REVIEW, true);
        when(candidateRepository.findById("cand-5")).thenReturn(Optional.of(candidate));
        when(candidateRepository.save(any(CompanyCandidate.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Approve both Website and Trade Name
        CandidateReviewRequest batchReq = new CandidateReviewRequest();
        CandidateReviewRequest.FieldReviewUpdate u1 = new CandidateReviewRequest.FieldReviewUpdate();
        u1.setManagerReviewStatus(ExtractionReviewStatus.ACCEPTED);
        u1.setManager(true);
        CandidateReviewRequest.FieldReviewUpdate u2 = new CandidateReviewRequest.FieldReviewUpdate();
        u2.setManagerReviewStatus(ExtractionReviewStatus.ACCEPTED);
        u2.setManager(true);
        batchReq.setFields(Map.of("contact.website", u1, "identity.tradeName", u2));

        candidateService.reviewCandidate("10", "cand-5", batchReq, 99L);

        // Undo ONLY Website
        CandidateReviewRequest undoReq = buildManagerDecisionRequest("contact.website", ExtractionReviewStatus.PENDING, null);
        CandidateResponse resp = candidateService.reviewCandidate("10", "cand-5", undoReq, 99L);

        ExtractionFieldResult websiteResult = resp.getFieldResults().get("contact.website");
        ExtractionFieldResult tradeNameResult = resp.getFieldResults().get("identity.tradeName");

        assertThat(websiteResult.getManagerReviewStatus()).isEqualTo(ExtractionReviewStatus.PENDING);
        assertThat(tradeNameResult.getManagerReviewStatus()).isEqualTo(ExtractionReviewStatus.ACCEPTED);
    }

    @Test
    @DisplayName("Test 6: AI Candidate field approved -> Undo -> field returns to PENDING, AI Original & Evidence intact")
    void testAiCandidateFieldUndoPreservesAiData() {
        CompanyCandidate candidate = buildTestCandidate("cand-ai", CandidateStatus.PENDING_REVIEW, false);
        // Add AI extraction metadata
        ExtractionFieldResult websiteResult = candidate.getFieldResults().get(com.apms.domain.ai.service.FieldKeyCodec.encode("contact.website"));
        websiteResult.setValue("https://extracted-acme.com");
        websiteResult.setConfidence(0.95);
        websiteResult.setEvidenceText("Found on page 1 footer");
        websiteResult.setPageNumber(1);

        when(candidateRepository.findById("cand-ai")).thenReturn(Optional.of(candidate));
        when(candidateRepository.save(any(CompanyCandidate.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Approve
        candidateService.reviewCandidate("10", "cand-ai", buildManagerDecisionRequest("contact.website", ExtractionReviewStatus.ACCEPTED, null), 99L);

        // Undo
        CandidateResponse resp = candidateService.reviewCandidate("10", "cand-ai", buildManagerDecisionRequest("contact.website", ExtractionReviewStatus.PENDING, null), 99L);

        ExtractionFieldResult undoneResult = resp.getFieldResults().get("contact.website");
        assertThat(undoneResult.getManagerReviewStatus()).isEqualTo(ExtractionReviewStatus.PENDING);
        // AI extraction data intact
        assertThat(undoneResult.getValue()).isEqualTo("https://extracted-acme.com");
        assertThat(undoneResult.getConfidence()).isEqualTo(0.95);
        assertThat(undoneResult.getEvidenceText()).isEqualTo("Found on page 1 footer");
        assertThat(undoneResult.getPageNumber()).isEqualTo(1);
    }

    @Test
    @DisplayName("Test 7: Manual Candidate empty fields: undoing a submitted field leaves empty fields unprovided without approval records")
    void testManualCandidateEmptyFieldsUnaffectedByUndo() {
        CompanyCandidate candidate = buildTestCandidate("cand-manual", CandidateStatus.PENDING_REVIEW, true);
        when(candidateRepository.findById("cand-manual")).thenReturn(Optional.of(candidate));
        when(candidateRepository.save(any(CompanyCandidate.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Approve Website
        candidateService.reviewCandidate("10", "cand-manual", buildManagerDecisionRequest("contact.website", ExtractionReviewStatus.ACCEPTED, null), 99L);
        // Undo Website
        candidateService.reviewCandidate("10", "cand-manual", buildManagerDecisionRequest("contact.website", ExtractionReviewStatus.PENDING, null), 99L);

        // Verify unprovided fields (e.g. contact.emails, business.industries) still have no approval records
        assertThat(candidate.getFieldApprovals()).hasSize(1);
        assertThat(candidate.getFieldApprovals().get(0).getFieldPath()).isEqualTo("contact.website");
        assertThat(candidate.getFieldApprovals().get(0).getStatus()).isEqualTo(FieldApprovalStatus.PENDING_REVIEW);
    }

    @Test
    @DisplayName("Test 8: Multi-round isolation test: Round 1 rejection preserved when Round 2 approval is undone")
    void testMultiRoundIsolationPreservesRound1History() {
        CompanyCandidate candidate = buildTestCandidate("cand-multiround", CandidateStatus.PENDING_REVIEW, true);
        candidate.setRevisionNumber(2);

        // Simulate Round 1 historical rejection preserved on record & fieldResult
        FieldApprovalRecord record = FieldApprovalRecord.builder()
                .fieldPath("contact.website")
                .status(FieldApprovalStatus.PENDING_REVIEW)
                .previousStatus(FieldApprovalStatus.REJECTED)
                .previousComment("Round 1: Broken link")
                .changedInRevision(2)
                .build();
        candidate.getFieldApprovals().add(record);

        ExtractionFieldResult fieldResult = candidate.getFieldResults().get(com.apms.domain.ai.service.FieldKeyCodec.encode("contact.website"));
        fieldResult.setPreviousManagerReviewStatus(ExtractionReviewStatus.REJECTED);
        fieldResult.setPreviousManagerReviewComment("Round 1: Broken link");
        fieldResult.setChangedInRevision(2);

        when(candidateRepository.findById("cand-multiround")).thenReturn(Optional.of(candidate));
        when(candidateRepository.save(any(CompanyCandidate.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Manager approves in Round 2
        candidateService.reviewCandidate("10", "cand-multiround", buildManagerDecisionRequest("contact.website", ExtractionReviewStatus.ACCEPTED, "Looks good in round 2"), 99L);
        assertThat(record.getStatus()).isEqualTo(FieldApprovalStatus.APPROVED);

        // Manager undos approval in Round 2
        CandidateResponse resp = candidateService.reviewCandidate("10", "cand-multiround", buildManagerDecisionRequest("contact.website", ExtractionReviewStatus.PENDING, null), 99L);

        // Current status is PENDING_REVIEW / PENDING
        assertThat(record.getStatus()).isEqualTo(FieldApprovalStatus.PENDING_REVIEW);
        ExtractionFieldResult updatedField = resp.getFieldResults().get("contact.website");
        assertThat(updatedField.getManagerReviewStatus()).isEqualTo(ExtractionReviewStatus.PENDING);

        // Round 1 history MUST remain intact!
        assertThat(record.getPreviousStatus()).isEqualTo(FieldApprovalStatus.REJECTED);
        assertThat(record.getPreviousComment()).isEqualTo("Round 1: Broken link");
        assertThat(updatedField.getPreviousManagerReviewStatus()).isEqualTo(ExtractionReviewStatus.REJECTED);
        assertThat(updatedField.getPreviousManagerReviewComment()).isEqualTo("Round 1: Broken link");

        // revisionNumber remains unchanged (2)
        assertThat(candidate.getRevisionNumber()).isEqualTo(2);
    }

    @Test
    @DisplayName("Test 9: Audit log sequence: Approve -> Undo -> Approve records in order")
    void testAuditLogOrder() {
        CompanyCandidate candidate = buildTestCandidate("cand-audit", CandidateStatus.PENDING_REVIEW, true);
        when(candidateRepository.findById("cand-audit")).thenReturn(Optional.of(candidate));
        when(candidateRepository.save(any(CompanyCandidate.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // 1. Approve
        candidateService.reviewCandidate("10", "cand-audit", buildManagerDecisionRequest("contact.website", ExtractionReviewStatus.ACCEPTED, null), 99L);
        // 2. Undo
        candidateService.reviewCandidate("10", "cand-audit", buildManagerDecisionRequest("contact.website", ExtractionReviewStatus.PENDING, null), 99L);
        // 3. Approve again
        candidateService.reviewCandidate("10", "cand-audit", buildManagerDecisionRequest("contact.website", ExtractionReviewStatus.ACCEPTED, null), 99L);

        ArgumentCaptor<AuditAction> actionCaptor = ArgumentCaptor.forClass(AuditAction.class);
        verify(auditLogService, times(3)).log(eq(99L), actionCaptor.capture(), eq("CompanyCandidate"), eq("cand-audit"), anyString());

        List<AuditAction> recordedActions = actionCaptor.getAllValues();
        assertThat(recordedActions).containsExactly(
                AuditAction.FIELD_APPROVED,
                AuditAction.FIELD_UNDO_APPROVAL,
                AuditAction.FIELD_APPROVED
        );
    }

    @Test
    @DisplayName("Test 10: Round 1 Field A approved, Field B rejected -> Staff resubmits B with revised value -> Round 2: A is APPROVED (rev 1), B is PENDING with Round 1 history")
    void test10_round1DecisionsAndStaffResubmission() {
        CompanyCandidate candidate = buildTestCandidate("cand-10", CandidateStatus.PENDING_REVIEW, true);
        when(candidateRepository.findById("cand-10")).thenReturn(Optional.of(candidate));
        when(candidateRepository.save(any(CompanyCandidate.class))).thenAnswer(inv -> inv.getArgument(0));

        // Round 1: Approve Trade Name, Reject Website
        candidateService.reviewCandidate("10", "cand-10", buildManagerDecisionRequest("identity.tradeName", ExtractionReviewStatus.ACCEPTED, null), 99L);
        candidateService.reviewCandidate("10", "cand-10", buildManagerDecisionRequest("contact.website", ExtractionReviewStatus.REJECTED, "sửa lại"), 99L);

        // Manager sends back candidate
        candidateService.sendBackCandidate("cand-10", 99L);
        assertThat(candidate.getStatus()).isEqualTo(CandidateStatus.REVISION_REQUIRED);
        assertThat(candidate.getRevisionNumber()).isEqualTo(2);

        // Staff revises website and resubmits
        candidate.getContact().setWebsite("https://revised-acme.com");
        candidate.setChangedFieldPaths(new ArrayList<>(List.of("contact.website")));

        CandidateResponse response = candidateService.submitCandidate("cand-10", 100L);

        assertThat(candidate.getStatus()).isEqualTo(CandidateStatus.PENDING_REVIEW);
        assertThat(candidate.getRevisionNumber()).isEqualTo(2);

        // Field A (Trade Name): remains APPROVED from Round 1
        ExtractionFieldResult tradeNameRes = response.getFieldResults().get("identity.tradeName");
        assertThat(tradeNameRes.getManagerReviewStatus()).isEqualTo(ExtractionReviewStatus.ACCEPTED);
        assertThat(tradeNameRes.getReviewedRevision()).isEqualTo(1);

        // Field B (Website): reset to PENDING for Round 2, Round 1 history preserved
        ExtractionFieldResult websiteRes = response.getFieldResults().get("contact.website");
        assertThat(websiteRes.getManagerReviewStatus()).isEqualTo(ExtractionReviewStatus.PENDING);
        assertThat(websiteRes.getManagerReviewComment()).isNull();
        assertThat(websiteRes.getReviewedRevision()).isNull();
        assertThat(websiteRes.getPreviousManagerReviewStatus()).isEqualTo(ExtractionReviewStatus.REJECTED);
        assertThat(websiteRes.getPreviousManagerReviewComment()).isEqualTo("sửa lại");
        assertThat(websiteRes.getPreviousReviewedRevision()).isEqualTo(1);
    }

    @Test
    @DisplayName("Test 11: Staff resubmits rejected Field B with IDENTICAL value -> Round 2: B is still PENDING")
    void test11_staffResubmitsIdenticalValueBecomesPending() {
        CompanyCandidate candidate = buildTestCandidate("cand-11", CandidateStatus.PENDING_REVIEW, true);
        when(candidateRepository.findById("cand-11")).thenReturn(Optional.of(candidate));
        when(candidateRepository.save(any(CompanyCandidate.class))).thenAnswer(inv -> inv.getArgument(0));

        // Round 1: Reject Website
        candidateService.reviewCandidate("10", "cand-11", buildManagerDecisionRequest("contact.website", ExtractionReviewStatus.REJECTED, "sửa lại"), 99L);

        // Send back
        candidateService.sendBackCandidate("cand-11", 99L);
        assertThat(candidate.getStatus()).isEqualTo(CandidateStatus.REVISION_REQUIRED);
        assertThat(candidate.getRevisionNumber()).isEqualTo(2);

        // Staff resubmits WITHOUT changing the value (changedFieldPaths is empty!)
        candidate.setChangedFieldPaths(new ArrayList<>());

        CandidateResponse response = candidateService.submitCandidate("cand-11", 100L);

        // Even though value was unchanged, rejected field MUST become PENDING for Round 2
        ExtractionFieldResult websiteRes = response.getFieldResults().get("contact.website");
        assertThat(websiteRes.getManagerReviewStatus()).isEqualTo(ExtractionReviewStatus.PENDING);
        assertThat(websiteRes.getManagerReviewComment()).isNull();
        assertThat(websiteRes.getReviewedRevision()).isNull();
        assertThat(websiteRes.getPreviousManagerReviewStatus()).isEqualTo(ExtractionReviewStatus.REJECTED);
        assertThat(websiteRes.getPreviousManagerReviewComment()).isEqualTo("sửa lại");
        assertThat(websiteRes.getPreviousReviewedRevision()).isEqualTo(1);
    }

    @Test
    @DisplayName("Test 12: Round 2 Manager approves B -> B is APPROVED with reviewedRevision=2")
    void test12_round2ManagerApprovesField() {
        CompanyCandidate candidate = buildTestCandidate("cand-12", CandidateStatus.PENDING_REVIEW, true);
        candidate.setRevisionNumber(2);

        // Simulate resubmitted field B
        FieldApprovalRecord bRecord = FieldApprovalRecord.builder()
                .fieldPath("contact.website")
                .status(FieldApprovalStatus.PENDING_REVIEW)
                .previousStatus(FieldApprovalStatus.REJECTED)
                .previousComment("sửa lại")
                .previousReviewedRevision(1)
                .changedInRevision(2)
                .build();
        candidate.getFieldApprovals().add(bRecord);

        when(candidateRepository.findById("cand-12")).thenReturn(Optional.of(candidate));
        when(candidateRepository.save(any(CompanyCandidate.class))).thenAnswer(inv -> inv.getArgument(0));

        // Manager approves Website in Round 2
        CandidateResponse response = candidateService.reviewCandidate("10", "cand-12",
                buildManagerDecisionRequest("contact.website", ExtractionReviewStatus.ACCEPTED, null), 99L);

        ExtractionFieldResult websiteRes = response.getFieldResults().get("contact.website");
        assertThat(websiteRes.getManagerReviewStatus()).isEqualTo(ExtractionReviewStatus.ACCEPTED);
        assertThat(websiteRes.getReviewedRevision()).isEqualTo(2);
        assertThat(bRecord.getStatus()).isEqualTo(FieldApprovalStatus.APPROVED);
        assertThat(bRecord.getReviewedRevision()).isEqualTo(2);
    }

    @Test
    @DisplayName("Test 13: Round 2 Manager rejects B again -> B is REJECTED with reviewedRevision=2, Round 1 comment in history")
    void test13_round2ManagerRejectsFieldAgain() {
        CompanyCandidate candidate = buildTestCandidate("cand-13", CandidateStatus.PENDING_REVIEW, true);
        candidate.setRevisionNumber(2);

        FieldApprovalRecord bRecord = FieldApprovalRecord.builder()
                .fieldPath("contact.website")
                .status(FieldApprovalStatus.PENDING_REVIEW)
                .previousStatus(FieldApprovalStatus.REJECTED)
                .previousComment("Round 1: sửa lại")
                .previousReviewedRevision(1)
                .changedInRevision(2)
                .build();
        candidate.getFieldApprovals().add(bRecord);

        when(candidateRepository.findById("cand-13")).thenReturn(Optional.of(candidate));
        when(candidateRepository.save(any(CompanyCandidate.class))).thenAnswer(inv -> inv.getArgument(0));

        // Manager rejects Website in Round 2
        CandidateResponse response = candidateService.reviewCandidate("10", "cand-13",
                buildManagerDecisionRequest("contact.website", ExtractionReviewStatus.REJECTED, "Round 2: vẫn sai"), 99L);

        ExtractionFieldResult websiteRes = response.getFieldResults().get("contact.website");
        assertThat(websiteRes.getManagerReviewStatus()).isEqualTo(ExtractionReviewStatus.REJECTED);
        assertThat(websiteRes.getManagerReviewComment()).isEqualTo("Round 2: vẫn sai");
        assertThat(websiteRes.getReviewedRevision()).isEqualTo(2);

        // Previous history intact
        assertThat(websiteRes.getPreviousManagerReviewStatus()).isEqualTo(ExtractionReviewStatus.REJECTED);
        assertThat(websiteRes.getPreviousManagerReviewComment()).isEqualTo("Round 1: sửa lại");
        assertThat(websiteRes.getPreviousReviewedRevision()).isEqualTo(1);
    }

    @Test
    @DisplayName("Test 14: Round 2 Manager undos Round 2 rejection -> B returns to PENDING in Round 2, Round 1 history untouched")
    void test14_round2UndoRejectionReturnsToPendingPreservingHistory() {
        CompanyCandidate candidate = buildTestCandidate("cand-14", CandidateStatus.PENDING_REVIEW, true);
        candidate.setRevisionNumber(2);

        FieldApprovalRecord bRecord = FieldApprovalRecord.builder()
                .fieldPath("contact.website")
                .status(FieldApprovalStatus.PENDING_REVIEW)
                .previousStatus(FieldApprovalStatus.REJECTED)
                .previousComment("Round 1: sửa lại")
                .previousReviewedRevision(1)
                .changedInRevision(2)
                .build();
        candidate.getFieldApprovals().add(bRecord);

        when(candidateRepository.findById("cand-14")).thenReturn(Optional.of(candidate));
        when(candidateRepository.save(any(CompanyCandidate.class))).thenAnswer(inv -> inv.getArgument(0));

        // 1. Reject in Round 2
        candidateService.reviewCandidate("10", "cand-14",
                buildManagerDecisionRequest("contact.website", ExtractionReviewStatus.REJECTED, "Round 2: nhầm"), 99L);
        assertThat(bRecord.getStatus()).isEqualTo(FieldApprovalStatus.REJECTED);
        assertThat(bRecord.getReviewedRevision()).isEqualTo(2);

        // 2. Undo rejection in Round 2
        CandidateResponse response = candidateService.reviewCandidate("10", "cand-14",
                buildManagerDecisionRequest("contact.website", ExtractionReviewStatus.PENDING, null), 99L);

        ExtractionFieldResult websiteRes = response.getFieldResults().get("contact.website");
        assertThat(websiteRes.getManagerReviewStatus()).isEqualTo(ExtractionReviewStatus.PENDING);
        assertThat(websiteRes.getReviewedRevision()).isNull();
        assertThat(bRecord.getStatus()).isEqualTo(FieldApprovalStatus.PENDING_REVIEW);
        assertThat(bRecord.getReviewedRevision()).isNull();

        // Round 1 history untouched
        assertThat(bRecord.getPreviousStatus()).isEqualTo(FieldApprovalStatus.REJECTED);
        assertThat(bRecord.getPreviousComment()).isEqualTo("Round 1: sửa lại");
        assertThat(websiteRes.getPreviousManagerReviewStatus()).isEqualTo(ExtractionReviewStatus.REJECTED);
        assertThat(websiteRes.getPreviousManagerReviewComment()).isEqualTo("Round 1: sửa lại");
        assertThat(websiteRes.getPreviousReviewedRevision()).isEqualTo(1);
    }

    @Test
    @DisplayName("Test 15: Round 2 attempting to undo Round 1 approval on Field A throws CANNOT_UNDO_PRIOR_ROUND_DECISION")
    void test15_undoRound1DecisionInRound2ThrowsException() {
        CompanyCandidate candidate = buildTestCandidate("cand-15", CandidateStatus.PENDING_REVIEW, true);
        candidate.setRevisionNumber(2);

        // Field A approved in Round 1
        FieldApprovalRecord aRecord = FieldApprovalRecord.builder()
                .fieldPath("identity.tradeName")
                .status(FieldApprovalStatus.APPROVED)
                .reviewedRevision(1)
                .build();
        candidate.getFieldApprovals().add(aRecord);

        ExtractionFieldResult tradeResult = candidate.getFieldResults().get(com.apms.domain.ai.service.FieldKeyCodec.encode("identity.tradeName"));
        tradeResult.setManagerReviewStatus(ExtractionReviewStatus.ACCEPTED);
        tradeResult.setReviewedRevision(1);

        when(candidateRepository.findById("cand-15")).thenReturn(Optional.of(candidate));

        // Attempting to undo Round 1 approval while in Round 2
        CandidateReviewRequest undoReq = buildManagerDecisionRequest("identity.tradeName", ExtractionReviewStatus.PENDING, null);

        assertThatThrownBy(() -> candidateService.reviewCandidate("10", "cand-15", undoReq, 99L))
                .isInstanceOf(BusinessValidationException.class)
                .satisfies(ex -> {
                    BusinessValidationException bve = (BusinessValidationException) ex;
                    assertThat(bve.getErrorCode()).isEqualTo("CANNOT_UNDO_PRIOR_ROUND_DECISION");
                    assertThat(bve.getMessage()).contains("Cannot undo decision from a previous review round");
                });

        // Status remains APPROVED
        assertThat(aRecord.getStatus()).isEqualTo(FieldApprovalStatus.APPROVED);
        assertThat(aRecord.getReviewedRevision()).isEqualTo(1);
    }

    @Test
    @DisplayName("Test 16: Staff edits previously APPROVED field during revision -> resubmits -> field becomes PENDING in Round 2")
    void test16_staffEditsApprovedFieldDuringRevisionBecomesPendingOnResubmit() {
        CompanyCandidate candidate = buildTestCandidate("cand-16", CandidateStatus.PENDING_REVIEW, true);
        when(candidateRepository.findById("cand-16")).thenReturn(Optional.of(candidate));
        when(candidateRepository.save(any(CompanyCandidate.class))).thenAnswer(inv -> inv.getArgument(0));

        // Round 1: Approve Trade Name
        candidateService.reviewCandidate("10", "cand-16", buildManagerDecisionRequest("identity.tradeName", ExtractionReviewStatus.ACCEPTED, null), 99L);
        FieldApprovalRecord aRecord = candidate.getFieldApprovals().stream()
                .filter(f -> "identity.tradeName".equals(f.getFieldPath()))
                .findFirst().orElseThrow();
        assertThat(aRecord.getStatus()).isEqualTo(FieldApprovalStatus.APPROVED);
        assertThat(aRecord.getReviewedRevision()).isEqualTo(1);

        // Send back to Staff
        candidateService.sendBackCandidate("cand-16", 99L);
        assertThat(candidate.getStatus()).isEqualTo(CandidateStatus.REVISION_REQUIRED);
        assertThat(candidate.getRevisionNumber()).isEqualTo(2);

        // Staff edits the previously-approved Trade Name
        candidate.getIdentity().setTradeName("Acme Global Corp");
        candidate.setChangedFieldPaths(new ArrayList<>(List.of("identity.tradeName")));

        // Resubmit
        CandidateResponse response = candidateService.submitCandidate("cand-16", 100L);

        // Trade Name must now be PENDING in Round 2
        ExtractionFieldResult tradeRes = response.getFieldResults().get("identity.tradeName");
        assertThat(tradeRes.getManagerReviewStatus()).isEqualTo(ExtractionReviewStatus.PENDING);
        assertThat(tradeRes.getReviewedRevision()).isNull();
        assertThat(aRecord.getStatus()).isEqualTo(FieldApprovalStatus.PENDING_REVIEW);
        assertThat(aRecord.getReviewedRevision()).isNull();

        // Round 1 approval preserved in previousStatus
        assertThat(aRecord.getPreviousStatus()).isEqualTo(FieldApprovalStatus.APPROVED);
        assertThat(aRecord.getPreviousReviewedRevision()).isEqualTo(1);
        assertThat(tradeRes.getPreviousManagerReviewStatus()).isEqualTo(ExtractionReviewStatus.ACCEPTED);
        assertThat(tradeRes.getPreviousReviewedRevision()).isEqualTo(1);
    }
}
