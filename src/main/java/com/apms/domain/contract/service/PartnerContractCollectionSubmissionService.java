package com.apms.domain.contract.service;

import com.apms.common.enums.SubmissionType;
import com.apms.domain.contract.dto.SubmitPartnerContractCollectionRequest;
import com.apms.domain.contract.entity.PartnerContractCollectionSubmissionPayload;
import com.apms.domain.contract.repository.mongo.PartnerContractCollectionSubmissionPayloadRepository;
import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.dto.CreateProjectTaskSubmissionRequest;
import com.apms.domain.project.dto.ProjectTaskSubmissionResponse;
import com.apms.domain.project.repository.sql.ProjectTaskRepository;
import com.apms.domain.project.service.ProjectTaskSubmissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.apms.common.exception.BusinessValidationException;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PartnerContractCollectionSubmissionService {

    private final ProjectTaskSubmissionService submissionService;
    private final PartnerContractCollectionSubmissionPayloadRepository payloadRepository;
    private final ProjectTaskRepository taskRepository;
    private final com.apms.domain.contract.repository.mongo.PartnerContractExtractionDraftRepository draftRepository;

    @Transactional
    public ProjectTaskSubmissionResponse submitCollection(Long projectId, Long taskId, SubmitPartnerContractCollectionRequest request) {
        ProjectTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessValidationException("Task not found"));

        if (task.getTaskType() != com.apms.common.enums.TaskType.PARTNER_CONTRACT_COLLECTION) {
            throw new BusinessValidationException("Task is not a PARTNER_CONTRACT_COLLECTION task");
        }

        if (request.getContractDraftIds() == null || request.getContractDraftIds().isEmpty()) {
            throw new BusinessValidationException("Contract draft list cannot be empty");
        }

        List<com.apms.domain.contract.entity.PartnerContractExtractionDraft> drafts = draftRepository.findAllById(request.getContractDraftIds());
        if (drafts.size() != request.getContractDraftIds().size()) {
            throw new BusinessValidationException("One or more contract drafts not found");
        }

        for (com.apms.domain.contract.entity.PartnerContractExtractionDraft draft : drafts) {
            if (!draft.getSourceProjectId().equals(projectId) || !draft.getSourceTaskId().equals(taskId) || !draft.getTargetCompanyProfileId().equals(task.getTargetCompanyProfileId())) {
                throw new BusinessValidationException("Draft does not belong to the same project/task/Partner");
            }
            if (draft.getReviewStatus() != com.apms.domain.contract.enums.ContractExtractionReviewStatus.REVIEWED) {
                throw new BusinessValidationException("All selected drafts must be reviewed");
            }
            if (draft.getApplicationStatus() == com.apms.domain.contract.enums.ContractExtractionApplicationStatus.APPLIED_FROZEN) {
                throw new BusinessValidationException("Applied drafts cannot be submitted again");
            }
        }

        // 1. Pre-create Payload in MongoDB to get ID
        PartnerContractCollectionSubmissionPayload payload = PartnerContractCollectionSubmissionPayload.builder()
                .projectId(projectId)
                .taskId(taskId)
                .targetCompanyProfileId(task.getTargetCompanyProfileId())
                .contractDraftIds(request.getContractDraftIds())
                .createdAt(LocalDateTime.now())
                .build();
        payload = payloadRepository.save(payload);

        // 2. Delegate to base submission service
        CreateProjectTaskSubmissionRequest baseRequest = new CreateProjectTaskSubmissionRequest();
        baseRequest.setSubmissionType(SubmissionType.PARTNER_CONTRACT_COLLECTION);
        baseRequest.setTargetEntityType("PartnerContractCollectionSubmissionPayload");
        baseRequest.setTargetEntityId(payload.getId());
        baseRequest.setNote(request.getNote());

        ProjectTaskSubmissionResponse response = submissionService.submitTask(projectId, taskId, baseRequest);

        // 3. Link back
        payload.setSubmissionId(response.getId());
        payloadRepository.save(payload);

        return response;
    }
}
