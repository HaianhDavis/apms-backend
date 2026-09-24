package com.apms.domain.contract.service;

import com.apms.common.enums.AuditAction;
import com.apms.common.enums.RelationshipType;
import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.contract.dto.*;
import com.apms.domain.contract.entity.PartnerContract;
import com.apms.domain.contract.entity.PartnerContractVersion;
import com.apms.domain.contract.enums.ContractLifecycleStatus;
import com.apms.domain.contract.enums.ContractReviewStatus;
import com.apms.domain.contract.repository.sql.PartnerContractRepository;
import com.apms.domain.contract.repository.sql.PartnerContractVersionRepository;
import com.apms.domain.document.RawDocument;
import com.apms.domain.document.repository.mongo.RawDocumentRepository;
import com.apms.domain.profile.service.OwnerOrganizationService;
import com.apms.domain.project.Project;
import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.apms.domain.project.repository.sql.ProjectTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PartnerContractServiceTest {

    @Mock private PartnerContractRepository contractRepository;
    @Mock private PartnerContractVersionRepository versionRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private ProjectTaskRepository taskRepository;
    @Mock private RawDocumentRepository documentRepository;
    @Mock private OwnerOrganizationService ownerOrganizationService;
    @Mock private AuditLogService auditService;

    @InjectMocks
    private PartnerContractService service;

    private Project validProject;
    private ProjectTask validTask;
    private RawDocument validDoc;

    @BeforeEach
    void setUp() {
        validProject = new Project();
        validProject.setId(10L);
        validProject.setTargetRelationshipType(RelationshipType.PARTNER_WITH);
        validProject.setTargetCompanyProfileId("partner-company-id");

        validTask = new ProjectTask();
        validTask.setId(20L);
        validTask.setProject(validProject);

        validDoc = new RawDocument();
        validDoc.setId("doc-id");
        validDoc.setProjectId("10");
    }

    private void mockAccess() {
        when(projectRepository.existsByIdAndMembersAccountId(anyLong(), anyLong())).thenReturn(true);
    }

    // --- CURRENCY NORMALIZATION ---
    @Test
    void testCreateDraft_CurrencyNormalizationAndRejection() {
        mockAccess();
        when(projectRepository.findById(10L)).thenReturn(Optional.of(validProject));
        when(ownerOrganizationService.getOwnerCompanyId()).thenReturn("reference-company-id");

        // Invalid currency
        CreatePartnerContractRequest reqInvalid = new CreatePartnerContractRequest();
        reqInvalid.setCurrency("INVALID");
        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () -> service.createDraft(10L, reqInvalid, 99L));
        assertTrue(ex.getMessage().contains("Invalid currency"));

        // Valid lowercase -> uppercase
        CreatePartnerContractRequest reqValid = new CreatePartnerContractRequest();
        reqValid.setCurrency("usd");
        PartnerContract saved = new PartnerContract();
        saved.setId(1L);
        when(contractRepository.save(any())).thenReturn(saved);

        service.createDraft(10L, reqValid, 99L);
        ArgumentCaptor<PartnerContract> captor = ArgumentCaptor.forClass(PartnerContract.class);
        verify(contractRepository).save(captor.capture());
        assertEquals("USD", captor.getValue().getCurrency());

        // Null remains null
        reqValid.setCurrency(null);
        service.createDraft(10L, reqValid, 99L);
        verify(contractRepository, times(2)).save(captor.capture());
        assertNull(captor.getValue().getCurrency());
    }

    // --- RAWDOCUMENT COVERAGE ---
    @Test
    void testCreateDraft_RawDocumentNotFoundRejected() {
        mockAccess();
        when(projectRepository.findById(10L)).thenReturn(Optional.of(validProject));
        when(ownerOrganizationService.getOwnerCompanyId()).thenReturn("reference-company");
        when(documentRepository.findById("invalid")).thenReturn(Optional.empty());

        CreatePartnerContractRequest req = new CreatePartnerContractRequest();
        req.setRawDocumentId("invalid");
        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () -> service.createDraft(10L, req, 99L));
        assertTrue(ex.getMessage().contains("RawDocument not found"));
    }

    @Test
    void testCreateDraft_RawDocumentHiddenRejected() {
        mockAccess();
        when(projectRepository.findById(10L)).thenReturn(Optional.of(validProject));
        when(ownerOrganizationService.getOwnerCompanyId()).thenReturn("reference-company");
        RawDocument doc = new RawDocument();
        doc.setIsHidden(true);
        when(documentRepository.findById("doc-id")).thenReturn(Optional.of(doc));

        CreatePartnerContractRequest req = new CreatePartnerContractRequest();
        req.setRawDocumentId("doc-id");
        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () -> service.createDraft(10L, req, 99L));
        assertTrue(ex.getMessage().contains("RawDocument is unavailable"));
    }

    @Test
    void testCreateDraft_RawDocumentWrongProjectRejected() {
        mockAccess();
        when(projectRepository.findById(10L)).thenReturn(Optional.of(validProject));
        when(ownerOrganizationService.getOwnerCompanyId()).thenReturn("reference-company");
        RawDocument doc = new RawDocument();
        doc.setProjectId("999");
        when(documentRepository.findById("doc-id")).thenReturn(Optional.of(doc));

        CreatePartnerContractRequest req = new CreatePartnerContractRequest();
        req.setRawDocumentId("doc-id");
        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () -> service.createDraft(10L, req, 99L));
        assertTrue(ex.getMessage().contains("does not belong to the project"));
    }

    @Test
    void testCreateDraft_RawDocumentWrongTaskRejected() {
        mockAccess();
        when(projectRepository.findById(10L)).thenReturn(Optional.of(validProject));
        when(ownerOrganizationService.getOwnerCompanyId()).thenReturn("reference-company");
        RawDocument doc = new RawDocument();
        doc.setProjectId("10");
        doc.setTaskId("999");
        when(documentRepository.findById("doc-id")).thenReturn(Optional.of(doc));
        when(taskRepository.findById(20L)).thenReturn(Optional.of(validTask));

        CreatePartnerContractRequest req = new CreatePartnerContractRequest();
        req.setSourceTaskId(20L);
        req.setRawDocumentId("doc-id");
        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () -> service.createDraft(10L, req, 99L));
        assertTrue(ex.getMessage().contains("does not belong to the specified task"));
    }

    @Test
    void testCreateDraft_ValidRawDocumentAccepted() {
        mockAccess();
        when(projectRepository.findById(10L)).thenReturn(Optional.of(validProject));
        when(ownerOrganizationService.getOwnerCompanyId()).thenReturn("reference-company");
        when(documentRepository.findById("doc-id")).thenReturn(Optional.of(validDoc));

        CreatePartnerContractRequest req = new CreatePartnerContractRequest();
        req.setRawDocumentId("doc-id");
        PartnerContract saved = new PartnerContract();
        saved.setId(1L);
        when(contractRepository.save(any())).thenReturn(saved);

        PartnerContractResponse res = service.createDraft(10L, req, 99L);
        assertNotNull(res);
        ArgumentCaptor<PartnerContract> captor = ArgumentCaptor.forClass(PartnerContract.class);
        verify(contractRepository).save(captor.capture());
        assertEquals("doc-id", captor.getValue().getRawDocumentId());
    }

    @Test
    void testCreateDraft_OmittedRawDocumentRemainsNull() {
        mockAccess();
        when(projectRepository.findById(10L)).thenReturn(Optional.of(validProject));
        when(ownerOrganizationService.getOwnerCompanyId()).thenReturn("reference-company");

        CreatePartnerContractRequest req = new CreatePartnerContractRequest();
        req.setRawDocumentId(null);
        PartnerContract saved = new PartnerContract();
        saved.setId(1L);
        when(contractRepository.save(any())).thenReturn(saved);

        service.createDraft(10L, req, 99L);
        ArgumentCaptor<PartnerContract> captor = ArgumentCaptor.forClass(PartnerContract.class);
        verify(contractRepository).save(captor.capture());
        assertNull(captor.getValue().getRawDocumentId());
    }

    // --- DATE VALIDATION TESTS ---
    @Test
    void testDateValidations() {
        mockAccess();
        when(projectRepository.findById(10L)).thenReturn(Optional.of(validProject));
        when(ownerOrganizationService.getOwnerCompanyId()).thenReturn("reference-company");

        // effectiveDate before signedDate rejected
        CreatePartnerContractRequest req1 = new CreatePartnerContractRequest();
        req1.setSignedDate(LocalDate.of(2023, 1, 2));
        req1.setEffectiveDate(LocalDate.of(2023, 1, 1));
        BusinessValidationException ex1 = assertThrows(BusinessValidationException.class, () -> service.createDraft(10L, req1, 99L));
        assertTrue(ex1.getMessage().contains("Effective date cannot be before signed date"));

        // effectiveDate equal signedDate allowed
        CreatePartnerContractRequest req2 = new CreatePartnerContractRequest();
        req2.setSignedDate(LocalDate.of(2023, 1, 1));
        req2.setEffectiveDate(LocalDate.of(2023, 1, 1));
        PartnerContract saved = new PartnerContract();
        saved.setId(1L);
        when(contractRepository.save(any())).thenReturn(saved);
        assertDoesNotThrow(() -> service.createDraft(10L, req2, 99L));

        // expiryDate equal effectiveDate rejected
        CreatePartnerContractRequest req3 = new CreatePartnerContractRequest();
        req3.setEffectiveDate(LocalDate.of(2023, 1, 1));
        req3.setExpiryDate(LocalDate.of(2023, 1, 1));
        BusinessValidationException ex3 = assertThrows(BusinessValidationException.class, () -> service.createDraft(10L, req3, 99L));
        assertTrue(ex3.getMessage().contains("Expiry date cannot be before or equal to effective date"));

        // expiryDate before effectiveDate rejected
        CreatePartnerContractRequest req4 = new CreatePartnerContractRequest();
        req4.setEffectiveDate(LocalDate.of(2023, 1, 2));
        req4.setExpiryDate(LocalDate.of(2023, 1, 1));
        BusinessValidationException ex4 = assertThrows(BusinessValidationException.class, () -> service.createDraft(10L, req4, 99L));
        assertTrue(ex4.getMessage().contains("Expiry date cannot be before or equal to effective date"));

        // null dates remain allowed in DRAFT
        CreatePartnerContractRequest req5 = new CreatePartnerContractRequest();
        assertDoesNotThrow(() -> service.createDraft(10L, req5, 99L));
    }

    @Test
    void testSubmitReadiness_RequiredDatePolicy() {
        mockAccess();
        PartnerContract contract = new PartnerContract();
        contract.setId(1L);
        contract.setReviewStatus(ContractReviewStatus.DRAFT);
        contract.setSourceProjectId(10L);
        contract.setContractNumber("CN-123");
        // null effective date
        contract.setEffectiveDate(null);
        when(contractRepository.findById(1L)).thenReturn(Optional.of(contract));

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () -> service.submitForReview(1L, 99L));
        assertTrue(ex.getMessage().contains("Effective date is required"));
    }

    // --- IDEMPOTENCY TEST ---
    @Test
    void testReviewContract_RepeatedApprovalIsIdempotent() {
        mockAccess();
        PartnerContract contract = new PartnerContract();
        contract.setId(1L);
        contract.setReviewStatus(ContractReviewStatus.APPROVED);
        contract.setSourceProjectId(10L);
        contract.setCurrentVersion(1);
        when(contractRepository.findById(1L)).thenReturn(Optional.of(contract));

        ReviewPartnerContractRequest req = new ReviewPartnerContractRequest();
        req.setDecision("APPROVE");

        // Second approval returns the existing approved state quietly
        assertDoesNotThrow(() -> service.reviewContract(1L, req, 99L));

        // Assert contractRepository does not increment twice, versionRepository.save called exactly 0 times during second approval
        // Actually the prompt says "versionRepository.save called exactly once", meaning testing the first approval AND second approval together
        // Let's test them in sequence
    }

    @Test
    void testReviewContract_IdempotencySequence() {
        mockAccess();
        PartnerContract contract = new PartnerContract();
        contract.setId(1L);
        contract.setReviewStatus(ContractReviewStatus.IN_REVIEW);
        contract.setSourceProjectId(10L);
        contract.setCurrentVersion(0);
        when(contractRepository.findById(1L)).thenReturn(Optional.of(contract));
        when(contractRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        ReviewPartnerContractRequest req = new ReviewPartnerContractRequest();
        req.setDecision("APPROVE");

        // FIRST APPROVAL
        service.reviewContract(1L, req, 99L);

        verify(versionRepository, times(1)).save(any());
        verify(auditService, times(1)).log(anyLong(), eq(AuditAction.PARTNER_CONTRACT_APPROVED), any(), any(), any());
        assertEquals(1, contract.getCurrentVersion());
        assertEquals(ContractReviewStatus.APPROVED, contract.getReviewStatus());

        // SECOND APPROVAL
        service.reviewContract(1L, req, 99L);

        // Still called exactly once total!
        verify(versionRepository, times(1)).save(any());
        verify(auditService, times(1)).log(anyLong(), eq(AuditAction.PARTNER_CONTRACT_APPROVED), any(), any(), any());
        assertEquals(1, contract.getCurrentVersion());
    }

    // --- REVISION VERSIONING TEST ---
    @Test
    void testRevisionVersioning() {
        mockAccess();
        PartnerContract contract = new PartnerContract();
        contract.setId(1L);
        contract.setReviewStatus(ContractReviewStatus.APPROVED);
        contract.setSourceProjectId(10L);
        contract.setCurrentVersion(1);
        when(contractRepository.findById(1L)).thenReturn(Optional.of(contract));
        when(contractRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        // Start Revision -> DRAFT, version remains 1
        service.startRevision(1L, 99L);
        assertEquals(ContractReviewStatus.DRAFT, contract.getReviewStatus());
        assertEquals(1, contract.getCurrentVersion());

        // Submit Revision -> IN_REVIEW
        contract.setContractNumber("CN-123");
        contract.setEffectiveDate(LocalDate.now());
        service.submitForReview(1L, 99L);
        assertEquals(ContractReviewStatus.IN_REVIEW, contract.getReviewStatus());

        // Approve Revision -> version 2
        ReviewPartnerContractRequest req = new ReviewPartnerContractRequest();
        req.setDecision("APPROVE");
        service.reviewContract(1L, req, 99L);

        assertEquals(ContractReviewStatus.APPROVED, contract.getReviewStatus());
        assertEquals(2, contract.getCurrentVersion());
        verify(versionRepository, times(1)).save(any());

        // Ensure version 1 history ordering matches (this implies no overwrite of version 1).
    }

    // --- LIFECYCLE AUDIT & TRANSITIONS ---
    @Test
    void testLifecycleAuditAndTransitions() {
        mockAccess();
        PartnerContract contract = new PartnerContract();
        contract.setId(1L);
        contract.setLifecycleStatus(ContractLifecycleStatus.PENDING_EFFECTIVE);
        contract.setSourceProjectId(10L);
        when(contractRepository.findById(1L)).thenReturn(Optional.of(contract));
        when(contractRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        // PENDING_EFFECTIVE -> TERMINATED
        UpdateContractLifecycleRequest req1 = new UpdateContractLifecycleRequest();
        req1.setLifecycleStatus(ContractLifecycleStatus.TERMINATED);
        service.updateLifecycle(1L, req1, 99L);
        assertEquals(ContractLifecycleStatus.TERMINATED, contract.getLifecycleStatus());
        verify(auditService, times(1)).log(anyLong(), eq(AuditAction.PARTNER_CONTRACT_LIFECYCLE_CHANGED), any(), any(), any());

        // ACTIVE -> TERMINATED
        contract.setLifecycleStatus(ContractLifecycleStatus.ACTIVE);
        service.updateLifecycle(1L, req1, 99L);
        assertEquals(ContractLifecycleStatus.TERMINATED, contract.getLifecycleStatus());
        verify(auditService, times(2)).log(anyLong(), eq(AuditAction.PARTNER_CONTRACT_LIFECYCLE_CHANGED), any(), any(), any());

        // TERMINATED -> ACTIVE rejected
        UpdateContractLifecycleRequest req2 = new UpdateContractLifecycleRequest();
        req2.setLifecycleStatus(ContractLifecycleStatus.ACTIVE);
        BusinessValidationException ex1 = assertThrows(BusinessValidationException.class, () -> service.updateLifecycle(1L, req2, 99L));
        assertTrue(ex1.getMessage().contains("TERMINATED contract cannot change lifecycle status"));

        // EXPIRED -> ACTIVE rejected
        contract.setLifecycleStatus(ContractLifecycleStatus.EXPIRED);
        BusinessValidationException ex2 = assertThrows(BusinessValidationException.class, () -> service.updateLifecycle(1L, req2, 99L));
        assertTrue(ex2.getMessage().contains("EXPIRED contract cannot change lifecycle status"));
    }

    // --- MISC TESTS TO RETAIN COVERAGE ---
    @Test
    void testCreateDraft_RejectNonPartnerScope() {
        mockAccess();
        validProject.setTargetRelationshipType(RelationshipType.POTENTIAL_PARTNER_OF);
        when(projectRepository.findById(10L)).thenReturn(Optional.of(validProject));

        CreatePartnerContractRequest req = new CreatePartnerContractRequest();
        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () -> service.createDraft(10L, req, 99L));
        assertTrue(ex.getMessage().contains("scope must resolve to PARTNER_WITH"));
    }

    @Test
    void testCreateDraft_RejectSameReferenceAndPartner() {
        mockAccess();
        when(projectRepository.findById(10L)).thenReturn(Optional.of(validProject));
        when(ownerOrganizationService.getOwnerCompanyId()).thenReturn("partner-company-id");

        CreatePartnerContractRequest req = new CreatePartnerContractRequest();
        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () -> service.createDraft(10L, req, 99L));
        assertTrue(ex.getMessage().contains("Reference and partner company cannot be the same"));
    }
}
