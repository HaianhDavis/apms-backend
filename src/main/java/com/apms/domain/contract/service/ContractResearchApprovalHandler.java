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

    @Override
    public boolean supports(SubmissionType submissionType) {
        return submissionType == SubmissionType.PARTNER_CONTRACT_COLLECTION;
    }

    @Override
    public void handleApproval(ProjectTaskSubmission submission, Long reviewerId, String reviewNote) {
        throw new UnsupportedOperationException("Contract Research must be reviewed at the individual contract level.");
    }

    @Override
    public void handleRejection(ProjectTaskSubmission submission, Long reviewerId, String reviewNote) {
        throw new UnsupportedOperationException("Contract Research must be reviewed at the individual contract level.");
    }
}
