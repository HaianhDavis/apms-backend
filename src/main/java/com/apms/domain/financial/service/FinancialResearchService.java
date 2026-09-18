package com.apms.domain.financial.service;

import com.apms.common.enums.AuditAction;
import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.document.RawDocument;
import com.apms.domain.document.repository.mongo.RawDocumentRepository;
import com.apms.domain.financial.*;
import com.apms.domain.financial.dto.*;
import com.apms.domain.financial.repository.FinancialResearchRepository;
import com.apms.domain.project.repository.sql.ProjectRepository;
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
import org.springframework.web.multipart.MultipartFile;
import com.apms.domain.document.service.DocumentService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.dto.CompanyIdentity;
import com.apms.domain.profile.service.CompanyIdentityResolver;
import com.apms.domain.profile.service.CompanyProfileAccessService;
import com.apms.common.security.StaffCompanyScopeEvaluator;

@Slf4j
@Service
@RequiredArgsConstructor
public class FinancialResearchService {

    public static final Pattern FINANCIAL_NUMBER_PATTERN = Pattern.compile("^-?(?:\\d+|\\d{1,3}(?:,\\d{3})+)(?:\\.\\d+)?$");

    private final FinancialResearchRepository researchRepository;
    private final RawDocumentRepository documentRepository;
    private final DocumentService documentService;
    private final ProjectTaskRepository projectTaskRepository;
    private final ProjectRepository projectRepository;
    private final DocumentCompanyMatcher companyMatcher;
    private final com.apms.domain.project.repository.sql.ProjectTaskSubmissionRepository submissionRepository;
    private final FinancialExtractionService extractionService;
    private final AuditLogService auditLogService;
    private final com.apms.domain.user.repository.sql.UserProfileRepository userProfileRepository;
    private final com.apms.domain.user.repository.sql.AccountRepository accountRepository;
    private final CompanyIdentityResolver companyIdentityResolver;
    private final CompanyProfileAccessService companyProfileAccessService;
    private final StaffCompanyScopeEvaluator companyScope;

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
            Integer targetYear = resolveTaskTargetYear(taskId, null);
            ReportingPeriod targetPeriod = targetYear != null
                    ? ReportingPeriod.builder()
                            .year(targetYear)
                            .period("FY")
                            .periodType(ReportingPeriodType.FULL_YEAR)
                            .build()
                    : null;

