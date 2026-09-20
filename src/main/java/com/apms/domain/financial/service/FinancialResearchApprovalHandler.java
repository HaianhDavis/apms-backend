package com.apms.domain.financial.service;

import com.apms.common.enums.AuditAction;
import com.apms.common.enums.SubmissionType;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.financial.FinancialResearch;
import com.apms.domain.financial.FinancialResearchStatus;
import com.apms.domain.financial.repository.FinancialResearchRepository;
import com.apms.domain.project.ProjectTaskSubmission;
import com.apms.domain.project.service.ProjectTaskSubmissionApprovalHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@Slf4j
@RequiredArgsConstructor
public class FinancialResearchApprovalHandler implements ProjectTaskSubmissionApprovalHandler {

    private final FinancialResearchRepository researchRepository;
    private final AuditLogService auditLogService;
    private final com.apms.domain.profile.service.CompanyProfileFinancialService companyProfileFinancialService;

    @Override
    public boolean supports(SubmissionType submissionType) {
        return submissionType == SubmissionType.FINANCIAL_RESEARCH;
    }

    @Override
    public void handleApproval(ProjectTaskSubmission submission, Long reviewerId, String reviewNote) {
        if (submission.getProjectTask() != null) {
            Long taskId = submission.getProjectTask().getId();
            researchRepository.findByTaskId(taskId).ifPresent(res -> {
                res.setStatus(FinancialResearchStatus.APPROVED);
                res.setReviewedBy(reviewerId);
                res.setReviewedAt(LocalDateTime.now());
                if (reviewNote != null) {
                    res.setReviewReason(reviewNote);
                }
                FinancialResearch saved = researchRepository.save(res);
                try {
                    companyProfileFinancialService.promoteFromApprovedResearch(saved);
                } catch (Exception e) {
                    log.error("Failed to promote approved financial research {} to canonical profile: {}", saved.getId(), e.getMessage(), e);
                }
            });
        }
    }

    @Override
    public void handleRejection(ProjectTaskSubmission submission, Long reviewerId, String reviewNote) {
        if (submission.getProjectTask() != null) {
            Long taskId = submission.getProjectTask().getId();
            researchRepository.findByTaskId(taskId).ifPresent(res -> {
                res.setStatus(FinancialResearchStatus.CHANGES_REQUESTED);
                res.setReviewedBy(reviewerId);
                res.setReviewedAt(LocalDateTime.now());
                if (reviewNote != null) {
                    res.setReviewReason(reviewNote);
                }
                researchRepository.save(res);
            });
        }
    }
}
