package com.apms.domain.news.service;

import com.apms.common.enums.AuditAction;
import com.apms.common.enums.SubmissionType;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.news.entity.CompanyIntelligenceArticle;
import com.apms.domain.news.entity.CompanyNewsResearchDraft;
import com.apms.domain.news.entity.CompanyNewsResearchSubmissionPayload;
import com.apms.domain.news.enums.ConfidentialityLevel;
import com.apms.domain.news.enums.NewsDraftStatus;
import com.apms.domain.news.repository.CompanyIntelligenceArticleRepository;
import com.apms.domain.news.repository.CompanyNewsResearchDraftRepository;
import com.apms.domain.news.repository.CompanyNewsResearchSubmissionPayloadRepository;
import com.apms.domain.project.ProjectTaskSubmission;
import com.apms.domain.project.service.ProjectTaskSubmissionApprovalHandler;
import com.apms.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class CompanyNewsResearchApprovalHandler implements ProjectTaskSubmissionApprovalHandler {

    private final CompanyNewsResearchSubmissionPayloadRepository payloadRepository;
    private final CompanyNewsResearchDraftRepository draftRepository;
    private final CompanyIntelligenceArticleRepository articleRepository;
    private final AuditLogService auditLogService;

    @Override
    public boolean supports(SubmissionType submissionType) {
        return submissionType == SubmissionType.COMPANY_NEWS_RESEARCH;
    }

    @Override
    public void handleApproval(ProjectTaskSubmission submission, Long reviewerId, String reviewComment) {
        CompanyNewsResearchSubmissionPayload payload = payloadRepository.findBySubmissionId(submission.getId())
                .orElseThrow(() -> new IllegalStateException("Payload not found for submission " + submission.getId()));

        List<CompanyNewsResearchDraft> drafts = payload.getNewsDraftIds().stream()
                .map(id -> draftRepository.findById(id).orElseThrow(() -> new IllegalStateException("Draft not found: " + id)))
                .toList();

        for (CompanyNewsResearchDraft draft : drafts) {
            if (!articleRepository.existsBySourceDraftId(draft.getId())) {
                CompanyIntelligenceArticle article = CompanyIntelligenceArticle.builder()
                        .companyProfileId(payload.getTargetCompanyProfileId())
                        .title(draft.getTitle())
                        .summary(draft.getSummary())
                        .content(draft.getContent())
                        .imageStorageKey(draft.getImageStorageKey())
                        .externalImageUrl(draft.getExternalImageUrl())
                        .sourceName(draft.getSourceName())
                        .sourceUrl(draft.getSourceUrl())
                        .author(draft.getAuthor())
                        .publishedAt(draft.getPublishedAt())
                        .capturedAt(draft.getCapturedAt())
                        .tags(draft.getTags())
                        .confidentialityLevel(ConfidentialityLevel.CONFIDENTIAL)
                        .sourceProjectId(payload.getProjectId())
                        .sourceTaskId(payload.getTaskId())
                        .sourceSubmissionId(submission.getId())
                        .sourceDraftId(draft.getId())
                        .createdByAccountId(draft.getCreatedByAccountId())
                        .approvedByAccountId(reviewerId)
                        .approvedAt(LocalDateTime.now())
                        .build();

                articleRepository.save(article);
            }

            draft.setReviewStatus(NewsDraftStatus.APPROVED);
        }
        draftRepository.saveAll(drafts);

        auditLogService.log(reviewerId, AuditAction.COMPANY_NEWS_RESEARCH_APPROVED, "ProjectTaskSubmission", String.valueOf(submission.getId()), "News research submission approved");
    }

    @Override
    public void handleRejection(ProjectTaskSubmission submission, Long reviewerId, String reviewComment) {
        CompanyNewsResearchSubmissionPayload payload = payloadRepository.findBySubmissionId(submission.getId())
                .orElseThrow(() -> new IllegalStateException("Payload not found for submission " + submission.getId()));

        List<CompanyNewsResearchDraft> drafts = payload.getNewsDraftIds().stream()
                .map(id -> draftRepository.findById(id).orElseThrow(() -> new IllegalStateException("Draft not found: " + id)))
                .toList();

        for (CompanyNewsResearchDraft draft : drafts) {
            if (draft.getReviewStatus() == NewsDraftStatus.SUBMITTED) {
                draft.setReviewStatus(NewsDraftStatus.DRAFT);
            }
        }
        draftRepository.saveAll(drafts);

        auditLogService.log(reviewerId, AuditAction.COMPANY_NEWS_RESEARCH_REJECTED, "ProjectTaskSubmission", String.valueOf(submission.getId()), "News research submission rejected");
    }
}