            research = FinancialResearch.builder()
                    .taskId(taskId)
                    .projectId(projectId)
                    .companyProfileId(targetProfileId)
                    .targetResearchPeriod(targetPeriod)
                    .status(FinancialResearchStatus.DRAFT)
                    .reports(new ArrayList<>())
                    .metrics(new ArrayList<>())
                    .build();
            research = researchRepository.save(research);
        } else {
            if (research.getTargetResearchPeriod() == null || research.getTargetResearchPeriod().getYear() == null) {
                Integer targetYear = resolveTaskTargetYear(taskId, research);
                if (targetYear != null) {
                    research.setTargetResearchPeriod(ReportingPeriod.builder()
                            .year(targetYear)
                            .period("FY")
                            .periodType(ReportingPeriodType.FULL_YEAR)
                            .build());
                    research = researchRepository.save(research);
                }
            }
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
                        com.apms.domain.project.ProjectTaskSubmission sub = activeSubs.get(0);
                        if (sub.getTargetItemIdList() != null && !sub.getTargetItemIdList().isEmpty()) {
                            research.setSubmittedReportIds(new ArrayList<>(sub.getTargetItemIdList()));
                        } else if (research.getSubmittedReportIds() == null || research.getSubmittedReportIds().isEmpty()) {
                            if (research.getReports() != null) {
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
            healOrphanManualMetrics(research);
        }
        return Optional.of(toResponse(research));
    }

    private boolean requiresSourceDocument(FinancialDataEntryMethod method) {
        return method == FinancialDataEntryMethod.AI_EXTRACTION;
    }

    public FinancialResearchResponse addReport(Long projectId, Long taskId, CreateFinancialReportRequest request) {
        FinancialResearch research = researchRepository.findByTaskId(taskId)
                .orElseThrow(() -> new BusinessValidationException("Research not found"));
        
        if (research.getStatus() != FinancialResearchStatus.DRAFT && research.getStatus() != FinancialResearchStatus.CHANGES_REQUESTED) {
            throw new BusinessValidationException("Cannot add report to submitted or approved research");
        }

        FinancialDataEntryMethod method = request.getDataEntryMethod() != null
                ? request.getDataEntryMethod()
                : FinancialDataEntryMethod.AI_EXTRACTION;

        if (requiresSourceDocument(method)) {
            if (request.getDocumentId() == null || request.getDocumentId().isBlank()) {
                throw new BusinessValidationException("AI Extraction requires a source document (PDF).");
            }
        }

        Integer year = request.getReportingPeriod() != null ? request.getReportingPeriod().getYear() : null;
        if (year == null && research.getTargetResearchPeriod() != null) {
            year = research.getTargetResearchPeriod().getYear();
        }
        if (year == null) {
            year = resolveTaskTargetYear(taskId, research);
            if (year != null && (research.getTargetResearchPeriod() == null || research.getTargetResearchPeriod().getYear() == null)) {
                research.setTargetResearchPeriod(ReportingPeriod.builder()
                        .year(year)
                        .period("FY")
                        .periodType(ReportingPeriodType.FULL_YEAR)
                        .build());
                research = researchRepository.save(research);
            }
        }
        if (year == null) {
            throw new BusinessValidationException("Target research period year is not configured for this task.");
        }

        ReportingPeriod reportingPeriod = request.getReportingPeriod();
        if (reportingPeriod == null) {
            reportingPeriod = ReportingPeriod.builder()
                    .year(year)
                    .period("Q1")
                    .periodType(ReportingPeriodType.QUARTER)
                    .build();
        } else {
            reportingPeriod.setYear(year);
        }

        String docName = null;
        if (request.getDocumentId() != null && !request.getDocumentId().isBlank()) {
            try {
                RawDocument doc = documentRepository.findById(request.getDocumentId()).orElse(null);
                if (doc != null && doc.getSource() != null) {
                    docName = doc.getSource().getFileName();
                }
            } catch (Exception ignored) {}
        }

        StatementScope statementScope = request.getStatementScope() != null
                ? request.getStatementScope()
                : StatementScope.UNKNOWN;

        ReportType reportType = request.getReportType() != null
                ? request.getReportType()
                : ReportType.FINANCIAL_STATEMENT;

        FinancialReportEntry entry = FinancialReportEntry.builder()
                .id(UUID.randomUUID().toString())
                .documentId(request.getDocumentId())
                .fileName(docName)
                .title(request.getTitle() != null ? request.getTitle().trim() : "")
                .dataEntryMethod(method)
                .publicationDate(request.getPublicationDate())
                .reportingPeriod(reportingPeriod)
                .reportType(reportType)
                .statementScope(statementScope)
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

    public FinancialResearchResponse updateReport(Long projectId, Long taskId, String reportId, UpdateFinancialReportRequest request) {
        FinancialResearch research = researchRepository.findByTaskId(taskId)
                .orElseThrow(() -> new BusinessValidationException("Research not found"));

        if (research.getStatus() != FinancialResearchStatus.DRAFT && research.getStatus() != FinancialResearchStatus.CHANGES_REQUESTED) {
            throw new BusinessValidationException("Cannot update report in submitted or approved research");
        }

        if (research.getReports() == null) {
            throw new BusinessValidationException("Report not found");
        }

        FinancialReportEntry report = research.getReports().stream()
                .filter(r -> r.getId().equals(reportId))
                .findFirst()
                .orElseThrow(() -> new BusinessValidationException("Report not found"));

        if (report.getReviewStatus() == FinancialReportReviewStatus.APPROVED) {
            throw new BusinessValidationException("Cannot edit an approved report");
        }

        if (report.getExtractionStatus() == ExtractionStatus.EXTRACTING) {
            throw new BusinessValidationException("Cannot edit report while AI extraction is in progress");
        }

        boolean anyExtracting = research.getReports().stream()
                .anyMatch(r -> r.getExtractionStatus() == ExtractionStatus.EXTRACTING);
        if (anyExtracting) {
            throw new BusinessValidationException("Một tài liệu khác đang được AI trích xuất. Vui lòng đợi hoàn tất trước khi thao tác tiếp.");
        }

        if (request.getTitle() != null && !request.getTitle().isBlank()) {
            report.setTitle(request.getTitle().trim());
        }
        if (request.getPublicationDate() != null) {
            report.setPublicationDate(request.getPublicationDate());
        }
        if (request.getReportType() != null) {
            report.setReportType(request.getReportType());
        }
        if (request.getStatementScope() != null) {
            report.setStatementScope(request.getStatementScope());
        }
        if (request.getReportingPeriod() != null) {
            report.setReportingPeriod(request.getReportingPeriod());
            // Sync period to metrics belonging to this report
            if (research.getMetrics() != null && request.getReportingPeriod().getPeriod() != null) {
                for (FinancialMetric m : research.getMetrics()) {
                    if (m.getSource() != null && reportId.equals(m.getSource().getReportEntryId())) {
                        m.setPeriod(request.getReportingPeriod());
                    }
                }
            }
        }

        report.setUpdatedAt(LocalDateTime.now());
        research.setUpdatedAt(LocalDateTime.now());
        research = researchRepository.save(research);

        auditLogService.log(getCurrentUserId(), AuditAction.FINANCIAL_RESEARCH_CREATED, "ProjectTask", taskId.toString(), "Updated financial report: " + report.getTitle());

        return toResponse(research);
    }

    @Transactional
    public FinancialResearchResponse replaceReportFile(Long projectId, Long taskId, String reportId, MultipartFile file, Long userId) {
        FinancialResearch research = researchRepository.findByTaskId(taskId)
                .orElseThrow(() -> new BusinessValidationException("Research not found"));

        if (research.getStatus() != FinancialResearchStatus.DRAFT && research.getStatus() != FinancialResearchStatus.CHANGES_REQUESTED) {
            throw new BusinessValidationException("Cannot replace document in submitted or approved research");
        }

        if (research.getReports() == null) {
            throw new BusinessValidationException("Report not found");
        }

        FinancialReportEntry report = research.getReports().stream()
                .filter(r -> r.getId().equals(reportId))
                .findFirst()
                .orElseThrow(() -> new BusinessValidationException("Report not found"));

        if (report.getReviewStatus() == FinancialReportReviewStatus.APPROVED) {
            throw new BusinessValidationException("Cannot replace document for an approved report");
        }

        if (report.getExtractionStatus() == ExtractionStatus.EXTRACTING) {
            throw new BusinessValidationException("Cannot replace document while AI extraction is in progress");
        }

        boolean anyExtracting = research.getReports().stream()
                .anyMatch(r -> r.getExtractionStatus() == ExtractionStatus.EXTRACTING);
        if (anyExtracting) {
            throw new BusinessValidationException("Một tài liệu khác đang được AI trích xuất. Vui lòng đợi hoàn tất trước khi thao tác tiếp.");
        }

        if (file == null || file.isEmpty()) {
            throw new BusinessValidationException("Uploaded file cannot be empty");
        }
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !originalFilename.toLowerCase().endsWith(".pdf")) {
            throw new BusinessValidationException("Only PDF documents are supported for financial reports");
        }

        Long effectiveUserId = userId != null ? userId : getCurrentUserId();
        String oldDocumentId = report.getDocumentId();

        // Upload and store new document via DocumentService
        com.apms.domain.document.dto.ImportJobResponse importJob = documentService.uploadDocument(projectId, taskId, file, effectiveUserId);
        String newDocumentId = importJob.getRawDocumentId();
        if (newDocumentId == null) {
            throw new BusinessValidationException("Failed to obtain document ID for uploaded file");
        }

        // Update report source document reference
        report.setDocumentId(newDocumentId);
        report.setFileName(originalFilename);

        if (report.getDataEntryMethod() == FinancialDataEntryMethod.MANUAL) {
            // Manual report: document is for reference only; preserve manual metrics and extraction status
            if (research.getMetrics() != null) {
                for (FinancialMetric m : research.getMetrics()) {
                    if (m.getSource() != null && reportId.equals(m.getSource().getReportEntryId())) {
                        m.getSource().setDocumentId(newDocumentId);
                        m.getSource().setDocumentName(originalFilename);
                    }
                }
            }
        } else {
            // Reset extraction-derived data for THIS REPORT ONLY
            report.setExtractionStatus(ExtractionStatus.NOT_EXTRACTED);
            report.setExtractionStage(null);
            report.setExtractionProgress(0);
            report.setExtractionStartedAt(null);
            report.setExtractionCompletedAt(null);
            report.setExtractionErrorCode(null);
            report.setExtractionErrorMessage(null);
            report.setDocumentContext(null);

            // Invalidate / remove metrics associated with this report
            if (research.getMetrics() != null) {
                research.getMetrics().removeIf(m -> m.getSource() != null &&
                        (reportId.equals(m.getSource().getReportEntryId()) ||
                         (oldDocumentId != null && oldDocumentId.equals(m.getSource().getDocumentId()))));
            }
        }

        // Soft-delete old document if not referenced by another report
        if (oldDocumentId != null) {
            boolean isDocShared = research.getReports().stream()
                    .anyMatch(r -> !r.getId().equals(reportId) && oldDocumentId.equals(r.getDocumentId()));
            if (!isDocShared) {
                try {
                    documentService.deleteDocument(projectId, taskId, oldDocumentId, effectiveUserId);
                } catch (Exception e) {
                    log.warn("Could not soft-delete old raw document {}: {}", oldDocumentId, e.getMessage());
                }
            }
        }

        if (report.getReviewStatus() == FinancialReportReviewStatus.CHANGES_REQUESTED) {
            report.setReviewStatus(null);
            report.setReviewComment(null);
            report.setReviewedBy(null);
            report.setReviewedByName(null);
            report.setReviewedAt(null);
        }

        research = researchRepository.save(research);

        auditLogService.log(effectiveUserId, AuditAction.FINANCIAL_RESEARCH_CREATED, "ProjectTask", taskId.toString(),
                "Replaced financial report document for " + report.getTitle() + " with " + originalFilename);

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

        if (report.getDataEntryMethod() == FinancialDataEntryMethod.MANUAL) {
            throw new BusinessValidationException("Cannot run AI extraction on a Manual Entry report.");
        }

        if (report.getDocumentId() == null || report.getDocumentId().isBlank()) {
            throw new BusinessValidationException("DOCUMENT_REQUIRED", "AI extraction requires a source document (PDF).");
        }

        // Prevent duplicate extraction
        if (report.getExtractionStatus() == ExtractionStatus.EXTRACTING) {
            // Already running — return current state without starting another
            return toResponse(research);
        }

        // Prevent concurrent extraction across documents
        boolean anyExtracting = research.getReports() != null && research.getReports().stream()
                .anyMatch(r -> r.getExtractionStatus() == ExtractionStatus.EXTRACTING);
        if (anyExtracting) {
            throw new BusinessValidationException("Một tài liệu khác đang được AI trích xuất. Vui lòng đợi hoàn tất trước khi thao tác tiếp.");
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

        if (report.getDataEntryMethod() == FinancialDataEntryMethod.MANUAL) {
            throw new BusinessValidationException("Cannot run AI extraction on a Manual Entry report.");
        }

        if (report.getDocumentId() == null || report.getDocumentId().isBlank()) {
            throw new BusinessValidationException("DOCUMENT_REQUIRED", "AI extraction requires a source document (PDF).");
        }

        // Prevent duplicate extraction
        if (report.getExtractionStatus() == ExtractionStatus.EXTRACTING) {
            return toResponse(research);
        }

        // Prevent concurrent extraction across documents
        boolean anyOtherExtracting = research.getReports() != null && research.getReports().stream()
                .anyMatch(r -> !r.getId().equals(reportId) && r.getExtractionStatus() == ExtractionStatus.EXTRACTING);
        if (anyOtherExtracting) {
            throw new BusinessValidationException("Một tài liệu khác đang được AI trích xuất. Vui lòng đợi hoàn tất trước khi thao tác tiếp.");
        }
                
        if (research.getMetrics() == null) {
            research.setMetrics(new ArrayList<>());
        }
        
        // Remove ALL previous AI-extracted metrics for this report (whether verified or unverified)
        // so that the re-extraction starts completely fresh without duplicating existing metrics
        research.getMetrics().removeIf(m -> 
                m.getInputMethod() == MetricInputMethod.AI_EXTRACTED &&
                ((m.getSource() != null && reportId.equals(m.getSource().getReportEntryId())) ||
                 (m.getSource() != null && report.getDocumentId() != null && report.getDocumentId().equals(m.getSource().getDocumentId()))));
                
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
     * Cancel in-progress AI extraction for a report.
     */
    public FinancialResearchResponse cancelExtraction(Long projectId, Long taskId, String reportId) {
        FinancialResearch research = researchRepository.findByTaskId(taskId)
                .orElseThrow(() -> new BusinessValidationException("Research not found"));

        FinancialReportEntry report = research.getReports().stream()
                .filter(r -> r.getId().equals(reportId)).findFirst()
                .orElseThrow(() -> new BusinessValidationException("Report not found"));

        if (research.getStatus() != FinancialResearchStatus.DRAFT && research.getStatus() != FinancialResearchStatus.CHANGES_REQUESTED) {
            throw new BusinessValidationException("Cannot cancel extraction on submitted or approved research");
        }

        if (report.getExtractionStatus() == ExtractionStatus.EXTRACTING) {
            boolean hasMetrics = research.getMetrics() != null && research.getMetrics().stream()
                    .anyMatch(m -> m.getSource() != null && reportId.equals(m.getSource().getReportEntryId()));

            if (hasMetrics) {
                report.setExtractionStatus(ExtractionStatus.EXTRACTED);
            } else {
                report.setExtractionStatus(ExtractionStatus.NOT_EXTRACTED);
            }
            report.setExtractionStage(null);
            report.setExtractionProgress(0);
            report.setExtractionErrorCode(null);
            report.setExtractionErrorMessage(null);
            report.setUpdatedAt(LocalDateTime.now());

            research = researchRepository.save(research);
            log.info("Cancelled AI extraction for task {} report {}", taskId, reportId);
            auditLogService.log(getCurrentUserId(), AuditAction.FINANCIAL_AI_EXTRACTION_RUN, "ProjectTask", taskId.toString(), "Cancelled AI extraction for report " + reportId);
        }

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

            FinancialDocumentExtractionResult docResult = extractionService.callAiExtraction(doc, report.getReportingPeriod());
            if (isExtractionCancelled(taskId, reportId)) {
                log.info("Extraction was cancelled for task {} report {}, aborting.", taskId, reportId);
                return;
            }
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

                ReportingPeriod repPeriod = report.getReportingPeriod();
                boolean isTargetQuarter = repPeriod != null && (repPeriod.getPeriodType() == ReportingPeriodType.QUARTER
                        || (repPeriod.getPeriod() != null && repPeriod.getPeriod().toUpperCase().startsWith("Q")));

                for (AiFinancialMetricCandidate candidate : docResult.getMetricCandidates()) {
                    if (isTargetQuarter) {
                        boolean isIncome = isIncomeStatementMetric(candidate.getLabel(), candidate.getStatementType());
                        if (isIncome && isCumulativeColumn(candidate.getSourceColumn())) {
                            log.warn("Skipped metric {} because source column '{}' is cumulative/YTD and does not match target quarterly period {}",
                                    candidate.getLabel(), candidate.getSourceColumn(), repPeriod.getPeriod());
                            continue;
                        }
                    }
                    FinancialMetric metric = mapToMetric(candidate, doc, report);
                    if (!existingKeys.contains(metric.getNormalizedKey())) {
                        newMetrics.add(metric);
                        existingKeys.add(metric.getNormalizedKey());
                    }
                }
            }

            // --- STAGE: SAVING_RESULTS ---
            if (isExtractionCancelled(taskId, reportId)) {
                log.info("Extraction was cancelled for task {} report {}, skipping save.", taskId, reportId);
                return;
            }
            updateExtractionProgress(taskId, reportId, FinancialExtractionStage.SAVING_RESULTS, PROGRESS_SAVING);

            // Ensure no duplicate AI metrics remain for this report before adding fresh ones
            final String currentDocId = doc.getId();
            research.getMetrics().removeIf(m -> 
                    m.getInputMethod() == MetricInputMethod.AI_EXTRACTED &&
                    m.getSource() != null &&
                    (reportId.equals(m.getSource().getReportEntryId()) ||
                     (currentDocId != null && currentDocId.equals(m.getSource().getDocumentId()))));

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

            boolean shouldSave = research.getReports().stream()
                    .filter(r -> r.getId().equals(reportId))
                    .findFirst()
                    .map(report -> {
                        if (report.getExtractionStatus() != ExtractionStatus.EXTRACTING) {
                            return false;
                        }
                        report.setExtractionStage(stage);
                        report.setExtractionProgress(progress);
                        report.setUpdatedAt(LocalDateTime.now());
                        return true;
                    }).orElse(false);

            if (shouldSave) {
                researchRepository.save(research);
                log.debug("Extraction progress updated: task={} report={} stage={} progress={}%", taskId, reportId, stage, progress);
            }
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

            boolean shouldSave = research.getReports().stream()
                    .filter(r -> r.getId().equals(reportId))
                    .findFirst()
                    .map(report -> {
                        if (report.getExtractionStatus() != ExtractionStatus.EXTRACTING) {
                            return false;
                        }
                        report.setExtractionStatus(ExtractionStatus.FAILED);
                        report.setExtractionStage(FinancialExtractionStage.FAILED);
                        report.setExtractionCompletedAt(LocalDateTime.now());
                        report.setExtractionErrorCode(errorCode);
                        report.setExtractionErrorMessage(errorMessage);
                        report.setUpdatedAt(LocalDateTime.now());
                        return true;
                    }).orElse(false);

            if (shouldSave) {
                researchRepository.save(research);
            }
        } catch (Exception e) {
            log.error("Failed to persist extraction failure for task={} report={}", taskId, reportId, e);
        }
    }

    private boolean isExtractionCancelled(Long taskId, String reportId) {
        FinancialResearch research = researchRepository.findByTaskId(taskId).orElse(null);
        if (research == null) return true;
        return research.getReports().stream()
                .filter(r -> r.getId().equals(reportId))
                .findFirst()
                .map(r -> r.getExtractionStatus() != ExtractionStatus.EXTRACTING)
                .orElse(true);
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

    public void validateReportEditableByStaff(FinancialResearch research, FinancialReportEntry report) {
        if (research == null) {
            throw new BusinessValidationException("Financial research not found.");
        }
        if (research.getStatus() != FinancialResearchStatus.DRAFT && research.getStatus() != FinancialResearchStatus.CHANGES_REQUESTED) {
            throw new BusinessValidationException("Cannot modify metrics in research with status: " + research.getStatus());
        }
        if (report != null) {
            FinancialReportReviewStatus reviewStatus = report.getReviewStatus();
            if (reviewStatus != null && reviewStatus != FinancialReportReviewStatus.CHANGES_REQUESTED) {
                throw new BusinessValidationException("Cannot modify metrics for report with review status: " + reviewStatus);
            }
        }
    }

    public FinancialResearchResponse saveManualMetricsBatch(Long projectId, Long taskId, String reportId, BatchCreateFinancialMetricsRequest request) {
        FinancialResearch research = researchRepository.findByTaskId(taskId)
                .orElseThrow(() -> new BusinessValidationException("Financial research not found for task " + taskId));

        FinancialReportEntry targetReport = null;
        if (research.getReports() != null) {
            targetReport = research.getReports().stream()
                    .filter(r -> reportId.equals(r.getId()))
                    .findFirst()
                    .orElse(null);
        }
        if (targetReport == null) {
            throw new BusinessValidationException("Report not found: " + reportId);
        }

        if (targetReport.getDataEntryMethod() != FinancialDataEntryMethod.MANUAL) {
            throw new BusinessValidationException("Batch metrics entry is only supported for MANUAL reports.");
        }

        validateReportEditableByStaff(research, targetReport);

        if (request == null || request.getMetrics() == null || request.getMetrics().isEmpty()) {
            return toResponse(research);
        }

        List<CreateFinancialMetricRequest> meaningfulRequests = request.getMetrics().stream()
                .filter(m -> m != null && StringUtils.hasText(m.getRawValue()) && StringUtils.hasText(m.getLabel()))
                .collect(Collectors.toList());

        if (meaningfulRequests.isEmpty()) {
            return toResponse(research);
        }

        // Atomic pre-validation of all metrics
        for (CreateFinancialMetricRequest req : meaningfulRequests) {
            if (!StringUtils.hasText(req.getLabel())) {
                throw new BusinessValidationException("Metric label cannot be empty.");
            }
            if (!StringUtils.hasText(req.getRawUnit())) {
                throw new BusinessValidationException("Metric unit cannot be empty for " + req.getLabel());
            }
            validateAndNormalizeFinancialNumber(req.getRawValue(), req.getLabel());
        }

        if (research.getMetrics() == null) {
            research.setMetrics(new ArrayList<>());
        }

        final String finalReportId = reportId;
        for (CreateFinancialMetricRequest req : meaningfulRequests) {
            String metricCode = req.getMetricCode();
            String normKey = generateNormalizedKey(req.getLabel());
            FinancialMetric existing = null;

            for (FinancialMetric m : research.getMetrics()) {
                if (m.getSource() != null && finalReportId.equals(m.getSource().getReportEntryId())) {
                    if (metricCode != null && metricCode.equals(m.getMetricCode())) {
                        existing = m;
                        break;
                    } else if (normKey != null && normKey.equals(m.getNormalizedKey())) {
                        existing = m;
                        break;
                    }
                }
            }

            NormalizedValue norm = normalizeManualValue(req.getRawValue(), req.getRawUnit(), req.getLabel());

            String stmtType = req.getStatementType();
            if (stmtType == null && metricCode != null) {
                CanonicalFinancialTaxonomy.findByCode(metricCode)
                        .ifPresent(d -> req.setStatementType(d.getStatementType()));
                stmtType = req.getStatementType();
            }

            if (existing != null) {
                existing.setLabel(req.getLabel().trim());
                if (req.getOriginalLabel() != null) {
                    existing.setOriginalLabel(req.getOriginalLabel().trim());
                }
                if (metricCode != null) {
                    existing.setMetricCode(metricCode);
                }
                existing.setRawValue(req.getRawValue().trim());
                existing.setRawUnit(req.getRawUnit().trim());
                existing.setNormalizedValue(norm.value);
                existing.setNormalizedUnit(norm.unit);
                existing.setInputMethod(MetricInputMethod.MANUAL);
                existing.setVerificationStatus(null);
                existing.setQualityStatus(MetricQualityStatus.VALID);
                if (stmtType != null && existing.getSource() != null) {
                    existing.getSource().setStatementType(stmtType);
                }
            } else {
                FinancialMetric newMetric = FinancialMetric.builder()
                        .id(UUID.randomUUID().toString())
                        .label(req.getLabel().trim())
                        .originalLabel(req.getOriginalLabel() != null ? req.getOriginalLabel().trim() : req.getLabel().trim())
                        .metricCode(metricCode)
                        .normalizedKey(normKey)
                        .rawValue(req.getRawValue().trim())
                        .rawUnit(req.getRawUnit().trim())
                        .normalizedValue(norm.value)
                        .normalizedUnit(norm.unit)
                        .inputMethod(MetricInputMethod.MANUAL)
                        .period(targetReport.getReportingPeriod())
                        .source(MetricSource.builder()
                                .reportEntryId(finalReportId)
                                .documentId(targetReport.getDocumentId())
                                .statementType(stmtType)
                                .build())
                        .qualityStatus(MetricQualityStatus.VALID)
                        .verificationStatus(null)
                        .build();

                research.getMetrics().add(newMetric);
            }
        }

        healOrphanManualMetrics(research);
        research = researchRepository.save(research);
        return toResponse(research);
    }

    public FinancialResearchResponse addManualMetric(Long projectId, Long taskId, CreateFinancialMetricRequest request) {
        FinancialResearch research = researchRepository.findByTaskId(taskId).orElseThrow();

        String reportEntryId = request.getReportEntryId();
        if ((reportEntryId == null || reportEntryId.isBlank()) && request.getReportId() != null) {
            reportEntryId = request.getReportId();
        }

        FinancialReportEntry targetReport = null;
        if (research.getReports() != null && !research.getReports().isEmpty()) {
            if (reportEntryId != null) {
                final String finalReportEntryId = reportEntryId;
                targetReport = research.getReports().stream()
                        .filter(r -> finalReportEntryId.equals(r.getId()))
                        .findFirst().orElse(null);
            }
            if (targetReport == null && research.getReports().size() == 1) {
                targetReport = research.getReports().get(0);
                reportEntryId = targetReport.getId();
            }
        }

        validateReportEditableByStaff(research, targetReport);

        String metricCode = request.getMetricCode();
        String label = request.getLabel();
        String normKey = generateNormalizedKey(label);

        // Check if metricCode or label matches any canonical metric (label, alias, or code)
        Optional<CanonicalFinancialTaxonomy.MetricDefinition> canonicalMatch =
                CanonicalFinancialTaxonomy.findByCodeOrAlias(metricCode != null ? metricCode : label);

        if (canonicalMatch.isPresent()) {
            CanonicalFinancialTaxonomy.MetricDefinition canDef = canonicalMatch.get();
            metricCode = canDef.getCode();
            request.setMetricCode(metricCode);
            if (request.getLabel() == null || request.getLabel().isBlank()) {
                request.setLabel(canDef.getLabel());
            }

            // Canonical duplicate check: same reportEntryId AND same metricCode
            if (targetReport != null && research.getMetrics() != null) {
                final String finalRepId = targetReport.getId();
                final String finalMetricCode = metricCode;
                boolean exists = research.getMetrics().stream()
                        .filter(m -> m.getSource() != null && finalRepId.equals(m.getSource().getReportEntryId()))
                        .anyMatch(m -> finalMetricCode.equals(m.getMetricCode()));
                if (exists) {
                    throw new BusinessValidationException("DUPLICATE_METRIC",
                            "Chỉ số '" + canDef.getLabel() + "' đã có trong báo cáo này.");
                }
            }
        } else {
            // Custom metric duplicate check: same reportEntryId AND same normalizedKey
            if (targetReport != null && research.getMetrics() != null) {
                final String finalRepId = targetReport.getId();
                boolean exists = research.getMetrics().stream()
                        .filter(m -> m.getSource() != null && finalRepId.equals(m.getSource().getReportEntryId()))
                        .anyMatch(m -> normKey != null && normKey.equals(m.getNormalizedKey()));
                if (exists) {
                    throw new BusinessValidationException("DUPLICATE_METRIC",
                            "Chỉ số '" + label + "' đã tồn tại trong báo cáo này.");
                }
            }
        }

        String sourceDocumentId = request.getSourceDocumentId();
        if (sourceDocumentId == null && targetReport != null) {
            sourceDocumentId = targetReport.getDocumentId();
        }

        String stmtType = request.getStatementType();
        if (stmtType == null && metricCode != null) {
            CanonicalFinancialTaxonomy.findByCode(metricCode)
                    .ifPresent(d -> request.setStatementType(d.getStatementType()));
            stmtType = request.getStatementType();
        }

        FinancialMetric metric = FinancialMetric.builder()
                .id(UUID.randomUUID().toString())
                .label(request.getLabel().trim())
                .originalLabel(request.getOriginalLabel() != null ? request.getOriginalLabel().trim() : request.getLabel().trim())
                .metricCode(metricCode)
                .normalizedKey(normKey)
                .rawValue(request.getRawValue())
                .rawUnit(request.getRawUnit())
                .evidence(request.getEvidence())
                .inputMethod(MetricInputMethod.MANUAL)
                .period(request.getPeriod() != null ? request.getPeriod() : (targetReport != null ? targetReport.getReportingPeriod() : null))
                .source(MetricSource.builder()
                        .reportEntryId(reportEntryId)
                        .documentId(sourceDocumentId)
                        .page(request.getSourcePage())
                        .statementType(stmtType)
                        .build())
                .qualityStatus(MetricQualityStatus.VALID)
                .verificationStatus(null)
                .build();

        NormalizedValue norm;
        if (StringUtils.hasText(request.getRawValue())) {
            norm = normalizeManualValue(request.getRawValue(), request.getRawUnit(), request.getLabel());
        } else {
            norm = new NormalizedValue(null, request.getRawUnit());
        }
        metric.setNormalizedValue(norm.value);
        metric.setNormalizedUnit(norm.unit);

        if (research.getMetrics() == null) {
            research.setMetrics(new ArrayList<>());
        }
        research.getMetrics().add(metric);

        if (targetReport != null && targetReport.getDataEntryMethod() != FinancialDataEntryMethod.MANUAL &&
                (targetReport.getExtractionStatus() == ExtractionStatus.NOT_EXTRACTED ||
                 targetReport.getExtractionStatus() == ExtractionStatus.FAILED)) {
            targetReport.setExtractionStatus(ExtractionStatus.EXTRACTED);
        }

        healOrphanManualMetrics(research);

        research = researchRepository.save(research);
        return toResponse(research);
    }

    private void healOrphanManualMetrics(FinancialResearch research) {
        if (research.getMetrics() == null || research.getMetrics().isEmpty() ||
            research.getReports() == null || research.getReports().isEmpty()) {
            return;
        }
        boolean modified = false;
        for (FinancialMetric m : research.getMetrics()) {
            if (m.getSource() == null || m.getSource().getReportEntryId() == null) {
                FinancialReportEntry targetReport = null;
                if (research.getReports().size() == 1) {
                    targetReport = research.getReports().get(0);
                } else if (m.getPeriod() != null) {
                    for (FinancialReportEntry r : research.getReports()) {
                        if (r.getReportingPeriod() != null &&
                            java.util.Objects.equals(r.getReportingPeriod().getYear(), m.getPeriod().getYear()) &&
                            r.getReportingPeriod().getPeriod() != null && m.getPeriod().getPeriod() != null &&
                            r.getReportingPeriod().getPeriod().trim().equalsIgnoreCase(m.getPeriod().getPeriod().trim())) {
                            targetReport = r;
                            break;
                        }
                    }
                }
                if (targetReport != null) {
                    if (m.getSource() == null) {
                        m.setSource(MetricSource.builder()
                                .reportEntryId(targetReport.getId())
                                .documentId(targetReport.getDocumentId())
                                .build());
                    } else {
                        m.getSource().setReportEntryId(targetReport.getId());
                        if (m.getSource().getDocumentId() == null) {
                            m.getSource().setDocumentId(targetReport.getDocumentId());
                        }
                    }
                    modified = true;
                }
            }
        }
        if (modified) {
            researchRepository.save(research);
        }
    }

    private void validateMetricEditable(FinancialResearch research, FinancialMetric metric) {
        FinancialReportEntry targetReport = null;
        if (metric.getSource() != null && metric.getSource().getReportEntryId() != null && research.getReports() != null) {
            targetReport = research.getReports().stream()
                    .filter(r -> r.getId().equals(metric.getSource().getReportEntryId()))
                    .findFirst()
                    .orElse(null);
        }
        validateReportEditableByStaff(research, targetReport);
    }

    public FinancialResearchResponse updateMetric(Long projectId, Long taskId, String metricId, UpdateFinancialMetricRequest request) {
        FinancialResearch research = researchRepository.findByTaskId(taskId).orElseThrow();
        FinancialMetric metric = research.getMetrics().stream().filter(m -> m.getId().equals(metricId)).findFirst().orElseThrow();
        
        validateMetricEditable(research, metric);

        metric.setLabel(request.getLabel());
        metric.setNormalizedKey(generateNormalizedKey(request.getLabel()));
        metric.setRawValue(request.getRawValue());
        metric.setRawUnit(request.getRawUnit());
        metric.setPeriod(request.getPeriod());
        if (request.getEvidence() != null) {
            metric.setEvidence(request.getEvidence());
        }
        
        NormalizedValue norm;
        if (StringUtils.hasText(request.getRawValue())) {
            if (metric.getInputMethod() == MetricInputMethod.MANUAL) {
                norm = normalizeManualValue(request.getRawValue(), request.getRawUnit(), request.getLabel());
            } else {
                norm = normalizeValue(request.getRawValue(), request.getRawUnit());
            }
        } else {
            norm = new NormalizedValue(null, request.getRawUnit());
        }
        metric.setNormalizedValue(norm.value);
        metric.setNormalizedUnit(norm.unit);
        
        metric.setQualityStatus(MetricQualityStatus.VALID);
        if (metric.getInputMethod() == MetricInputMethod.MANUAL) {
            metric.setVerificationStatus(null);
        } else {
            metric.setVerificationStatus(MetricVerificationStatus.VERIFIED);
        }
        
        research = researchRepository.save(research);
        return toResponse(research);
    }

    @Transactional
    public FinancialResearchResponse removeMetric(Long projectId, Long taskId, String metricId) {
        FinancialResearch research = researchRepository.findByTaskId(taskId).orElseThrow();
        FinancialMetric metric = research.getMetrics().stream().filter(m -> m.getId().equals(metricId)).findFirst().orElseThrow();
        validateMetricEditable(research, metric);
        
        research.getMetrics().removeIf(m -> m.getId().equals(metricId));
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

    public FinancialResearchResponse unverifyMetric(Long projectId, Long taskId, String metricId) {
        FinancialResearch research = researchRepository.findByTaskId(taskId).orElseThrow();
        FinancialMetric metric = research.getMetrics().stream().filter(m -> m.getId().equals(metricId)).findFirst().orElseThrow();
        
        validateMetricEditable(research, metric);

        metric.setVerificationStatus(MetricVerificationStatus.UNVERIFIED);
        research = researchRepository.save(research);
        return toResponse(research);
    }

    public FinancialResearchResponse verifyAllMetricsForReport(Long projectId, Long taskId, String reportId) {
        FinancialResearch research = researchRepository.findByTaskId(taskId)
                .orElseThrow(() -> new BusinessValidationException("Research not found"));

        FinancialReportEntry report = research.getReports().stream()
                .filter(r -> r.getId().equals(reportId))
                .findFirst()
                .orElseThrow(() -> new BusinessValidationException("Report not found"));

        if (research.getStatus() == FinancialResearchStatus.SUBMITTED || research.getStatus() == FinancialResearchStatus.APPROVED) {
            throw new BusinessValidationException("Cannot modify metrics in a submitted or approved research package.");
        }
        if (report.getReviewStatus() == FinancialReportReviewStatus.APPROVED) {
            throw new BusinessValidationException("Cannot modify metrics for an approved report.");
        }

        if (research.getMetrics() != null && !research.getMetrics().isEmpty()) {
            for (FinancialMetric metric : research.getMetrics()) {
                boolean belongs = (metric.getSource() != null && reportId.equals(metric.getSource().getReportEntryId())) ||
                        (metric.getSource() != null && metric.getSource().getDocumentId() != null && metric.getSource().getDocumentId().equals(report.getDocumentId())) ||
                        (metric.getPeriod() != null && report.getReportingPeriod() != null &&
                         java.util.Objects.equals(metric.getPeriod().getYear(), report.getReportingPeriod().getYear()) &&
                         metric.getPeriod().getPeriod() != null && report.getReportingPeriod().getPeriod() != null &&
                         metric.getPeriod().getPeriod().trim().equalsIgnoreCase(report.getReportingPeriod().getPeriod().trim()));

                if (belongs) {
                    metric.setQualityStatus(MetricQualityStatus.VALID);
                    metric.setVerificationStatus(MetricVerificationStatus.VERIFIED);
                    if (metric.getSource() == null) {
                        metric.setSource(MetricSource.builder()
                                .reportEntryId(report.getId())
                                .documentId(report.getDocumentId())
                                .build());
                    } else if (metric.getSource().getReportEntryId() == null) {
                        metric.getSource().setReportEntryId(report.getId());
                    }
                }
            }
        }

        research = researchRepository.save(research);
        return toResponse(research);
    }

    public FinancialResearchResponse unverifyAllMetricsForReport(Long projectId, Long taskId, String reportId) {
        FinancialResearch research = researchRepository.findByTaskId(taskId)
                .orElseThrow(() -> new BusinessValidationException("Research not found"));

        FinancialReportEntry report = research.getReports().stream()
                .filter(r -> r.getId().equals(reportId))
                .findFirst()
                .orElseThrow(() -> new BusinessValidationException("Report not found"));

        if (research.getStatus() == FinancialResearchStatus.SUBMITTED || research.getStatus() == FinancialResearchStatus.APPROVED) {
            throw new BusinessValidationException("Cannot modify metrics in a submitted or approved research package.");
        }
        if (report.getReviewStatus() == FinancialReportReviewStatus.APPROVED) {
            throw new BusinessValidationException("Cannot modify metrics for an approved report.");
        }

        if (research.getMetrics() != null && !research.getMetrics().isEmpty()) {
            for (FinancialMetric metric : research.getMetrics()) {
                boolean belongs = (metric.getSource() != null && reportId.equals(metric.getSource().getReportEntryId())) ||
                        (metric.getSource() != null && metric.getSource().getDocumentId() != null && metric.getSource().getDocumentId().equals(report.getDocumentId())) ||
                        (metric.getPeriod() != null && report.getReportingPeriod() != null &&
                         java.util.Objects.equals(metric.getPeriod().getYear(), report.getReportingPeriod().getYear()) &&
                         metric.getPeriod().getPeriod() != null && report.getReportingPeriod().getPeriod() != null &&
                         metric.getPeriod().getPeriod().trim().equalsIgnoreCase(report.getReportingPeriod().getPeriod().trim()));

                if (belongs) {
                    metric.setVerificationStatus(MetricVerificationStatus.UNVERIFIED);
                }
            }
        }

        research = researchRepository.save(research);
        return toResponse(research);
    }

    @Transactional
    public FinancialResearchResponse confirmCompanyMatch(
            Long projectId,
            Long taskId,
            String reportId,
            boolean confirmed,
            Long currentUserId) {

        com.apms.domain.project.ProjectTask task = projectTaskRepository.findWithProjectById(taskId)
                .orElseThrow(() -> new BusinessValidationException("Task not found: " + taskId));

        if (!task.getProject().getId().equals(projectId)) {
            throw new BusinessValidationException("Task does not belong to the specified project");
        }

        if (task.getTaskType() != com.apms.common.enums.TaskType.FINANCIAL_RESEARCH) {
            throw new BusinessValidationException("Task type must be FINANCIAL_RESEARCH");
        }

        if (currentUserId != null) {
            if (!projectRepository.existsByIdAndMembersAccountId(projectId, currentUserId)) {
                throw new AccessDeniedException("User is not a member of this project");
            }
            UserDetailsImpl currentUser = getCurrentUserDetails();
            boolean isManager = currentUser != null && currentUser.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("ROLE_BUSINESS_DEVELOPMENT_MANAGER")
                            || a.getAuthority().equals("ROLE_SYSTEM_ADMIN")
                            || a.getAuthority().equals("ROLE_ADMIN"));
            boolean isAssigned = task.getAssignedToAccount() != null && task.getAssignedToAccount().getId().equals(currentUserId);
            if (!isAssigned && !isManager) {
                throw new AccessDeniedException("Only the assigned staff or manager can confirm company match for this task");
            }
        }

        if (task.getStatus() == com.apms.common.enums.TaskStatus.DONE || task.getStatus() == com.apms.common.enums.TaskStatus.CANCELLED) {
            throw new BusinessValidationException("Task is not in an editable state");
        }

        FinancialResearch research = researchRepository.findByTaskId(taskId)
                .orElseThrow(() -> new BusinessValidationException("Financial research entity not found for task " + taskId));

        if (!research.getProjectId().equals(projectId)) {
            throw new BusinessValidationException("Financial research entity does not belong to the specified project");
        }

        FinancialReportEntry report = research.getReports().stream()
                .filter(r -> r.getId().equals(reportId))
                .findFirst()
                .orElseThrow(() -> new BusinessValidationException("Report not found: " + reportId));

        if (research.getStatus() == FinancialResearchStatus.SUBMITTED) {
            throw new BusinessValidationException("REPORT_IN_REVIEW", "Report is currently under Manager review and cannot be modified.");
        }
        if (research.getStatus() == FinancialResearchStatus.APPROVED || report.getReviewStatus() == FinancialReportReviewStatus.APPROVED) {
            throw new BusinessValidationException("REPORT_APPROVED_IMMUTABLE", "Approved report is immutable and cannot be modified.");
        }

        if (report.getDocumentContext() == null) {
            report.setDocumentContext(DocumentContext.builder()
                    .companyValidation(DocumentCompanyValidationStatus.UNKNOWN)
                    .build());
        }

        if (confirmed) {
            report.getDocumentContext().setCompanyVerifiedByStaff(true);
            report.getDocumentContext().setCompanyVerifiedByStaffId(currentUserId);
            report.getDocumentContext().setCompanyVerifiedAt(LocalDateTime.now());
        } else {
            report.getDocumentContext().setCompanyVerifiedByStaff(false);
            report.getDocumentContext().setCompanyVerifiedByStaffId(null);
            report.getDocumentContext().setCompanyVerifiedAt(null);
        }

        report.setUpdatedAt(LocalDateTime.now());
        research.setUpdatedAt(LocalDateTime.now());
        research = researchRepository.save(research);

        auditLogService.log(
                currentUserId,
                AuditAction.PROJECT_TASK_UPDATED,
                "ProjectTask",
                taskId.toString(),
                (confirmed ? "Confirmed" : "Unconfirmed") + " company match for report " + report.getTitle()
        );

        return toResponse(research);
    }

    public static boolean requiresCompanyConfirmation(DocumentContext context) {
        if (context == null) {
            return false;
        }
        DocumentCompanyValidationStatus status = context.getCompanyValidation() != null
                ? context.getCompanyValidation()
                : DocumentCompanyValidationStatus.UNKNOWN;
        return status != DocumentCompanyValidationStatus.MATCH
                && !Boolean.TRUE.equals(context.getCompanyVerifiedByStaff());
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
                    
            if (report.getDataEntryMethod() == FinancialDataEntryMethod.MANUAL) {
                List<FinancialMetric> reportMetrics = research.getMetrics() != null ? research.getMetrics().stream()
                        .filter(m -> m.getSource() != null && reportId.equals(m.getSource().getReportEntryId()))
                        .collect(Collectors.toList()) : Collections.emptyList();
                if (reportMetrics.isEmpty()) {
                    throw new BusinessValidationException("NO_METRICS", "Báo cáo '" + report.getTitle() + "' chưa có chỉ số nào được nhập.");
                }
                for (FinancialMetric m : reportMetrics) {
                    if (StringUtils.hasText(m.getRawValue())) {
                        validateAndNormalizeFinancialNumber(m.getRawValue(), m.getLabel());
                    }
                }
            } else {
                if (report.getExtractionStatus() != ExtractionStatus.EXTRACTED && report.getExtractionStatus() != ExtractionStatus.NEEDS_REVIEW) {
                    throw new BusinessValidationException("Cannot submit report that has not been extracted: " + report.getTitle());
                }

                if (requiresCompanyConfirmation(report.getDocumentContext())) {
                    throw new BusinessValidationException(
                            "COMPANY_MATCH_UNCONFIRMED",
                            "Báo cáo '" + report.getTitle() + "' yêu cầu xác nhận công ty mục tiêu trước khi nộp cho Manager."
                    );
                }
                
                long reportMetricCount = research.getMetrics() != null ? research.getMetrics().stream()
                        .filter(m -> m.getSource() != null && reportId.equals(m.getSource().getReportEntryId()))
                        .count() : 0;
                if (reportMetricCount == 0) {
                    throw new BusinessValidationException("NO_METRICS", "Báo cáo '" + report.getTitle() + "' chưa có chỉ số nào được trích xuất.");
                }

                // Check for unverified metrics in THIS report - all AI-extracted metrics must be VERIFIED before submit
                boolean hasUnverified = research.getMetrics() != null && research.getMetrics().stream()
                        .filter(m -> m.getSource() != null && reportId.equals(m.getSource().getReportEntryId()))
                        .filter(m -> m.getInputMethod() != MetricInputMethod.MANUAL)
                        .anyMatch(m -> m.getVerificationStatus() != MetricVerificationStatus.VERIFIED);
                if (hasUnverified) {
                    throw new BusinessValidationException("UNVERIFIED_FIELDS", "Không thể nộp báo cáo '" + report.getTitle() + "' do còn chỉ số chưa được xác thực. Vui lòng xác thực tất cả các chỉ số trước khi nộp cho Manager.");
                }
            }

            // Set review status to PENDING_REVIEW for all selected reports
            report.setReviewStatus(FinancialReportReviewStatus.PENDING_REVIEW);
            report.setReviewComment(null);
            report.setReviewedBy(null);
            report.setReviewedByName(null);
            report.setReviewedAt(null);
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
        if (!StringUtils.hasText(companyProfileId)) {
            return Collections.emptyList();
        }

        // 1. Centralized company identity resolution
        Optional<CompanyIdentity> identityOpt = companyIdentityResolver != null
                ? companyIdentityResolver.resolve(companyProfileId)
                : Optional.empty();

        // 2. Authoritative Access Control Check
        UserDetailsImpl currentUser = getCurrentUserDetails();
        if (currentUser != null && identityOpt.isPresent()) {
            CompanyProfile profile = identityOpt.get().getProfile();
            if (profile != null) {
                boolean isOwner = currentUser.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_BUSINESS_OWNER"));
                boolean isAdmin = currentUser.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_SYSTEM_ADMIN"));
                boolean isManager = currentUser.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_BUSINESS_DEVELOPMENT_MANAGER"));
                boolean isStaff = currentUser.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_BUSINESS_DEVELOPMENT_STAFF"));

                if (isManager && !isAdmin && !isOwner) {
                    if (companyProfileAccessService != null && !companyProfileAccessService.isManagerAuthorizedForCompany(profile, currentUser.getId())) {
                        log.warn("Manager {} denied access to financials of company {}", currentUser.getId(), profile.getCompanyId());
                        throw new AccessDeniedException("MANAGER_NOT_AUTHORIZED_FOR_COMPANY");
                    }
                } else if (isStaff && !isAdmin && !isOwner) {
                    if (companyScope != null && !companyScope.canAccessCompany(identityOpt.get().getCanonicalCompanyId())) {
                        log.warn("Staff {} denied access to financials of company {}", currentUser.getId(), profile.getCompanyId());
                        throw new AccessDeniedException("STAFF_NOT_AUTHORIZED_FOR_COMPANY");
                    }
                }
            }
        }

        // 3. Resolve candidate identifiers (universal companyId and Mongo _id)
        Set<String> candidateIds = new LinkedHashSet<>();
        candidateIds.add(companyProfileId.trim());
        if (identityOpt.isPresent()) {
            candidateIds.addAll(identityOpt.get().allIdentifiers());
        }

        // 4. Query FinancialResearch by candidate identifiers with deduplication by research ID
        Map<String, FinancialResearch> deduplicatedResearch = new LinkedHashMap<>();
        List<FinancialResearch> directMatches = researchRepository.findByCompanyProfileIdInAndStatus(candidateIds, FinancialResearchStatus.APPROVED);
        for (FinancialResearch r : directMatches) {
            if (r != null && r.getId() != null) {
                deduplicatedResearch.putIfAbsent(r.getId(), r);
            }
        }

        // 5. Fallback: Search tasks by target company profile ID across candidate IDs
        if (deduplicatedResearch.isEmpty()) {
            Set<Long> seenTaskIds = new HashSet<>();
            List<com.apms.domain.project.ProjectTask> tasks = new ArrayList<>();
            for (String cid : candidateIds) {
                try {
                    List<com.apms.domain.project.ProjectTask> t1 = projectTaskRepository.findByTargetCompanyProfileId(cid);
                    if (t1 != null) {
                        for (var t : t1) {
                            if (t != null && t.getId() != null && seenTaskIds.add(t.getId())) {
                                tasks.add(t);
                            }
                        }
                    }
                    List<com.apms.domain.project.ProjectTask> t2 = projectTaskRepository.findByProject_TargetCompanyProfileId(cid);
                    if (t2 != null) {
                        for (var t : t2) {
                            if (t != null && t.getId() != null && seenTaskIds.add(t.getId())) {
                                tasks.add(t);
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("Failed to find tasks by target company profile id: {}", cid, e);
                }
            }

            for (com.apms.domain.project.ProjectTask pt : tasks) {
                researchRepository.findByTaskId(pt.getId()).ifPresent(r -> {
                    if (r.getStatus() == FinancialResearchStatus.APPROVED && r.getId() != null) {
                        deduplicatedResearch.putIfAbsent(r.getId(), r);
                    }
                });
            }
        }

        // 6. Filter only approved report entries and corresponding metrics; exclude research with 0 approved reports
        return deduplicatedResearch.values().stream().map(r -> {
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
        })
        .filter(resp -> resp.getReports() != null && !resp.getReports().isEmpty())
        .collect(Collectors.toList());
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

        com.apms.domain.project.ProjectTaskSubmission activeSub = activeSubs.get(0);
        com.apms.domain.project.ProjectTask task = projectTaskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessValidationException("Task not found"));

        FinancialReportEntry report = research.getReports().stream()
                .filter(r -> r.getId().equals(reportId))
                .findFirst()
                .orElseThrow(() -> new BusinessValidationException("Report not found"));

        // Tighten report review validation: decision allowed only when report is PENDING_REVIEW or null
        boolean reviewable = report.getReviewStatus() == null
                || report.getReviewStatus() == FinancialReportReviewStatus.PENDING_REVIEW;
        if (!reviewable) {
            throw new BusinessValidationException("This report has already been reviewed in the current review cycle.");
        }

        // Validate package membership
        if (activeSub.getTargetItemIdList() != null && !activeSub.getTargetItemIdList().isEmpty()) {
            research.setSubmittedReportIds(new ArrayList<>(activeSub.getTargetItemIdList()));
            if (!activeSub.getTargetItemIdList().contains(reportId)) {
                throw new BusinessValidationException("Report is not included in the current submission package.");
            }
        } else if (research.getSubmittedReportIds() != null && !research.getSubmittedReportIds().isEmpty()) {
            if (!research.getSubmittedReportIds().contains(reportId)) {
                throw new BusinessValidationException("Report is not included in the current submission package.");
            }
        } else {
            List<String> autoIds = research.getReports() != null
                    ? research.getReports().stream().map(FinancialReportEntry::getId).collect(Collectors.toList())
                    : new ArrayList<>();
            research.setSubmittedReportIds(autoIds);
        }

        if (request.getStatus() == FinancialReportReviewStatus.APPROVED) {
            if (report.getDataEntryMethod() == FinancialDataEntryMethod.MANUAL) {
                long reportMetricCount = research.getMetrics() != null ? research.getMetrics().stream()
                        .filter(m -> m.getSource() != null && reportId.equals(m.getSource().getReportEntryId()))
                        .count() : 0;
                if (reportMetricCount == 0) {
                    throw new BusinessValidationException("Cannot approve manual report without metrics.");
                }
            } else {
                if (report.getExtractionStatus() != ExtractionStatus.EXTRACTED && report.getExtractionStatus() != ExtractionStatus.NEEDS_REVIEW) {
                    throw new BusinessValidationException("Cannot approve report that has not been extracted.");
                }
                boolean hasUnverified = research.getMetrics() != null && research.getMetrics().stream()
                        .filter(m -> m.getSource() != null && reportId.equals(m.getSource().getReportEntryId()))
                        .filter(m -> m.getInputMethod() != MetricInputMethod.MANUAL)
                        .anyMatch(m -> m.getQualityStatus() == MetricQualityStatus.NEEDS_REVIEW && m.getVerificationStatus() == MetricVerificationStatus.UNVERIFIED);
                if (hasUnverified) {
                    throw new BusinessValidationException("Cannot approve report with unverified metrics that need review.");
                }
            }
        } else if (request.getStatus() == FinancialReportReviewStatus.CHANGES_REQUESTED) {
            if (!org.springframework.util.StringUtils.hasText(request.getReason())) {
                throw new BusinessValidationException("Reason is required when requesting changes.");
            }
        } else {
            throw new BusinessValidationException("Invalid review decision status: " + request.getStatus());
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

        // Recalculate parent review state across reports, research, task, and submission
        recalculateReviewState(research, task, activeSub, reviewerId);

        research = researchRepository.save(research);
        String action = request.getStatus() == FinancialReportReviewStatus.APPROVED ? "Approved" : "Requested changes for";
        auditLogService.log(reviewerId, AuditAction.FINANCIAL_RESEARCH_CHANGES_REQUESTED, "ProjectTask", taskId.toString(), action + " report " + report.getTitle());
        return toResponse(research);
    }

    private void recalculateReviewState(
            FinancialResearch research,
            com.apms.domain.project.ProjectTask task,
            com.apms.domain.project.ProjectTaskSubmission activeSubmission,
            Long reviewerId
    ) {
        List<FinancialReportEntry> allReports = research.getReports() != null
                ? research.getReports()
                : Collections.emptyList();

        // 1. Determine reports in current active review package
        Set<String> packageReportIds = new HashSet<>();
        if (activeSubmission != null && activeSubmission.getTargetItemIdList() != null && !activeSubmission.getTargetItemIdList().isEmpty()) {
            packageReportIds.addAll(activeSubmission.getTargetItemIdList());
        } else if (research.getSubmittedReportIds() != null && !research.getSubmittedReportIds().isEmpty()) {
            packageReportIds.addAll(research.getSubmittedReportIds());
        }

        List<FinancialReportEntry> reviewPackageReports = allReports.stream()
                .filter(r -> packageReportIds.isEmpty() || packageReportIds.contains(r.getId()))
                .collect(Collectors.toList());

        // 2. Pending reports check (scoped strictly to current review package)
        boolean hasPending = reviewPackageReports.stream()
                .anyMatch(r -> r.getReviewStatus() == null || r.getReviewStatus() == FinancialReportReviewStatus.PENDING_REVIEW);

        // 3. Changes requested check (across all reports in the research package)
        boolean hasChangesRequested = allReports.stream()
                .anyMatch(r -> r.getReviewStatus() == FinancialReportReviewStatus.CHANGES_REQUESTED);

        // 4. All approved check (every report in research package must be approved)
        boolean allApproved = !allReports.isEmpty() && allReports.stream()
                .allMatch(r -> r.getReviewStatus() == FinancialReportReviewStatus.APPROVED);

        LocalDateTime now = LocalDateTime.now();
        com.apms.domain.user.Account reviewerAccount = accountRepository != null && reviewerId != null
                ? accountRepository.findById(reviewerId).orElse(null)
                : null;

        // 5. Apply precedence rules
        if (hasPending) {
            // Manager has not finished reviewing all submitted reports in the current package.
            research.setStatus(FinancialResearchStatus.SUBMITTED);
            if (task != null && task.getStatus() != com.apms.common.enums.TaskStatus.IN_REVIEW) {
                task.setStatus(com.apms.common.enums.TaskStatus.IN_REVIEW);
                task.setCompletedAt(null);
                projectTaskRepository.save(task);
            }
            if (activeSubmission != null && activeSubmission.getStatus() != com.apms.common.enums.SubmissionStatus.IN_REVIEW) {
                activeSubmission.setStatus(com.apms.common.enums.SubmissionStatus.IN_REVIEW);
                submissionRepository.save(activeSubmission);
            }
        } else if (allApproved) {
            // All reports across the research package are approved
            research.setStatus(FinancialResearchStatus.APPROVED);
            research.setReviewedBy(reviewerId);
            research.setReviewedAt(now);
            research.setReviewReason("All financial reports approved");

            if (task != null) {
                String targetProfileId = task.getTargetCompanyProfileId() != null
                        ? task.getTargetCompanyProfileId()
                        : (task.getProject() != null ? task.getProject().getTargetCompanyProfileId() : null);
                if (targetProfileId != null) {
                    research.setCompanyProfileId(targetProfileId);
                }
                task.setStatus(com.apms.common.enums.TaskStatus.DONE);
                task.setCompletedAt(now);
                projectTaskRepository.save(task);
            }

            List<com.apms.domain.project.ProjectTaskSubmission> subs = submissionRepository.findByProjectTask_Id(task != null ? task.getId() : research.getTaskId());
            for (com.apms.domain.project.ProjectTaskSubmission s : subs) {
                if (s.getStatus() == com.apms.common.enums.SubmissionStatus.IN_REVIEW || s.getStatus() == com.apms.common.enums.SubmissionStatus.APPROVED) {
                    s.setStatus(com.apms.common.enums.SubmissionStatus.APPROVED);
                    s.setReviewedByAccount(reviewerAccount);
                    s.setReviewedAt(now);
                    s.setReviewComment("All financial reports approved");
                    submissionRepository.save(s);
                }
            }
        } else if (hasChangesRequested) {
            // No pending reports remain in package, and at least one report has CHANGES_REQUESTED
            research.setStatus(FinancialResearchStatus.CHANGES_REQUESTED);
            research.setReviewedBy(reviewerId);
            research.setReviewedAt(now);

            // Generate aggregated feedback from ALL reports currently marked CHANGES_REQUESTED
            List<FinancialReportEntry> changedReports = allReports.stream()
                    .filter(r -> r.getReviewStatus() == FinancialReportReviewStatus.CHANGES_REQUESTED)
                    .collect(Collectors.toList());

            String aggregatedFeedback = changedReports.stream()
                    .map(r -> {
                        String title = org.springframework.util.StringUtils.hasText(r.getTitle()) ? r.getTitle() : "Report";
                        String comment = org.springframework.util.StringUtils.hasText(r.getReviewComment()) ? r.getReviewComment() : "Changes requested";
                        return title + ": " + comment;
                    })
                    .collect(Collectors.joining("\n"));

            research.setReviewReason(aggregatedFeedback);

            if (task != null) {
                task.setStatus(com.apms.common.enums.TaskStatus.IN_PROGRESS);
                task.setCompletedAt(null);
                projectTaskRepository.save(task);
            }

            List<com.apms.domain.project.ProjectTaskSubmission> subs = submissionRepository.findByProjectTask_Id(task != null ? task.getId() : research.getTaskId());
            for (com.apms.domain.project.ProjectTaskSubmission s : subs) {
                if (s.getStatus() == com.apms.common.enums.SubmissionStatus.IN_REVIEW || s.getStatus() == com.apms.common.enums.SubmissionStatus.CHANGES_REQUESTED) {
                    s.setStatus(com.apms.common.enums.SubmissionStatus.CHANGES_REQUESTED);
                    s.setReviewedByAccount(reviewerAccount);
                    s.setReviewedAt(now);
                    s.setReviewComment(aggregatedFeedback);
                    submissionRepository.save(s);
                }
            }
        }
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
        DocumentCompanyValidationStatus companyValidation = companyMatcher.evaluateCompanyMatch(candidate.getCompanyName(), targetCompanyName);
        boolean isMatch = (companyValidation == DocumentCompanyValidationStatus.MATCH);
        
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
                .companyVerifiedByStaff(isMatch)
                .companyVerifiedByStaffId(null)
                .companyVerifiedAt(null)
                .periodValidation(periodValidation)
                .build();
    }

    public static boolean isCumulativeColumn(String sourceColumn) {
        if (sourceColumn == null || sourceColumn.isBlank()) {
            return false;
        }
        String lower = sourceColumn.trim().toLowerCase();
        String normalized = Normalizer.normalize(lower, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");

        return normalized.contains("luy ke")
                || normalized.contains("6 thang")
                || normalized.contains("9 thang")
                || normalized.contains("ban nien")
                || normalized.contains("nua nam")
                || normalized.contains("ytd")
                || normalized.contains("year to date")
                || normalized.contains("year-to-date")
                || normalized.contains("cumulative")
                || normalized.contains("full year")
                || normalized.contains("annual")
                || normalized.contains("ca nam")
                || normalized.contains("12 thang");
    }

    public static boolean isIncomeStatementMetric(String label, String statementType) {
        if ("INCOME_STATEMENT".equalsIgnoreCase(statementType)) {
            return true;
        }
        if (label == null) return false;
        String lower = Normalizer.normalize(label.toLowerCase(), Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        return lower.contains("doanh thu")
                || lower.contains("gia von")
                || lower.contains("loi nhuan")
                || lower.contains("chi phi")
                || lower.contains("thu nhap")
                || lower.contains("lai co ban")
                || lower.contains("eps");
    }

    public FinancialMetric mapToMetric(AiFinancialMetricCandidate candidate, RawDocument doc, String reportEntryId) {
        FinancialReportEntry dummyReport = FinancialReportEntry.builder().id(reportEntryId).build();
        return mapToMetric(candidate, doc, dummyReport);
    }

    public FinancialMetric mapToMetric(AiFinancialMetricCandidate candidate, RawDocument doc, FinancialReportEntry report) {
        NormalizedValue normalized = normalizeValue(candidate.getRawValue(), candidate.getRawUnit());
        
        MetricQualityStatus quality = MetricQualityStatus.VALID;
        if (candidate.getConfidence() == null || candidate.getConfidence() < 0.7) {
            quality = MetricQualityStatus.NEEDS_REVIEW;
        }
        if (normalized.unit == null || normalized.unit.equals(candidate.getRawUnit()) && !isStandardUnit(normalized.unit)) {
            quality = MetricQualityStatus.NEEDS_REVIEW;
        }

        // ReportingPeriod normalization: report.reportingPeriod is the authoritative SOURCE OF TRUTH
        ReportingPeriod reportPeriod = report != null ? report.getReportingPeriod() : null;
        ReportingPeriod finalPeriod;

        if (reportPeriod != null && reportPeriod.getPeriod() != null) {
            String asOfDate = null;
            if (candidate.getPeriod() != null && candidate.getPeriod().getAsOfDate() != null) {
                asOfDate = candidate.getPeriod().getAsOfDate();
            } else if (reportPeriod.getAsOfDate() != null) {
                asOfDate = reportPeriod.getAsOfDate();
            }

            finalPeriod = ReportingPeriod.builder()
                    .year(reportPeriod.getYear())
                    .periodType(reportPeriod.getPeriodType())
                    .period(reportPeriod.getPeriod())
                    .asOfDate(asOfDate)
                    .build();
        } else if (candidate.getPeriod() != null) {
            finalPeriod = candidate.getPeriod();
        } else {
            finalPeriod = null;
        }

        return FinancialMetric.builder()
                .id(UUID.randomUUID().toString())
                .label(candidate.getLabel())
                .originalLabel(candidate.getOriginalLabel() != null ? candidate.getOriginalLabel() : candidate.getLabel())
                .metricCode(candidate.getMetricCode())
                .normalizedKey(generateNormalizedKey(candidate.getLabel()))
                .rawValue(candidate.getRawValue())
                .rawUnit(candidate.getRawUnit())
                .normalizedValue(normalized.value)
                .normalizedUnit(normalized.unit)
                .inputMethod(MetricInputMethod.AI_EXTRACTED)
                .period(finalPeriod)
                .source(MetricSource.builder()
                        .reportEntryId(report != null ? report.getId() : null)
                        .documentId(doc != null ? doc.getId() : null)
                        .documentName(doc != null && doc.getSource() != null ? doc.getSource().getFileName() : "Unknown")
                        .page(candidate.getSourcePage())
                        .sourceColumn(candidate.getSourceColumn())
                        .statementType(candidate.getStatementType())
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

    public static BigDecimal validateAndNormalizeFinancialNumber(String rawValue, String metricLabel) {
        if (!StringUtils.hasText(rawValue)) {
            return null;
        }
        String trimmed = rawValue.trim();
        if (!FINANCIAL_NUMBER_PATTERN.matcher(trimmed).matches()) {
            throw new BusinessValidationException("INVALID_METRIC_VALUE",
                    "Giá trị chỉ số tài chính phải là số" + (metricLabel != null && !metricLabel.isBlank() ? " (" + metricLabel + ")" : "") + ".");
        }
        String normalized = trimmed.replace(",", "");
        try {
            return new BigDecimal(normalized);
        } catch (Exception e) {
            throw new BusinessValidationException("INVALID_METRIC_VALUE",
                    "Giá trị chỉ số tài chính phải là số" + (metricLabel != null && !metricLabel.isBlank() ? " (" + metricLabel + ")" : "") + ".");
        }
    }

    private NormalizedValue applyUnitScale(BigDecimal bd, String rawUnit) {
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
    }

    private NormalizedValue normalizeManualValue(String rawValue, String rawUnit, String metricLabel) {
        if (!StringUtils.hasText(rawValue)) {
            return new NormalizedValue(null, rawUnit);
        }
        BigDecimal bd = validateAndNormalizeFinancialNumber(rawValue, metricLabel);
        if (bd == null) {
            return new NormalizedValue(null, rawUnit);
        }
        return applyUnitScale(bd, rawUnit);
    }

    private NormalizedValue normalizeValue(String rawValue, String rawUnit) {
        if (!StringUtils.hasText(rawValue)) {
            return new NormalizedValue(null, rawUnit);
        }

        try {
            String cleanVal = rawValue.replaceAll("[^0-9.-]", "");
            BigDecimal bd = new BigDecimal(cleanVal);
            return applyUnitScale(bd, rawUnit);
        } catch (Exception e) {
            log.warn("Failed to parse value: {}", rawValue);
            return new NormalizedValue(null, rawUnit);
        }
    }

    public FinancialResearchResponse toResponse(FinancialResearch domain) {
        Map<String, FinancialReportEntry> reportMap = domain.getReports() != null ? domain.getReports().stream()
                .collect(Collectors.toMap(FinancialReportEntry::getId, r -> r, (a, b) -> a)) : Collections.emptyMap();

        List<FinancialMetricResponse> metricResponses = domain.getMetrics() != null ? domain.getMetrics().stream().map(m -> {
            ReportingPeriod responsePeriod = m.getPeriod();
            if (m.getSource() != null && m.getSource().getReportEntryId() != null) {
                FinancialReportEntry rep = reportMap.get(m.getSource().getReportEntryId());
                if (rep != null && rep.getReportingPeriod() != null && rep.getReportingPeriod().getPeriod() != null) {
                    ReportingPeriod rp = rep.getReportingPeriod();
                    if (responsePeriod == null || responsePeriod.getPeriod() == null || responsePeriod.getPeriod().isBlank()
                            || responsePeriod.getPeriodType() == ReportingPeriodType.AS_OF_DATE) {
                        responsePeriod = ReportingPeriod.builder()
                                .year(rp.getYear() != null ? rp.getYear() : (responsePeriod != null ? responsePeriod.getYear() : null))
                                .periodType(rp.getPeriodType())
                                .period(rp.getPeriod())
                                .asOfDate(responsePeriod != null && responsePeriod.getAsOfDate() != null ? responsePeriod.getAsOfDate() : rp.getAsOfDate())
                                .build();
                    }
                }
            }

            return FinancialMetricResponse.builder()
                    .id(m.getId())
                    .label(m.getLabel())
                    .originalLabel(m.getOriginalLabel())
                    .metricCode(m.getMetricCode())
                    .normalizedKey(m.getNormalizedKey())
                    .rawValue(m.getRawValue())
                    .rawUnit(m.getRawUnit())
                    .normalizedValue(m.getNormalizedValue() != null ? m.getNormalizedValue().toString() : null)
                    .normalizedUnit(m.getNormalizedUnit())
                    .inputMethod(m.getInputMethod())
                    .period(responsePeriod)
                    .source(m.getSource())
                    .evidence(m.getEvidence())
                    .confidence(m.getConfidence())
                    .qualityStatus(m.getQualityStatus())
                    .verificationStatus(m.getVerificationStatus())
                    .build();
        }).collect(Collectors.toList()) : new ArrayList<>();

        List<FinancialReportEntry> filteredReports = domain.getReports() != null ? new ArrayList<>(domain.getReports()) : new ArrayList<>();
        for (FinancialReportEntry rep : filteredReports) {
            if (rep.getFileName() == null && rep.getDocumentId() != null) {
                if (rep.getDocumentContext() != null && rep.getDocumentContext().getDocumentName() != null) {
                    rep.setFileName(rep.getDocumentContext().getDocumentName());
                } else {
                    try {
                        RawDocument doc = documentRepository.findById(rep.getDocumentId()).orElse(null);
                        if (doc != null && doc.getSource() != null) {
                            rep.setFileName(doc.getSource().getFileName());
                        }
                    } catch (Exception ignored) {}
                }
            }
        }
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
        if (SecurityContextHolder.getContext() != null && SecurityContextHolder.getContext().getAuthentication() != null) {
            Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
            if (principal instanceof UserDetails) {
                try {
                    java.lang.reflect.Method getIdMethod = principal.getClass().getMethod("getId");
                    return (Long) getIdMethod.invoke(principal);
                } catch (Exception e) {
                    log.warn("Could not extract user id", e);
                }
            }
        }
        return 1L; // fallback
    }

    public Integer resolveTaskTargetYear(Long taskId, FinancialResearch research) {
        if (research != null && research.getTargetResearchPeriod() != null && research.getTargetResearchPeriod().getYear() != null) {
            return research.getTargetResearchPeriod().getYear();
        }

        try {
            com.apms.domain.project.ProjectTask task = projectTaskRepository.findWithProjectById(taskId)
                    .or(() -> projectTaskRepository.findById(taskId))
                    .orElse(null);
            if (task != null) {
                Integer yearFromText = extractYearFromText(task.getTitle());
                if (yearFromText != null) return yearFromText;

                yearFromText = extractYearFromText(task.getDescription());
                if (yearFromText != null) return yearFromText;

                if (task.getDueDate() != null) {
                    return task.getDueDate().getYear();
                }

                if (task.getProject() != null && task.getProject().getPlannedEndDate() != null) {
                    return task.getProject().getPlannedEndDate().getYear();
                }

                if (task.getProject() != null) {
                    yearFromText = extractYearFromText(task.getProject().getProjectName());
                    if (yearFromText != null) return yearFromText;

                    yearFromText = extractYearFromText(task.getProject().getDescription());
                    if (yearFromText != null) return yearFromText;

                    if (task.getProject().getCreatedAt() != null) {
                        return task.getProject().getCreatedAt().getYear();
                    }
                }

                if (task.getCreatedAt() != null) {
                    return task.getCreatedAt().getYear();
                }
            }
        } catch (Exception e) {
            log.warn("Could not resolve target year from task {}: {}", taskId, e.getMessage());
        }

        return null;
    }

    private Integer extractYearFromText(String text) {
        if (text == null || text.isBlank()) return null;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\b(20\\d{2})\\b").matcher(text);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {}
        }
        return null;
    }
}
