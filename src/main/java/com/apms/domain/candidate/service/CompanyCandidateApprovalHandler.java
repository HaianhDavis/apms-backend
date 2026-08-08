package com.apms.domain.candidate.service;

import com.apms.common.enums.SubmissionType;
import com.apms.domain.candidate.dto.ApproveCandidateRequest;
import com.apms.domain.candidate.dto.RejectCandidateRequest;
import com.apms.domain.project.ProjectTaskSubmission;
import com.apms.domain.project.service.ProjectTaskSubmissionApprovalHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CompanyCandidateApprovalHandler implements ProjectTaskSubmissionApprovalHandler {

    private final CandidateService candidateService;

    @Override
    public boolean supports(SubmissionType submissionType) {
        return submissionType == SubmissionType.COMPANY_CANDIDATE;
    }

    @Override
    public void handleApproval(ProjectTaskSubmission submission, Long reviewerId, String comment) {
        if (submission.getTargetEntityId() == null) {
            log.warn("Cannot approve Candidate: targetEntityId is null in submission {}", submission.getId());
            return;
        }

        String candidateId = submission.getTargetEntityId();
        
        try {
            ApproveCandidateRequest request = new ApproveCandidateRequest();
            // RelationshipTypeOverride can be passed if needed, but for MVP we rely on the project's targetRelationshipType
            candidateService.approveCandidate(candidateId, request, reviewerId);
            log.info("Successfully approved Candidate {} through Task Submission {}", candidateId, submission.getId());
        } catch (Exception e) {
            log.error("Failed to approve Candidate {} during Task Submission {} approval", candidateId, submission.getId(), e);
            throw e;
        }
    }

    @Override
    public void handleRejection(ProjectTaskSubmission submission, Long reviewerId, String comment) {
        if (submission.getTargetEntityId() == null) {
            log.warn("Cannot reject Candidate: targetEntityId is null in submission {}", submission.getId());
            return;
        }

        String candidateId = submission.getTargetEntityId();
        
        try {
            RejectCandidateRequest request = new RejectCandidateRequest();
            request.setRejectionReason(comment);
            candidateService.rejectCandidate(candidateId, request, reviewerId);
            log.info("Successfully rejected Candidate {} through Task Submission {}", candidateId, submission.getId());
        } catch (Exception e) {
            log.error("Failed to reject Candidate {} during Task Submission {} rejection", candidateId, submission.getId(), e);
            throw e;
        }
    }
}
