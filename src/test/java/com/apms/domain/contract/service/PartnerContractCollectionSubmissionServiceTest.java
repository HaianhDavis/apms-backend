package com.apms.domain.contract.service;

import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.contract.entity.PartnerContractCollectionSubmissionPayload;
import com.apms.domain.contract.repository.mongo.PartnerContractCollectionSubmissionPayloadRepository;
import com.apms.domain.project.ProjectTask;

import com.apms.domain.project.repository.sql.ProjectTaskRepository;
import com.apms.domain.project.service.ProjectTaskSubmissionService;
import com.apms.domain.contract.dto.SubmitPartnerContractCollectionRequest;
import com.apms.domain.contract.entity.PartnerContractExtractionDraft;
import com.apms.domain.contract.repository.mongo.PartnerContractExtractionDraftRepository;
import com.apms.domain.contract.enums.ContractExtractionReviewStatus;
import com.apms.domain.contract.enums.ContractExtractionApplicationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class PartnerContractCollectionSubmissionServiceTest {

    @Mock
    private ProjectTaskSubmissionService baseSubmissionService;
    @Mock
    private PartnerContractCollectionSubmissionPayloadRepository payloadRepository;
    @Mock
    private ProjectTaskRepository taskRepository;
    @Mock
    private PartnerContractExtractionDraftRepository draftRepository;

    @InjectMocks
    private PartnerContractCollectionSubmissionService submissionService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void submitCollection_emptyDrafts_rejected() {
        ProjectTask task = new ProjectTask();
        task.setId(1L);
        task.setTaskType(com.apms.common.enums.TaskType.PARTNER_CONTRACT_COLLECTION);
        when(taskRepository.findById(1L)).thenReturn(Optional.of(task));

        SubmitPartnerContractCollectionRequest req = new SubmitPartnerContractCollectionRequest();
        req.setContractDraftIds(List.of());

        assertThrows(BusinessValidationException.class, () -> submissionService.submitCollection(1L, 1L, req));
    }

    @Test
    void submitCollection_unreviewedDrafts_rejected() {
        ProjectTask task = new ProjectTask();
        task.setId(1L);
        task.setTargetCompanyProfileId("partner1");
        task.setTaskType(com.apms.common.enums.TaskType.PARTNER_CONTRACT_COLLECTION);
        when(taskRepository.findById(1L)).thenReturn(Optional.of(task));

        PartnerContractExtractionDraft draft = new PartnerContractExtractionDraft();
        draft.setSourceProjectId(1L);
        draft.setSourceTaskId(1L);
        draft.setTargetCompanyProfileId("partner1");
        draft.setReviewStatus(ContractExtractionReviewStatus.PENDING); // Not reviewed

        when(draftRepository.findAllById(any())).thenReturn(List.of(draft));

        SubmitPartnerContractCollectionRequest req = new SubmitPartnerContractCollectionRequest();
        req.setContractDraftIds(List.of("draft1"));

        assertThrows(BusinessValidationException.class, () -> submissionService.submitCollection(1L, 1L, req));
    }
}
