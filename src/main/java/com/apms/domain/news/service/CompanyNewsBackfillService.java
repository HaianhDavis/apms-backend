package com.apms.domain.news.service;

import com.apms.common.enums.AuditAction;
import com.apms.common.enums.SubmissionStatus;
import com.apms.common.enums.SubmissionType;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.news.entity.CompanyIntelligenceArticle;
import com.apms.domain.news.entity.CompanyNewsResearchDraft;
import com.apms.domain.news.entity.CompanyNewsResearchSubmissionPayload;
import com.apms.domain.news.enums.ConfidentialityLevel;
import com.apms.domain.news.repository.CompanyIntelligenceArticleRepository;
import com.apms.domain.news.repository.CompanyNewsResearchDraftRepository;
import com.apms.domain.news.repository.CompanyNewsResearchSubmissionPayloadRepository;
import com.apms.domain.project.Project;
import com.apms.domain.project.ProjectTaskSubmission;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.apms.domain.project.repository.sql.ProjectTaskSubmissionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class CompanyNewsBackfillService {

    private final ProjectTaskSubmissionRepository submissionRepository;
    private final CompanyNewsResearchSubmissionPayloadRepository payloadRepository;
    private final CompanyNewsResearchDraftRepository draftRepository;
    private final CompanyIntelligenceArticleRepository articleRepository;
    private final ProjectRepository projectRepository;
    private final AuditLogService auditLogService;

    @Transactional
    public String backfillApprovedSubmissions(Long adminUserId) {
        List<ProjectTaskSubmission> approvedSubmissions = submissionRepository.findBySubmissionTypeAndStatus(
                SubmissionType.COMPANY_NEWS_RESEARCH, SubmissionStatus.APPROVED);

        int totalSubmissions = approvedSubmissions.size();
        int publishedArticles = 0;
        int skippedArticles = 0;
        int errorMissingProfile = 0;

        for (ProjectTaskSubmission submission : approvedSubmissions) {
            Optional<CompanyNewsResearchSubmissionPayload> payloadOpt = payloadRepository.findBySubmissionId(submission.getId());
            if (payloadOpt.isEmpty()) {
                continue;
            }

            CompanyNewsResearchSubmissionPayload payload = payloadOpt.get();
            String companyProfileId = payload.getTargetCompanyProfileId();
            
            if (!StringUtils.hasText(companyProfileId)) {
                Optional<Project> projectOpt = projectRepository.findById(payload.getProjectId());
                if (projectOpt.isPresent()) {
                    companyProfileId = projectOpt.get().getTargetCompanyProfileId();
                }
            }

            if (!StringUtils.hasText(companyProfileId)) {
                errorMissingProfile++;
                continue;
            }

            if (payload.getNewsDraftIds() == null) continue;

            for (String draftId : payload.getNewsDraftIds()) {
                if (articleRepository.existsBySourceDraftId(draftId)) {
                    skippedArticles++;
                    continue;
                }

                Optional<CompanyNewsResearchDraft> draftOpt = draftRepository.findById(draftId);
                if (draftOpt.isEmpty()) continue;

                CompanyNewsResearchDraft draft = draftOpt.get();

                CompanyIntelligenceArticle article = CompanyIntelligenceArticle.builder()
                        .companyProfileId(companyProfileId)
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
                        .approvedByAccountId(submission.getReviewedByAccount() != null ? submission.getReviewedByAccount().getId() : adminUserId)
                        .approvedAt(submission.getReviewedAt() != null ? submission.getReviewedAt() : LocalDateTime.now())
                        .build();

                CompanyIntelligenceArticle savedArticle = articleRepository.save(article);
                auditLogService.log(adminUserId, AuditAction.INTERNAL_NEWS_PUBLISHED, "CompanyIntelligenceArticle", savedArticle.getId(), "Backfilled internal news from draft");
                publishedArticles++;
            }
        }

        String result = String.format("Backfill Complete. Submissions checked: %d. Published: %d. Skipped (already exist): %d. Errors (Missing Profile Link): %d.", 
                totalSubmissions, publishedArticles, skippedArticles, errorMissingProfile);
        log.info(result);
        return result;
    }
}
