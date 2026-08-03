package com.apms.domain.contract.service;

import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.contract.entity.PartnerContractCollectionSubmissionPayload;
import com.apms.domain.contract.repository.mongo.PartnerContractCollectionSubmissionPayloadRepository;
import com.apms.domain.contract.entity.PartnerContractExtractionDraft;
import com.apms.domain.contract.repository.mongo.PartnerContractExtractionDraftRepository;
import com.apms.domain.contract.repository.sql.PartnerContractRepository;
import com.apms.domain.contract.repository.sql.PartnerContractVersionRepository;
import com.apms.domain.contract.repository.sql.PartnerContractClauseVersionRepository;
import com.apms.domain.profile.service.OwnerOrganizationService;
import com.apms.domain.project.ProjectTaskSubmission;
import com.apms.domain.user.Account;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class PartnerContractCollectionApprovalHandlerTest {

    @Mock
    private PartnerContractCollectionSubmissionPayloadRepository payloadRepository;
    @Mock
    private PartnerContractExtractionDraftRepository draftRepository;
    @Mock
    private PartnerContractRepository contractRepository;
    @Mock
    private PartnerContractVersionRepository versionRepository;
    @Mock
    private PartnerContractClauseVersionRepository clauseVersionRepository;
    @Mock
    private OwnerOrganizationService ownerOrganizationService;
    @Mock
    private com.apms.domain.audit.service.AuditLogService auditLogService;

    @InjectMocks
    private PartnerContractCollectionApprovalHandler approvalHandler;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void handleApproval_createsOfficialContract() {
        ProjectTaskSubmission sub = new ProjectTaskSubmission();
        sub.setId(1L);
        Account acc = new Account();
        acc.setId(1L);
        sub.setSubmittedByAccount(acc);

        PartnerContractCollectionSubmissionPayload payload = PartnerContractCollectionSubmissionPayload.builder()
                .contractDraftIds(List.of("draft1"))
                .targetCompanyProfileId("partner1")
                .projectId(1L)
                .taskId(1L)
                .build();
        when(payloadRepository.findBySubmissionId(1L)).thenReturn(Optional.of(payload));
        when(ownerOrganizationService.getOwnerCompanyId()).thenReturn("owner1");

        PartnerContractExtractionDraft draft = new PartnerContractExtractionDraft();
        draft.setId("draft1");
        draft.setRawDocumentId("raw1");
        when(draftRepository.findById("draft1")).thenReturn(Optional.of(draft));
        
        when(contractRepository.findByExtractionDraftId("draft1")).thenReturn(Optional.empty());
        when(contractRepository.save(any())).thenAnswer(inv -> {
            com.apms.domain.contract.entity.PartnerContract c = inv.getArgument(0);
            c.setId(100L);
            return c;
        });
        when(versionRepository.save(any())).thenAnswer(inv -> {
            com.apms.domain.contract.entity.PartnerContractVersion v = inv.getArgument(0);
            v.setId(200L);
            return v;
        });

        approvalHandler.handleApproval(sub, 2L, "Approved");

        verify(contractRepository, times(1)).save(any());
        verify(versionRepository, times(1)).save(any());
    }

    @Test
    void handleApproval_isIdempotent() {
        ProjectTaskSubmission sub = new ProjectTaskSubmission();
        sub.setId(1L);
        Account acc = new Account();
        acc.setId(1L);
        sub.setSubmittedByAccount(acc);

        PartnerContractCollectionSubmissionPayload payload = PartnerContractCollectionSubmissionPayload.builder()
                .contractDraftIds(List.of("draft1"))
                .build();
        when(payloadRepository.findBySubmissionId(1L)).thenReturn(Optional.of(payload));
        when(ownerOrganizationService.getOwnerCompanyId()).thenReturn("owner1");

        PartnerContractExtractionDraft draft = new PartnerContractExtractionDraft();
        draft.setId("draft1");
        when(draftRepository.findById("draft1")).thenReturn(Optional.of(draft));
        
        // Return existing contract to test idempotency
        when(contractRepository.findByExtractionDraftId("draft1")).thenReturn(Optional.of(new com.apms.domain.contract.entity.PartnerContract()));

        approvalHandler.handleApproval(sub, 2L, "Approved");

        verify(contractRepository, never()).save(any());
        verify(versionRepository, never()).save(any());
    }
}
