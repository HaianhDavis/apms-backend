package com.apms.domain.contract.service;

import com.apms.common.enums.SubmissionType;
import com.apms.domain.contract.repository.mongo.ContractResearchRepository;
import com.apms.domain.project.ProjectTaskSubmission;
import com.apms.domain.project.service.ProjectTaskSubmissionApprovalHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class ContractResearchApprovalHandler implements ProjectTaskSubmissionApprovalHandler {

    private final ContractResearchRepository contractResearchRepository;
    private final com.apms.domain.profile.service.CompanyProfileContractService companyProfileContractService;

    @Override
    public boolean supports(SubmissionType submissionType) {
        return submissionType == SubmissionType.PARTNER_CONTRACT_COLLECTION;
    }

    @Override
    public void handleApproval(ProjectTaskSubmission submission, Long reviewerId, String reviewNote) {
        if (submission.getProjectTask() != null) {
            Long taskId = submission.getProjectTask().getId();
            contractResearchRepository.findByTaskId(taskId).ifPresent(res -> {
                res.setStatus(com.apms.domain.contract.enums.ContractResearchStatus.APPROVED);
                res.setReviewedBy(reviewerId);
                res.setReviewedAt(java.time.LocalDateTime.now());
                if (reviewNote != null) {
                    res.setReviewReason(reviewNote);
                }
                var saved = contractResearchRepository.save(res);
                try {
                    companyProfileContractService.promoteFromApprovedResearch(saved);
                } catch (Exception e) {
                    log.error("Failed to promote approved contracts for research {}: {}", saved.getId(), e.getMessage(), e);
                }
            });
        }
    }

    @Override
    public void handleRejection(ProjectTaskSubmission submission, Long reviewerId, String reviewNote) {
        if (submission.getProjectTask() != null) {
            Long taskId = submission.getProjectTask().getId();
            contractResearchRepository.findByTaskId(taskId).ifPresent(res -> {
                res.setStatus(com.apms.domain.contract.enums.ContractResearchStatus.CHANGES_REQUESTED);
                res.setReviewedBy(reviewerId);
                res.setReviewedAt(java.time.LocalDateTime.now());
                if (reviewNote != null) {
                    res.setReviewReason(reviewNote);
                }
                contractResearchRepository.save(res);
            });
        }
    }
}
