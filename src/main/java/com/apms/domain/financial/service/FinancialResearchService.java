package com.apms.domain.financial.service;

import com.apms.common.enums.AuditAction;
import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.document.RawDocument;
import com.apms.domain.document.repository.mongo.RawDocumentRepository;
import com.apms.domain.financial.*;
import com.apms.domain.financial.dto.*;
import com.apms.domain.financial.repository.FinancialResearchRepository;
import com.apms.domain.project.repository.sql.ProjectTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import com.apms.security.UserDetailsImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class FinancialResearchService {

    private final FinancialResearchRepository researchRepository;
    private final RawDocumentRepository documentRepository;
    private final ProjectTaskRepository projectTaskRepository;
    private final com.apms.domain.project.repository.sql.ProjectTaskSubmissionRepository submissionRepository;
    private final FinancialExtractionService extractionService;
    private final AuditLogService auditLogService;
    private final com.apms.domain.user.repository.sql.UserProfileRepository userProfileRepository;

    @Autowired
    @Lazy
    private FinancialResearchService self;

    @Value("${app.financial.extraction.stale-timeout-minutes:10}")
    private int staleTimeoutMinutes;

    // --- Stage progress constants ---
    private static final int PROGRESS_QUEUED = 5;
    private static final int PROGRESS_PARSING = 15;
    private static final int PROGRESS_EXTRACTING = 45;
    private static final int PROGRESS_VALIDATING = 75;
    private static final int PROGRESS_SAVING = 90;
    private static final int PROGRESS_COMPLETED = 100;

    @Transactional
    public Optional<FinancialResearchResponse> getResearch(Long projectId, Long taskId) {
        FinancialResearch research = researchRepository.findByTaskId(taskId).orElse(null);
        String targetProfileId = null;
        try {
            targetProfileId = projectTaskRepository.findWithProjectById(taskId)
                    .map(t -> {
                        if (t.getTargetCompanyProfileId() != null) return t.getTargetCompanyProfileId();
                        if (t.getProject() != null) return t.getProject().getTargetCompanyProfileId();
                        return null;
                    }).orElse(null);
        } catch (Exception e) {
            log.warn("Could not fetch target profile id for task {}: {}", taskId, e.getMessage());
        }

        if (research == null) {
            research = FinancialResearch.builder()
                    .taskId(taskId)
                    .projectId(projectId)
                    .companyProfileId(targetProfileId)
                    .status(FinancialResearchStatus.DRAFT)
                    .reports(new ArrayList<>())
                    .metrics(new ArrayList<>())
                    .build();
            research = researchRepository.save(research);
        } else {
            if (research.getCompanyProfileId() == null && targetProfileId != null) {
                research.setCompanyProfileId(targetProfileId);
                research = researchRepository.save(research);
            }
            if (research.getStatus() == FinancialResearchStatus.DRAFT && research.getReports() != null) {
                boolean modified = false;
                for (FinancialReportEntry rep : research.getReports()) {
                    if (rep.getReviewStatus() == FinancialReportReviewStatus.PENDING_REVIEW) {
                        rep.setReviewStatus(null);
                        modified = true;
                    }
                }
                if (modified) {
                    research = researchRepository.save(research);
                }
            }

            // Auto-heal state sync between ProjectTask, ProjectTaskSubmission and FinancialResearch
            try {
                com.apms.domain.project.ProjectTask pt = projectTaskRepository.findWithProjectById(taskId).orElse(null);
                if (pt != null) {
                    List<com.apms.domain.project.ProjectTaskSubmission> activeSubs = submissionRepository.findByProjectTask_Id(taskId).stream()
                            .filter(s -> s.getStatus() == com.apms.common.enums.SubmissionStatus.IN_REVIEW)
                            .collect(Collectors.toList());
                    boolean hasActiveSub = !activeSubs.isEmpty();

                    if (research.getStatus() == FinancialResearchStatus.DRAFT && pt.getStatus() == com.apms.common.enums.TaskStatus.IN_REVIEW && !hasActiveSub) {
                        pt.setStatus(com.apms.common.enums.TaskStatus.IN_PROGRESS);
                        projectTaskRepository.save(pt);
                    } else if (hasActiveSub) {
                        if (research.getStatus() != FinancialResearchStatus.SUBMITTED) {
                            research.setStatus(FinancialResearchStatus.SUBMITTED);
                        }
                        if (research.getSubmittedReportIds() == null || research.getSubmittedReportIds().isEmpty()) {
                            com.apms.domain.project.ProjectTaskSubmission sub = activeSubs.get(0);
                            if (sub.getTargetItemIdList() != null && !sub.getTargetItemIdList().isEmpty()) {
                                research.setSubmittedReportIds(new ArrayList<>(sub.getTargetItemIdList()));
                            } else if (research.getReports() != null) {
                                List<String> autoIds = research.getReports().stream()
                                        .filter(r -> r.getReviewStatus() != FinancialReportReviewStatus.APPROVED)
                                        .map(FinancialReportEntry::getId)
                                        .collect(Collectors.toList());
                                research.setSubmittedReportIds(autoIds);
                            }
                        }
                        research = researchRepository.save(research);
                    }
                }
            } catch (Exception e) {
                log.warn("Could not sync task status in getResearch: {}", e.getMessage());
            }

            recoverStaleExtractions(research);
        }
        return Optional.of(toResponse(research));
    }

    public FinancialResearchResponse addReport(Long projectId, Long taskId, CreateFinancialReportRequest request) {
        FinancialResearch research = researchRepository.findByTaskId(taskId)
                .orElseThrow(() -> new BusinessValidationException("Research not found"));
        
        if (research.getStatus() != FinancialResearchStatus.DRAFT && research.getStatus() != FinancialResearchStatus.CHANGES_REQUESTED) {
            throw new BusinessValidationException("Cannot add report to submitted or approved research");
        }

        FinancialReportEntry entry = FinancialReportEntry.builder()
                .id(UUID.randomUUID().toString())
                .documentId(request.getDocumentId())
                .title(request.getTitle())
                .publicationDate(request.getPublicationDate())
                .reportingPeriod(request.getReportingPeriod())
                .reportType(request.getReportType())
                .statementScope(request.getStatementScope())
                .extractionStatus(ExtractionStatus.NOT_EXTRACTED)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        if (research.getReports() == null) {
            research.setReports(new ArrayList<>());
        }
        research.getReports().add(entry);
        research = researchRepository.save(research);

        auditLogService.log(getCurrentUserId(), AuditAction.FINANCIAL_RESEARCH_CREATED, "ProjectTask", taskId.toString(), "Added financial report");
        
        return toResponse(research);
    }

    public FinancialResearchResponse removeReport(Long projectId, Long taskId, String reportId) {
        FinancialResearch research = researchRepository.findByTaskId(taskId)
                .orElseThrow(() -> new BusinessValidationException("Research not found"));
                
        if (research.getStatus() != FinancialResearchStatus.DRAFT && research.getStatus() != FinancialResearchStatus.CHANGES_REQUESTED) {
            throw new BusinessValidationException("Cannot remove report from submitted or approved research");
        }

        if (research.getReports() != null) {
            research.getReports().removeIf(r -> r.getId().equals(reportId));
        }
        if (research.getMetrics() != null) {
            research.getMetrics().removeIf(m -> m.getSource() != null && reportId.equals(m.getSource().getReportEntryId()));
        }
        
        research = researchRepository.save(research);
        return toResponse(research);
    }

    /**
     * Start async extraction for a report. Returns immediately with EXTRACTING state.
     * The actual extraction runs in a background thread.
     */
    public FinancialResearchResponse extractReport(Long projectId, Long taskId, String reportId) {
        FinancialResearch research = researchRepository.findByTaskId(taskId)
                .orElseThrow(() -> new BusinessValidationException("Research not found"));
                
        FinancialReportEntry report = research.getReports().stream()
                .filter(r -> r.getId().equals(reportId)).findFirst()
                .orElseThrow(() -> new BusinessValidationException("Report not found"));

        // Prevent duplicate extraction
        if (report.getExtractionStatus() == ExtractionStatus.EXTRACTING) {
            // Already running — return current state without starting another
            return toResponse(research);
        }

        // Set initial extraction state
        report.setExtractionStatus(ExtractionStatus.EXTRACTING);
        report.setExtractionStage(FinancialExtractionStage.QUEUED);
        report.setExtractionProgress(PROGRESS_QUEUED);
        report.setExtractionStartedAt(LocalDateTime.now());
        report.setExtractionCompletedAt(null);
        report.setExtractionErrorCode(null);
        report.setExtractionErrorMessage(null);
        report.setUpdatedAt(LocalDateTime.now());

        research = researchRepository.save(research);

        // Launch async extraction
        self.executeExtractionAsync(taskId, reportId);

        auditLogService.log(getCurrentUserId(), AuditAction.FINANCIAL_AI_EXTRACTION_RUN, "ProjectTask", taskId.toString(), "Started AI extraction for report " + reportId);
        return toResponse(research);
    }

    /**
     * Re-extract: remove unverified AI metrics, then start async extraction.
     */
    public FinancialResearchResponse reExtractReport(Long projectId, Long taskId, String reportId) {
        FinancialResearch research = researchRepository.findByTaskId(taskId)
                .orElseThrow(() -> new BusinessValidationException("Research not found"));

        FinancialReportEntry report = research.getReports().stream()
                .filter(r -> r.getId().equals(reportId)).findFirst()
                .orElseThrow(() -> new BusinessValidationException("Report not found"));

        // Prevent duplicate extraction
        if (report.getExtractionStatus() == ExtractionStatus.EXTRACTING) {
            return toResponse(research);
        }
                
        if (research.getMetrics() == null) {
            research.setMetrics(new ArrayList<>());
        }
        
        // Keep verified and manual metrics for this report, remove unverified AI metrics
        research.getMetrics().removeIf(m -> 
                m.getSource() != null && reportId.equals(m.getSource().getReportEntryId()) &&
                m.getInputMethod() == MetricInputMethod.AI_EXTRACTED &&
                m.getVerificationStatus() == MetricVerificationStatus.UNVERIFIED);
                
        // Set initial extraction state
        report.setExtractionStatus(ExtractionStatus.EXTRACTING);
        report.setExtractionStage(FinancialExtractionStage.QUEUED);
        report.setExtractionProgress(PROGRESS_QUEUED);
        report.setExtractionStartedAt(LocalDateTime.now());
        report.setExtractionCompletedAt(null);
        report.setExtractionErrorCode(null);
        report.setExtractionErrorMessage(null);
        report.setUpdatedAt(LocalDateTime.now());

        research = researchRepository.save(research);

        // Launch async extraction
        self.executeExtractionAsync(taskId, reportId);

        auditLogService.log(getCurrentUserId(), AuditAction.FINANCIAL_AI_EXTRACTION_RUN, "ProjectTask", taskId.toString(), "Started re-extraction for report " + reportId);
        return toResponse(research);
    }

    /**
     * Async extraction worker. Runs on the taskExecutor thread pool.
     * Updates progress at each real pipeline stage.
     */
    @Async("taskExecutor")
    public void executeExtractionAsync(Long taskId, String reportId) {
        log.info("Starting async financial extraction for task={} report={}", taskId, reportId);

        try {
            // --- STAGE: PARSING_DOCUMENT ---
            updateExtractionProgress(taskId, reportId, FinancialExtractionStage.PARSING_DOCUMENT, PROGRESS_PARSING);

            FinancialResearch research = researchRepository.findByTaskId(taskId)
                    .orElseThrow(() -> new RuntimeException("Research not found for task " + taskId));
            FinancialReportEntry report = research.getReports().stream()
                    .filter(r -> r.getId().equals(reportId)).findFirst()
                    .orElseThrow(() -> new RuntimeException("Report not found: " + reportId));

            RawDocument doc = documentRepository.findById(report.getDocumentId())
                    .orElseThrow(() -> new BusinessValidationException("Document not found"));

            // Check if valid document
            if (!extractionService.isExtractable(doc)) {
                failExtraction(taskId, reportId, "EMPTY_DOCUMENT", "Document contains no extractable data.");
                return;
            }

            // --- STAGE: EXTRACTING_METRICS ---
            updateExtractionProgress(taskId, reportId, FinancialExtractionStage.EXTRACTING_METRICS, PROGRESS_EXTRACTING);

            FinancialDocumentExtractionResult docResult = extractionService.callAiExtraction(doc);
            if (docResult == null) {
                // If multimodal fallback to text failed as well (returning null), or Gemini had a bad request
                failExtraction(taskId, reportId, "AI_NO_RESULT", "AI did not return extraction results. Please retry.");
                return;
            }

            // --- STAGE: VALIDATING_RESULTS ---
            updateExtractionProgress(taskId, reportId, FinancialExtractionStage.VALIDATING_RESULTS, PROGRESS_VALIDATING);

            // Re-read research to get fresh state (other reports may have changed)
            research = researchRepository.findByTaskId(taskId)
                    .orElseThrow(() -> new RuntimeException("Research not found for task " + taskId));
            report = research.getReports().stream()
                    .filter(r -> r.getId().equals(reportId)).findFirst()
                    .orElseThrow(() -> new RuntimeException("Report not found: " + reportId));

            String targetCompanyName = self.fetchTargetCompanyName(taskId);

            DocumentContext context = mapToDocumentContext(docResult.getDocumentContextCandidate(), doc, targetCompanyName, report.getReportingPeriod());
            report.setDocumentContext(context);

            List<FinancialMetric> newMetrics = new ArrayList<>();
            if (docResult.getMetricCandidates() != null) {
                java.util.Set<String> existingKeys = research.getMetrics().stream()
                        .filter(m -> m.getSource() != null && reportId.equals(m.getSource().getReportEntryId()))
                        .map(FinancialMetric::getNormalizedKey)
                        .filter(java.util.Objects::nonNull)
                        .collect(java.util.stream.Collectors.toSet());

                for (AiFinancialMetricCandidate candidate : docResult.getMetricCandidates()) {
                    FinancialMetric metric = mapToMetric(candidate, doc, report.getId());
                    if (!existingKeys.contains(metric.getNormalizedKey())) {
                        newMetrics.add(metric);
                        existingKeys.add(metric.getNormalizedKey());
                    }
                }
            }

            // --- STAGE: SAVING_RESULTS ---
            updateExtractionProgress(taskId, reportId, FinancialExtractionStage.SAVING_RESULTS, PROGRESS_SAVING);

            research.getMetrics().addAll(newMetrics);

            // --- STAGE: COMPLETED ---
            report.setExtractionStatus(ExtractionStatus.EXTRACTED);
            report.setExtractionStage(FinancialExtractionStage.COMPLETED);
            report.setExtractionProgress(PROGRESS_COMPLETED);
            report.setExtractionCompletedAt(LocalDateTime.now());
            report.setUpdatedAt(LocalDateTime.now());

            researchRepository.save(research);
            log.info("Financial extraction completed for task={} report={}, {} metrics extracted", taskId, reportId, newMetrics.size());

        } catch (BusinessValidationException e) {
            log.error("Business validation failed during extraction for task={} report={}: {}", taskId, reportId, e.getMessage());
            failExtraction(taskId, reportId, "AI_EXTRACTION_FAILED", e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error during extraction for task={} report={}", taskId, reportId, e);
            failExtraction(taskId, reportId, "AI_EXTRACTION_FAILED", "Unable to extract financial data from this report. Please retry.");
        }
    }

    /**
     * Update extraction progress for a specific report. Persists to MongoDB.
     */
    private void updateExtractionProgress(Long taskId, String reportId, FinancialExtractionStage stage, int progress) {
        try {
            FinancialResearch research = researchRepository.findByTaskId(taskId).orElse(null);
            if (research == null) return;

            research.getReports().stream()
                    .filter(r -> r.getId().equals(reportId))
                    .findFirst()
                    .ifPresent(report -> {
                        report.setExtractionStage(stage);
                        report.setExtractionProgress(progress);
                        report.setUpdatedAt(LocalDateTime.now());
                    });

            researchRepository.save(research);
            log.debug("Extraction progress updated: task={} report={} stage={} progress={}%", taskId, reportId, stage, progress);
        } catch (Exception e) {
            log.warn("Failed to update extraction progress for task={} report={}: {}", taskId, reportId, e.getMessage());
        }
    }

    /**
     * Mark extraction as failed with a safe error message.
     */
    private void failExtraction(Long taskId, String reportId, String errorCode, String errorMessage) {
        try {
            FinancialResearch research = researchRepository.findByTaskId(taskId).orElse(null);
            if (research == null) return;

            research.getReports().stream()
                    .filter(r -> r.getId().equals(reportId))
                    .findFirst()
                    .ifPresent(report -> {
                        report.setExtractionStatus(ExtractionStatus.FAILED);
                        report.setExtractionStage(FinancialExtractionStage.FAILED);
                        report.setExtractionCompletedAt(LocalDateTime.now());
                        report.setExtractionErrorCode(errorCode);
                        report.setExtractionErrorMessage(errorMessage);
                        report.setUpdatedAt(LocalDateTime.now());
                        // Keep last progress value — don't reset to 0
                    });

            researchRepository.save(research);
        } catch (Exception e) {
            log.error("Failed to persist extraction failure for task={} report={}", taskId, reportId, e);
        }
    }

    /**
     * Recover stale extractions that were stuck in EXTRACTING state.
     * Called on getResearch to clean up stuck jobs.
     */
    private void recoverStaleExtractions(FinancialResearch research) {
        if (research.getReports() == null) return;
        boolean changed = false;
        for (FinancialReportEntry report : research.getReports()) {
            if (report.getExtractionStatus() == ExtractionStatus.EXTRACTING
                    && report.getExtractionStartedAt() != null
                    && Duration.between(report.getExtractionStartedAt(), LocalDateTime.now()).toMinutes() >= staleTimeoutMinutes) {
                log.warn("Recovering stale extraction: report={} startedAt={}", report.getId(), report.getExtractionStartedAt());
                report.setExtractionStatus(ExtractionStatus.FAILED);
                report.setExtractionStage(FinancialExtractionStage.FAILED);
                report.setExtractionCompletedAt(LocalDateTime.now());
                report.setExtractionErrorCode("EXTRACTION_TIMEOUT");
                report.setExtractionErrorMessage("Extraction interrupted. Please retry.");
                report.setUpdatedAt(LocalDateTime.now());
                changed = true;
            }
        }
        if (changed) {
            researchRepository.save(research);
        }
    }

    public FinancialResearchResponse addManualMetric(Long projectId, Long taskId, CreateFinancialMetricRequest request) {
        FinancialResearch research = researchRepository.findByTaskId(taskId).orElseThrow();
        
        FinancialMetric metric = FinancialMetric.builder()
                .id(UUID.randomUUID().toString())
                .label(request.getLabel())
                .normalizedKey(generateNormalizedKey(request.getLabel()))
                .rawValue(request.getRawValue())
                .rawUnit(request.getRawUnit())
                .evidence(request.getEvidence())
                .inputMethod(MetricInputMethod.MANUAL)
                .period(request.getPeriod())
                .source(MetricSource.builder()
                        .reportEntryId(request.getReportEntryId())
                        .documentId(request.getSourceDocumentId())
                        .page(request.getSourcePage())
                        .build())
                .qualityStatus(MetricQualityStatus.VALID)
                .verificationStatus(MetricVerificationStatus.VERIFIED)
                .build();

        NormalizedValue norm = normalizeValue(request.getRawValue(), request.getRawUnit());
        metric.setNormalizedValue(norm.value);
        metric.setNormalizedUnit(norm.unit);

        research.getMetrics().add(metric);
        research = researchRepository.save(research);
        return toResponse(research);
    }

    private void validateMetricEditable(FinancialResearch research, FinancialMetric metric) {
        if (research.getStatus() == FinancialResearchStatus.SUBMITTED || research.getStatus() == FinancialResearchStatus.APPROVED) {
            throw new BusinessValidationException("Cannot modify metrics in a submitted or approved research package.");
        }
        
        if (metric.getSource() != null && metric.getSource().getReportEntryId() != null) {
            research.getReports().stream()
                    .filter(r -> r.getId().equals(metric.getSource().getReportEntryId()))
                    .findFirst()
                    .ifPresent(report -> {
                        if (report.getReviewStatus() == FinancialReportReviewStatus.APPROVED) {
                            throw new BusinessValidationException("Cannot modify metrics for an approved report.");
                        }
                    });
        }
    }

    public FinancialResearchResponse updateMetric(Long projectId, Long taskId, String metricId, UpdateFinancialMetricRequest request) {
        FinancialResearch research = researchRepository.findByTaskId(taskId).orElseThrow();
        FinancialMetric metric = research.getMetrics().stream().filter(m -> m.getId().equals(metricId)).findFirst().orElseThrow();
        
        validateMetricEditable(research, metric);

        metric.setLabel(request.getLabel());
        metric.setRawValue(request.getRawValue());
        metric.setRawUnit(request.getRawUnit());
        metric.setPeriod(request.getPeriod());
        if (request.getEvidence() != null) {
            metric.setEvidence(request.getEvidence());
        }
        
        NormalizedValue norm = normalizeValue(request.getRawValue(), request.getRawUnit());
        metric.setNormalizedValue(norm.value);
        metric.setNormalizedUnit(norm.unit);
        
        metric.setQualityStatus(MetricQualityStatus.VALID);
        metric.setVerificationStatus(MetricVerificationStatus.VERIFIED);
        
        research = researchRepository.save(research);
        return toResponse(research);
    }

    public FinancialResearchResponse removeMetric(Long projectId, Long taskId, String metricId) {
        FinancialResearch research = researchRepository.findByTaskId(taskId).orElseThrow();
        FinancialMetric metric = research.getMetrics().stream().filter(m -> m.getId().equals(metricId)).findFirst().orElseThrow();
        validateMetricEditable(research, metric);
        
        research.getMetrics().remove(metric);
        research = researchRepository.save(research);
        return toResponse(research);
    }

    public FinancialResearchResponse verifyMetric(Long projectId, Long taskId, String metricId) {
        FinancialResearch research = researchRepository.findByTaskId(taskId).orElseThrow();
        FinancialMetric metric = research.getMetrics().stream().filter(m -> m.getId().equals(metricId)).findFirst().orElseThrow();
        
        validateMetricEditable(research, metric);

        metric.setQualityStatus(MetricQualityStatus.VALID);
        metric.setVerificationStatus(MetricVerificationStatus.VERIFIED);
        research = researchRepository.save(research);
        return toResponse(research);
    }

    public FinancialResearchResponse submitForReview(Long projectId, Long taskId, Long submitterId, List<String> selectedReportIds) {
        FinancialResearch research = researchRepository.findByTaskId(taskId)
                .orElseThrow(() -> new BusinessValidationException("Research not found"));
                
        if (research.getStatus() != FinancialResearchStatus.DRAFT && research.getStatus() != FinancialResearchStatus.CHANGES_REQUESTED) {
            throw new BusinessValidationException("Research is already submitted or approved.");
        }
        
        if (selectedReportIds == null || selectedReportIds.isEmpty()) {
            throw new BusinessValidationException("Cannot submit an empty research package. Please select at least one report.");
        }

        // Validate that selected reports exist and are eligible
        for (String reportId : selectedReportIds) {
            FinancialReportEntry report = research.getReports().stream()
                    .filter(r -> r.getId().equals(reportId))
                    .findFirst()
                    .orElseThrow(() -> new BusinessValidationException("Selected report not found in this research package: " + reportId));

            if (report.getReviewStatus() == FinancialReportReviewStatus.APPROVED) {
                throw new BusinessValidationException("Report '" + report.getTitle() + "' is already approved and cannot be resubmitted.");
            }

            if (research.getStatus() == FinancialResearchStatus.CHANGES_REQUESTED) {
                if (report.getReviewStatus() != FinancialReportReviewStatus.CHANGES_REQUESTED && report.getReviewStatus() != null && report.getReviewStatus() != FinancialReportReviewStatus.PENDING_REVIEW) {
                    throw new BusinessValidationException("Only eligible reports can be selected for resubmission. Invalid report: " + report.getTitle());
                }
            }
                    
            if (report.getExtractionStatus() != ExtractionStatus.EXTRACTED && report.getExtractionStatus() != ExtractionStatus.NEEDS_REVIEW) {
                throw new BusinessValidationException("Cannot submit report that has not been extracted: " + report.getTitle());
            }
            
            // Check for unverified metrics in THIS report
            boolean hasUnverified = research.getMetrics().stream()
                    .filter(m -> m.getSource() != null && reportId.equals(m.getSource().getReportEntryId()))
                    .anyMatch(m -> m.getQualityStatus() == MetricQualityStatus.NEEDS_REVIEW && m.getVerificationStatus() == MetricVerificationStatus.UNVERIFIED);
            if (hasUnverified) {
                throw new BusinessValidationException("Cannot submit report with unverified metrics that need review: " + report.getTitle());
            }

            // Set review status to PENDING_REVIEW for all selected reports
            report.setReviewStatus(FinancialReportReviewStatus.PENDING_REVIEW);
        }

        research.setSubmittedReportIds(new ArrayList<>(selectedReportIds));
        research.setStatus(FinancialResearchStatus.SUBMITTED);
        research.setSubmittedAt(LocalDateTime.now());
        research = researchRepository.save(research);
        
        return toResponse(research);
    }

    @Transactional
    public FinancialResearchResponse recallSubmission(Long projectId, Long taskId) {
        UserDetailsImpl currentUser = getCurrentUserDetails();
        Long currentUserId = currentUser != null ? currentUser.getId() : getCurrentUserId();

        com.apms.domain.project.ProjectTask task = projectTaskRepository.findWithProjectById(taskId)
                .orElseThrow(() -> new BusinessValidationException("Task not found"));

        if (!task.getProject().getId().equals(projectId)) {
            throw new BusinessValidationException("Task does not belong to the specified project");
        }

        // Authorization check: User must be assigned Staff or System Admin
        boolean isAdmin = currentUser != null && currentUser.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_SYSTEM_ADMIN") || a.getAuthority().equals("SYSTEM_ADMIN"));
        if (!isAdmin) {
            if (task.getAssignedToAccount() == null || !task.getAssignedToAccount().getId().equals(currentUserId)) {
                throw new AccessDeniedException("Only the assigned staff can recall this submission.");
            }
        }

        // Task must be IN_REVIEW
        if (task.getStatus() != com.apms.common.enums.TaskStatus.IN_REVIEW) {
            throw new BusinessValidationException("Task is not currently under review.");
        }

        FinancialResearch research = researchRepository.findByTaskId(taskId)
                .orElseThrow(() -> new BusinessValidationException("Research package not found"));

        // Check active submissions
        List<com.apms.domain.project.ProjectTaskSubmission> submissions = submissionRepository.findByProjectTask_Id(taskId);
        com.apms.domain.project.ProjectTaskSubmission activeSub = submissions.stream()
                .filter(s -> s.getStatus() == com.apms.common.enums.SubmissionStatus.IN_REVIEW)
                .findFirst()
                .orElse(null);

        // If task is not IN_REVIEW, research is not SUBMITTED, and no active submission -> truly nothing to recall
        if (task.getStatus() != com.apms.common.enums.TaskStatus.IN_REVIEW &&
            research.getStatus() != FinancialResearchStatus.SUBMITTED &&
            activeSub == null) {
            throw new BusinessValidationException("Task is not currently under review.");
        }

        List<String> submittedReportIds = new ArrayList<>();
        if (research.getSubmittedReportIds() != null && !research.getSubmittedReportIds().isEmpty()) {
            submittedReportIds.addAll(research.getSubmittedReportIds());
        } else if (activeSub != null && !activeSub.getTargetItemIdList().isEmpty()) {
            submittedReportIds.addAll(activeSub.getTargetItemIdList());
        } else if (research.getReports() != null) {
            research.getReports().stream()
                    .filter(r -> r.getReviewStatus() != FinancialReportReviewStatus.APPROVED)
                    .map(FinancialReportEntry::getId)
                    .forEach(submittedReportIds::add);
        }

        // ABSOLUTE RULE: Check if Manager has made ANY decision on any report in current submission
        for (String repId : submittedReportIds) {
            FinancialReportEntry report = research.getReports() != null
                    ? research.getReports().stream().filter(r -> r.getId().equals(repId)).findFirst().orElse(null)
                    : null;
            if (report != null) {
                if (report.getReviewStatus() == FinancialReportReviewStatus.APPROVED ||
                    report.getReviewStatus() == FinancialReportReviewStatus.CHANGES_REQUESTED) {
                    throw new BusinessValidationException("This submission can no longer be recalled because Manager review has already started.");
                }
            }
        }

        // 1. Mark active submission as WITHDRAWN (preserving history, not deleting)
        submissions.stream()
                .filter(s -> s.getStatus() == com.apms.common.enums.SubmissionStatus.IN_REVIEW)
                .forEach(s -> {
                    s.setStatus(com.apms.common.enums.SubmissionStatus.WITHDRAWN);
                    s.setNote(StringUtils.hasText(s.getNote()) ? s.getNote() + " [Recalled by Staff]" : "[Recalled by Staff]");
                    submissionRepository.save(s);
                });

        // 2. Restore reports in current submission to their correct editable state
        if (research.getReports() != null) {
            for (FinancialReportEntry report : research.getReports()) {
                if (submittedReportIds.contains(report.getId()) || report.getReviewStatus() == FinancialReportReviewStatus.PENDING_REVIEW) {
                    if (StringUtils.hasText(report.getReviewComment())) {
                        report.setReviewStatus(FinancialReportReviewStatus.CHANGES_REQUESTED);
                    } else {
                        report.setReviewStatus(null);
                    }
                }
            }
        }

        // 3. Update FinancialResearch state
        boolean hasAnyChangesRequested = research.getReports() != null && research.getReports().stream()
                .anyMatch(r -> r.getReviewStatus() == FinancialReportReviewStatus.CHANGES_REQUESTED);
        if (hasAnyChangesRequested) {
            research.setStatus(FinancialResearchStatus.CHANGES_REQUESTED);
        } else {
            research.setStatus(FinancialResearchStatus.DRAFT);
        }
        research.setSubmittedReportIds(new ArrayList<>());
        research = researchRepository.save(research);

        // 4. Update ProjectTask state back to IN_PROGRESS
        task.setStatus(com.apms.common.enums.TaskStatus.IN_PROGRESS);
        task.setCompletedAt(null);
        projectTaskRepository.save(task);

        // 5. Audit Log
        auditLogService.log(
                currentUserId,
                AuditAction.FINANCIAL_RESEARCH_SUBMISSION_RECALLED,
                "ProjectTask",
                String.valueOf(taskId),
                "Financial research submission recalled by staff"
        );

        return toResponse(research);
    }

    @Transactional(readOnly = true)
    public List<FinancialResearchResponse> getApprovedFinancials(String companyProfileId) {
        List<FinancialResearch> list = new ArrayList<>(researchRepository.findByCompanyProfileIdAndStatus(companyProfileId, FinancialResearchStatus.APPROVED));
        
        if (list.isEmpty()) {
            List<com.apms.domain.project.ProjectTask> tasks = new ArrayList<>();
            try {
                tasks.addAll(projectTaskRepository.findByTargetCompanyProfileId(companyProfileId));
                tasks.addAll(projectTaskRepository.findByProject_TargetCompanyProfileId(companyProfileId));
            } catch (Exception e) {
                log.warn("Failed to find tasks by target company profile id: {}", companyProfileId, e);
            }
            
            if (!tasks.isEmpty()) {
                for (com.apms.domain.project.ProjectTask pt : tasks) {
                    researchRepository.findByTaskId(pt.getId()).ifPresent(r -> {
                        if (r.getStatus() == FinancialResearchStatus.APPROVED) {
                            if (list.stream().noneMatch(existing -> existing.getId().equals(r.getId()))) {
                                list.add(r);
                            }
                        }
                    });
                }
            }
        }

        return list.stream().map(r -> {
            List<FinancialReportEntry> approvedReports = r.getReports() != null
                    ? r.getReports().stream()
                            .filter(rep -> rep.getReviewStatus() == FinancialReportReviewStatus.APPROVED)
                            .collect(Collectors.toList())
                    : new ArrayList<>();
            
            Set<String> approvedReportIds = approvedReports.stream().map(FinancialReportEntry::getId).collect(Collectors.toSet());
            Set<String> approvedDocIds = approvedReports.stream().map(FinancialReportEntry::getDocumentId).filter(Objects::nonNull).collect(Collectors.toSet());

            List<FinancialMetric> approvedMetrics = r.getMetrics() != null
                    ? r.getMetrics().stream()
                            .filter(m -> m.getSource() != null && 
                                    (approvedReportIds.contains(m.getSource().getReportEntryId()) || 
                                     approvedDocIds.contains(m.getSource().getDocumentId())))
                            .collect(Collectors.toList())
                    : new ArrayList<>();

            FinancialResearch clone = FinancialResearch.builder()
                    .id(r.getId())
                    .taskId(r.getTaskId())
                    .projectId(r.getProjectId())
                    .companyProfileId(r.getCompanyProfileId())
                    .targetResearchPeriod(r.getTargetResearchPeriod())
                    .reports(approvedReports)
                    .metrics(approvedMetrics)
                    .submittedReportIds(r.getSubmittedReportIds())
                    .status(r.getStatus())
                    .submittedAt(r.getSubmittedAt())
                    .reviewedBy(r.getReviewedBy())
                    .reviewedAt(r.getReviewedAt())
                    .reviewReason(r.getReviewReason())
                    .createdAt(r.getCreatedAt())
                    .updatedAt(r.getUpdatedAt())
                    .build();
            return toResponse(clone);
        }).collect(Collectors.toList());
    }

    @org.springframework.transaction.annotation.Transactional
    public FinancialResearchResponse reviewReport(Long projectId, Long taskId, String reportId, com.apms.domain.financial.dto.ReviewFinancialReportRequest request) {
        FinancialResearch research = researchRepository.findByTaskId(taskId)
                .orElseThrow(() -> new BusinessValidationException("Research not found"));

        if (research.getStatus() != FinancialResearchStatus.SUBMITTED) {
            throw new BusinessValidationException("Research is not in a reviewable state.");
        }

        List<com.apms.domain.project.ProjectTaskSubmission> activeSubs = submissionRepository.findByProjectTask_Id(taskId).stream()
                .filter(s -> s.getStatus() == com.apms.common.enums.SubmissionStatus.IN_REVIEW)
                .collect(Collectors.toList());
        if (activeSubs.isEmpty()) {
            throw new BusinessValidationException("This submission is no longer available for review.");
        }

        FinancialReportEntry report = research.getReports().stream()
                .filter(r -> r.getId().equals(reportId))
                .findFirst()
                .orElseThrow(() -> new BusinessValidationException("Report not found"));

        if (research.getSubmittedReportIds() == null || research.getSubmittedReportIds().isEmpty()) {
            List<String> autoIds = research.getReports() != null
                    ? research.getReports().stream().map(FinancialReportEntry::getId).collect(Collectors.toList())
                    : new ArrayList<>();
            research.setSubmittedReportIds(autoIds);
            research = researchRepository.save(research);
        } else if (!research.getSubmittedReportIds().contains(reportId)) {
            boolean existsInReports = research.getReports() != null && research.getReports().stream().anyMatch(r -> r.getId().equals(reportId));
            if (existsInReports) {
                research.getSubmittedReportIds().add(reportId);
                research = researchRepository.save(research);
            } else {
                throw new BusinessValidationException("Report is not included in the current submission package.");
            }
        }

        if (request.getStatus() == FinancialReportReviewStatus.APPROVED) {
            if (report.getExtractionStatus() != ExtractionStatus.EXTRACTED && report.getExtractionStatus() != ExtractionStatus.NEEDS_REVIEW) {
                throw new BusinessValidationException("Cannot approve report that has not been extracted.");
            }
            boolean hasUnverified = research.getMetrics().stream()
                    .filter(m -> m.getSource() != null && reportId.equals(m.getSource().getReportEntryId()))
                    .anyMatch(m -> m.getQualityStatus() == MetricQualityStatus.NEEDS_REVIEW && m.getVerificationStatus() == MetricVerificationStatus.UNVERIFIED);
            if (hasUnverified) {
                throw new BusinessValidationException("Cannot approve report with unverified metrics that need review.");
            }
        } else if (request.getStatus() == FinancialReportReviewStatus.CHANGES_REQUESTED) {
            if (!org.springframework.util.StringUtils.hasText(request.getReason())) {
                throw new BusinessValidationException("Reason is required when requesting changes.");
            }
        }

        Long reviewerId = getCurrentUserId();
        String reviewerName = userProfileRepository.findByAccountId(reviewerId)
                .map(p -> (p.getFirstName() != null ? p.getFirstName() + " " : "") + (p.getLastName() != null ? p.getLastName() : ""))
                .map(String::trim)
                .filter(org.springframework.util.StringUtils::hasText)
                .orElse("Manager");

        report.setReviewStatus(request.getStatus());
        report.setReviewedBy(reviewerId);
        report.setReviewedByName(reviewerName);
        report.setReviewedAt(LocalDateTime.now());
        report.setReviewComment(request.getReason());

        boolean currentSubmissionReviewed = true;

        for (String subReportId : research.getSubmittedReportIds()) {
            FinancialReportEntry subReport = research.getReports().stream().filter(r -> r.getId().equals(subReportId)).findFirst().orElse(null);
            if (subReport == null || subReport.getReviewStatus() == null || subReport.getReviewStatus() == FinancialReportReviewStatus.PENDING_REVIEW) {
                currentSubmissionReviewed = false;
                break;
            }
        }

        if (currentSubmissionReviewed) {
            com.apms.domain.project.ProjectTask task = projectTaskRepository.findById(taskId).orElseThrow();
            
            boolean anyChangesRequested = research.getReports().stream()
                    .anyMatch(r -> r.getReviewStatus() == FinancialReportReviewStatus.CHANGES_REQUESTED);
            
            boolean anyPending = research.getReports().stream()
                    .anyMatch(r -> r.getReviewStatus() == FinancialReportReviewStatus.PENDING_REVIEW);
            
            boolean anyApproved = research.getReports().stream()
                    .anyMatch(r -> r.getReviewStatus() == FinancialReportReviewStatus.APPROVED);

            if (anyChangesRequested) {
                research.setStatus(FinancialResearchStatus.CHANGES_REQUESTED);
                task.setStatus(com.apms.common.enums.TaskStatus.IN_PROGRESS);
                task.setCompletedAt(null);
                submissionRepository.findByProjectTask_Id(taskId).stream()
                        .filter(s -> s.getStatus() == com.apms.common.enums.SubmissionStatus.IN_REVIEW)
                        .forEach(s -> {
                            s.setStatus(com.apms.common.enums.SubmissionStatus.CHANGES_REQUESTED);
                            submissionRepository.save(s);
                        });
            } else if (!anyPending && anyApproved) {
                research.setStatus(FinancialResearchStatus.APPROVED);
                String targetProfileId = task.getTargetCompanyProfileId() != null
                        ? task.getTargetCompanyProfileId()
                        : (task.getProject() != null ? task.getProject().getTargetCompanyProfileId() : null);
                if (targetProfileId != null) {
                    research.setCompanyProfileId(targetProfileId);
                }
                task.setStatus(com.apms.common.enums.TaskStatus.DONE);
                submissionRepository.findByProjectTask_Id(taskId).stream()
                        .filter(s -> s.getStatus() == com.apms.common.enums.SubmissionStatus.IN_REVIEW)
                        .forEach(s -> {
                            s.setStatus(com.apms.common.enums.SubmissionStatus.APPROVED);
                            submissionRepository.save(s);
                        });
            }
            projectTaskRepository.save(task);
        } else {
            research.setStatus(FinancialResearchStatus.SUBMITTED);
        }

        research = researchRepository.save(research);
        String action = request.getStatus() == FinancialReportReviewStatus.APPROVED ? "Approved" : "Requested changes for";
        auditLogService.log(reviewerId, AuditAction.FINANCIAL_RESEARCH_CHANGES_REQUESTED, "ProjectTask", taskId.toString(), action + " report " + report.getTitle());
        return toResponse(research);
    }

    @Transactional(readOnly = true)
    public String fetchTargetCompanyName(Long taskId) {
        try {
            return projectTaskRepository.findWithProjectById(taskId)
                    .map(t -> (t.getProject() != null && t.getProject().getTargetCompanyName() != null)
                            ? t.getProject().getTargetCompanyName()
                            : "Unknown Company")
                    .orElse("Unknown Company");
        } catch (Exception e) {
            return "Unknown Company";
        }
    }

    // --- Private mapping logic ---

    private DocumentContext mapToDocumentContext(AiFinancialDocumentContextCandidate candidate, RawDocument doc, String targetCompanyName, ReportingPeriod targetPeriod) {
        if (candidate == null) {
            candidate = new AiFinancialDocumentContextCandidate();
        }
        DocumentCompanyValidationStatus companyValidation = DocumentCompanyValidationStatus.UNKNOWN;
        if (targetCompanyName != null && candidate.getCompanyName() != null) {
            if (candidate.getCompanyName().equalsIgnoreCase(targetCompanyName)) {
                companyValidation = DocumentCompanyValidationStatus.MATCH;
            } else if (candidate.getCompanyName().toLowerCase().contains(targetCompanyName.toLowerCase()) ||
                       targetCompanyName.toLowerCase().contains(candidate.getCompanyName().toLowerCase())) {
                companyValidation = DocumentCompanyValidationStatus.POSSIBLE_MATCH;
            } else {
                companyValidation = DocumentCompanyValidationStatus.MISMATCH;
            }
        }
        
        DocumentPeriodValidationStatus periodValidation = DocumentPeriodValidationStatus.UNKNOWN;
        if (targetPeriod != null && candidate.getYear() != null && candidate.getPeriodType() != null) {
            if (candidate.getYear().equals(targetPeriod.getYear()) && candidate.getPeriodType() == targetPeriod.getPeriodType()) {
                periodValidation = DocumentPeriodValidationStatus.MATCH;
            } else {
                periodValidation = DocumentPeriodValidationStatus.MISMATCH;
            }
        }

        return DocumentContext.builder()
                .documentId(doc.getId())
                .documentName(doc.getSource() != null ? doc.getSource().getFileName() : "Unknown")
                .companyName(candidate.getCompanyName())
                .reportType(candidate.getReportType())
                .year(candidate.getYear())
                .periodType(candidate.getPeriodType())
                .period(candidate.getPeriod())
                .asOfDate(candidate.getAsOfDate())
                .currency(candidate.getCurrency())
                .scale(candidate.getScale())
                .statementScope(candidate.getStatementScope())
                .industryContext(candidate.getIndustryContext())
                .companyValidation(companyValidation)
                .periodValidation(periodValidation)
                .build();
    }

    private FinancialMetric mapToMetric(AiFinancialMetricCandidate candidate, RawDocument doc, String reportEntryId) {
        NormalizedValue normalized = normalizeValue(candidate.getRawValue(), candidate.getRawUnit());
        
        MetricQualityStatus quality = MetricQualityStatus.VALID;
        if (candidate.getConfidence() == null || candidate.getConfidence() < 0.7) {
            quality = MetricQualityStatus.NEEDS_REVIEW;
        }
        if (normalized.unit == null || normalized.unit.equals(candidate.getRawUnit()) && !isStandardUnit(normalized.unit)) {
            quality = MetricQualityStatus.NEEDS_REVIEW;
        }

        return FinancialMetric.builder()
                .id(UUID.randomUUID().toString())
                .label(candidate.getLabel())
                .normalizedKey(generateNormalizedKey(candidate.getLabel()))
                .rawValue(candidate.getRawValue())
                .rawUnit(candidate.getRawUnit())
                .normalizedValue(normalized.value)
                .normalizedUnit(normalized.unit)
                .inputMethod(MetricInputMethod.AI_EXTRACTED)
                .period(candidate.getPeriod())
                .source(MetricSource.builder()
                        .reportEntryId(reportEntryId)
                        .documentId(doc.getId())
                        .documentName(doc.getSource() != null ? doc.getSource().getFileName() : "Unknown")
                        .page(candidate.getSourcePage())
                        .build())
                .evidence(candidate.getEvidence())
                .confidence(candidate.getConfidence())
                .qualityStatus(quality)
                .verificationStatus(MetricVerificationStatus.UNVERIFIED)
                .build();
    }

    private String generateNormalizedKey(String label) {
        if (!StringUtils.hasText(label)) return "";
        String normalized = Normalizer.normalize(label, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        return normalized.toLowerCase()
                .replaceAll("[^a-z0-9]", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
    }

    private static class NormalizedValue {
        BigDecimal value;
        String unit;
        NormalizedValue(BigDecimal v, String u) { this.value = v; this.unit = u; }
    }

    private boolean isStandardUnit(String unit) {
        if (unit == null) return false;
        String upper = unit.toUpperCase();
        return upper.equals("VND") || upper.equals("USD") || upper.equals("PERCENT") || upper.equals("TIMES") || upper.equals("RATIO");
    }

    private NormalizedValue normalizeValue(String rawValue, String rawUnit) {
        if (!StringUtils.hasText(rawValue)) {
            return new NormalizedValue(null, rawUnit);
        }

        try {
            String cleanVal = rawValue.replaceAll("[^0-9.-]", "");
            BigDecimal bd = new BigDecimal(cleanVal);
            
            if (!StringUtils.hasText(rawUnit)) {
                return new NormalizedValue(bd, null);
            }

            String unitUpper = rawUnit.toUpperCase();
            if (unitUpper.contains("MILLION_VND") || unitUpper.contains("TRI\u1EC7U \u0110\u1ED3NG")) {
                return new NormalizedValue(bd.multiply(new BigDecimal("1000000")), "VND");
            } else if (unitUpper.contains("BILLION_VND") || unitUpper.contains("T\u1EF7 \u0110\u1ED3NG")) {
                return new NormalizedValue(bd.multiply(new BigDecimal("1000000000")), "VND");
            } else if (unitUpper.contains("THOUSAND_VND") || unitUpper.contains("NGH\u00CCN \u0110\u1ED3NG")) {
                return new NormalizedValue(bd.multiply(new BigDecimal("1000")), "VND");
            } else if (unitUpper.contains("MILLION_USD")) {
                return new NormalizedValue(bd.multiply(new BigDecimal("1000000")), "USD");
            } else if (unitUpper.contains("PERCENT") || unitUpper.contains("%")) {
                return new NormalizedValue(bd, "PERCENT");
            } else if (unitUpper.contains("TIMES") || unitUpper.contains("L\u1EA6N")) {
                return new NormalizedValue(bd, "TIMES");
            } else if (unitUpper.contains("RATIO") || unitUpper.contains("T\u1EF6 L\u1EC6")) {
                return new NormalizedValue(bd, "RATIO");
            }

            return new NormalizedValue(bd, rawUnit);
        } catch (Exception e) {
            log.warn("Failed to parse value: {}", rawValue);
            return new NormalizedValue(null, rawUnit);
        }
    }

    private FinancialResearchResponse toResponse(FinancialResearch domain) {
        List<FinancialMetricResponse> metricResponses = domain.getMetrics() != null ? domain.getMetrics().stream().map(m -> FinancialMetricResponse.builder()
                .id(m.getId())
                .label(m.getLabel())
                .normalizedKey(m.getNormalizedKey())
                .rawValue(m.getRawValue())
                .rawUnit(m.getRawUnit())
                .normalizedValue(m.getNormalizedValue() != null ? m.getNormalizedValue().toString() : null)
                .normalizedUnit(m.getNormalizedUnit())
                .inputMethod(m.getInputMethod())
                .period(m.getPeriod())
                .source(m.getSource())
                .evidence(m.getEvidence())
                .confidence(m.getConfidence())
                .qualityStatus(m.getQualityStatus())
                .verificationStatus(m.getVerificationStatus())
                .build()).collect(Collectors.toList()) : new ArrayList<>();

        List<FinancialReportEntry> filteredReports = domain.getReports() != null ? new ArrayList<>(domain.getReports()) : new ArrayList<>();
        // Removed backend filtering of non-submitted reports.
        // The frontend already filters based on submittedReportIds to split CURRENT SUBMISSION and PREVIOUSLY APPROVED.

        boolean canRecall = false;
        Long activeSubmissionId = null;
        if (domain.getStatus() == FinancialResearchStatus.SUBMITTED) {
            List<String> subReportIds = domain.getSubmittedReportIds();
            boolean anyDecisionMade = false;
            if (domain.getReports() != null) {
                anyDecisionMade = domain.getReports().stream()
                        .filter(r -> subReportIds != null && !subReportIds.isEmpty() ? subReportIds.contains(r.getId()) : true)
                        .anyMatch(r -> r.getReviewStatus() == FinancialReportReviewStatus.APPROVED ||
                                       r.getReviewStatus() == FinancialReportReviewStatus.CHANGES_REQUESTED);
            }
            canRecall = !anyDecisionMade;
            
            try {
                activeSubmissionId = submissionRepository.findByProjectTask_Id(domain.getTaskId()).stream()
                        .filter(s -> s.getStatus() == com.apms.common.enums.SubmissionStatus.IN_REVIEW)
                        .map(com.apms.domain.project.ProjectTaskSubmission::getId)
                        .findFirst()
                        .orElse(null);
            } catch (Exception e) {
                log.warn("Could not find active submission id for task: {}", domain.getTaskId(), e);
            }
        }

        return FinancialResearchResponse.builder()
                .id(domain.getId())
                .taskId(domain.getTaskId())
                .projectId(domain.getProjectId())
                .companyProfileId(domain.getCompanyProfileId())
                .targetResearchPeriod(domain.getTargetResearchPeriod())
                .reports(filteredReports)
                .metrics(metricResponses)
                .submittedReportIds(domain.getSubmittedReportIds() != null ? domain.getSubmittedReportIds() : new ArrayList<>())
                .status(domain.getStatus())
                .submittedAt(domain.getSubmittedAt())
                .reviewedBy(domain.getReviewedBy())
                .reviewedAt(domain.getReviewedAt())
                .reviewReason(domain.getReviewReason())
                .canRecallSubmission(canRecall)
                .activeSubmissionId(activeSubmissionId)
                .createdAt(domain.getCreatedAt())
                .updatedAt(domain.getUpdatedAt())
                .build();
    }

    private UserDetailsImpl getCurrentUserDetails() {
        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
            if (principal instanceof UserDetailsImpl) {
                return (UserDetailsImpl) principal;
            }
        }
        return null;
    }

    private Long getCurrentUserId() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (principal instanceof UserDetails) {
            try {
                java.lang.reflect.Method getIdMethod = principal.getClass().getMethod("getId");
                return (Long) getIdMethod.invoke(principal);
            } catch (Exception e) {
                log.warn("Could not extract user id", e);
            }
        }
        return 1L; // fallback
    }
}
