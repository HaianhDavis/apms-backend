package com.apms.domain.ai.service;

import com.apms.common.enums.CandidateStatus;
import com.apms.common.exception.ResourceNotFoundException;
import com.apms.domain.ai.dto.RawExtractionOutput;
import com.apms.domain.ai.dto.ExtractionFieldResult;
import com.apms.domain.ai.dto.ExtractionQualityMetrics;
import com.apms.domain.ai.dto.ExtractionQualityStatus;
import com.apms.domain.ai.service.provider.GeminiExtractionProvider;
import java.util.Set;
import com.apms.domain.candidate.CompanyCandidate;
import com.apms.domain.candidate.dto.CandidateResponse;
import com.apms.domain.candidate.repository.mongo.CompanyCandidateRepository;
import com.apms.domain.candidate.service.CandidateService;
import com.apms.domain.ai.entity.AiExtractionJob;
import com.apms.domain.ai.repository.AiExtractionJobRepository;
import com.apms.common.enums.AiExtractionJobStatus;
import com.apms.common.enums.AiExtractionJobStage;
import com.apms.domain.document.RawDocument;
import com.apms.domain.document.repository.mongo.RawDocumentRepository;
import com.apms.domain.project.Project;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.apms.domain.user.Account;
import com.apms.domain.user.repository.sql.AccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class TaskExtractionOrchestrator {
    private static final String SOURCE_TYPE_PARTNER_CONTRACT = "PARTNER_CONTRACT";

    private final RawDocumentRepository rawDocumentRepository;
    private final GeminiExtractionProvider geminiProvider;
    private final CompanyCandidateRepository candidateRepository;
    private final CandidateService candidateService;
    private final AiExtractionJobRepository jobRepository;
    private final AiExtractionQualityService qualityService;
    private final com.apms.domain.project.repository.sql.ProjectTaskRepository projectTaskRepository;
    private final ProjectRepository projectRepository;
    private final AccountRepository accountRepository;
    private final DocumentCompanyConsistencyValidator companyConsistencyValidator;
    private final com.apms.domain.profile.repository.mongo.CompanyProfileRepository companyProfileRepository;
    private final com.apms.domain.financial.service.DocumentCompanyMatcher companyMatcher;
    private final CompanyIdentityDetectionService identityDetectionService;

    @Value("${app.storage.upload-dir:uploads/}")
    private String uploadDir;

    @Value("${app.ai.gemini.model:gemini-3.6-flash}")
    private String geminiModel;

    @Transactional
    public String startExtractionJob(Long projectId, Long taskId, List<String> rawDocumentIds, Long creatorId) {
        validateResearchExtractionRequest(projectId, taskId, rawDocumentIds, creatorId);

        String jobId = UUID.randomUUID().toString();
        AiExtractionJob job = AiExtractionJob.builder()
                .id(jobId)
                .taskId(taskId)
                .status(AiExtractionJobStatus.PENDING)
                .stage(null)
                .progress(0)
                .totalDocuments(rawDocumentIds.size())
                .processedDocuments(0)
                .build();
        jobRepository.save(job);
        return jobId;
    }

    private void validateResearchExtractionRequest(Long projectId, Long taskId, List<String> rawDocumentIds, Long currentUserId) {
        // 1. project/task relationship & 2. current-user access
        validateTaskAccess(projectId, taskId, currentUserId);

        com.apms.domain.project.ProjectTask task = projectTaskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found: " + taskId));

        if (task.getStatus() == com.apms.common.enums.TaskStatus.DONE ||
            task.getStatus() == com.apms.common.enums.TaskStatus.CANCELLED ||
            task.getStatus() == com.apms.common.enums.TaskStatus.IN_REVIEW) {
            throw new com.apms.common.exception.BusinessValidationException("Cannot run extraction while task is in review, completed, or cancelled.");
        }

        // 3. each raw document exists, 4. rawDocument.projectId == projectId, 5. rawDocument.taskId == taskId
        for (String rawDocId : rawDocumentIds) {
            RawDocument doc = rawDocumentRepository.findById(rawDocId)
                    .orElseThrow(() -> new ResourceNotFoundException("RawDocument not found: " + rawDocId));
            if (!String.valueOf(projectId).equals(doc.getProjectId())) {
                throw new com.apms.common.exception.BusinessValidationException("RawDocument does not belong to this project: " + rawDocId);
            }
            if (doc.getTaskId() == null || !String.valueOf(taskId).equals(doc.getTaskId())) {
                throw new com.apms.common.exception.BusinessValidationException("RawDocument does not belong to this task: " + rawDocId);
            }
            if (doc.getSource() != null && SOURCE_TYPE_PARTNER_CONTRACT.equalsIgnoreCase(doc.getSource().getType())) {
                throw new com.apms.common.exception.BusinessValidationException("Partner contract documents cannot be used for AI company extraction: " + rawDocId);
            }
            if (Boolean.TRUE.equals(doc.getIsHidden())) {
                throw new com.apms.common.exception.BusinessValidationException("Hidden documents cannot be used for AI extraction: " + rawDocId);
            }
        }

        // ─── Company consistency validation ───
        if (rawDocumentIds.size() > 1) {
            List<RawDocument> documentsForValidation = new ArrayList<>();
            for (String rawDocId : rawDocumentIds) {
                rawDocumentRepository.findById(rawDocId).ifPresent(documentsForValidation::add);
            }

            // Get target company context from project/task
            String targetCompanyName = null;
            String targetTaxCode = null;
            if (org.springframework.util.StringUtils.hasText(task.getTargetCompanyProfileId())) {
                // Try to resolve target company name from CompanyProfile
                var profileOpt = companyProfileRepository.findByCompanyId(task.getTargetCompanyProfileId())
                        .or(() -> companyProfileRepository.findById(task.getTargetCompanyProfileId()));
                if (profileOpt.isPresent()) {
                    var profile = profileOpt.get();
                    if (profile.getIdentity() != null) {
                        targetCompanyName = profile.getIdentity().getLegalName();
                        targetTaxCode = profile.getIdentity().getTaxCode();
                    }
                }
            }
            if (targetCompanyName == null && task.getProject() != null) {
                targetCompanyName = task.getProject().getTargetCompanyName();
            }

            com.apms.domain.ai.dto.DocumentCompanyValidationResult validationResult =
                    companyConsistencyValidator.validate(documentsForValidation, targetCompanyName, targetTaxCode);

            if (!validationResult.isValid()) {
                if ("DOCUMENT_TARGET_COMPANY_MISMATCH".equals(validationResult.getErrorCode())
                        && task.getTaskType() == com.apms.common.enums.TaskType.COMPANY_DATA_PREPARATION) {
                    log.info("Document target company mismatch detected for task {}. Continuing extraction as allowed for COMPANY_DATA_PREPARATION.", taskId);
                } else {
                    log.warn("Document company validation failed for task {}: {}", taskId, validationResult.getMessage());

                    Map<String, Object> details = new java.util.LinkedHashMap<>();
                    if (validationResult.getDocuments() != null) {
                        details.put("documents", validationResult.getDocuments());
                    }
                    if (validationResult.getConflicts() != null) {
                        details.put("conflicts", validationResult.getConflicts());
                    }
                    if (validationResult.getAmbiguousDocuments() != null && !validationResult.getAmbiguousDocuments().isEmpty()) {
                        details.put("ambiguousDocuments", validationResult.getAmbiguousDocuments());
                    }

                    throw new com.apms.common.exception.BusinessValidationException(
                            validationResult.getErrorCode(),
                            validationResult.getMessage(),
                            details);
                }
            }
            log.info("Document company validation passed for task {}. Resolved: {}", taskId, validationResult.getResolvedCompanyName());
        }
    }

    @Async
    public void processExtraction(String jobId, Long projectId, Long taskId, List<String> rawDocumentIds, Long creatorId) {
        AiExtractionJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Job not found: " + jobId));

        if (isJobCancelled(jobId)) {
            log.info("Extraction job {} was cancelled before processing started", jobId);
            return;
        }

        try {
            job.setStatus(AiExtractionJobStatus.PROCESSING);
            job.setStage(AiExtractionJobStage.PREPARING);
            job.setStartedAt(LocalDateTime.now());
            jobRepository.save(job);

            log.info("Starting multi-document extraction for task {}, documents: {}", taskId, rawDocumentIds);

            // 1. Load and concatenate texts
            StringBuilder combinedText = new StringBuilder();
            int processed = 0;
            for (String rawDocId : rawDocumentIds) {
                if (isJobCancelled(jobId)) {
                    log.info("Extraction job {} was cancelled during document preparation", jobId);
                    return;
                }

                RawDocument doc = rawDocumentRepository.findById(rawDocId)
                        .orElseThrow(() -> new ResourceNotFoundException("RawDocument not found: " + rawDocId));
                String text = extractTextFromDocument(doc);
                
                combinedText.append("=== DOCUMENT ").append(rawDocId).append(" ===\n");
                combinedText.append("sourceDocumentId: ").append(rawDocId).append("\n");
                String fileName = doc.getSource() != null && doc.getSource().getFileName() != null ? doc.getSource().getFileName() : "Unknown";
                combinedText.append("fileName: ").append(fileName).append("\n");
                combinedText.append("content:\n").append(text).append("\n\n");
                
                processed++;
                job.setProcessedDocuments(processed);
                job.setProgress((int) (((float) processed / rawDocumentIds.size()) * 30));
                jobRepository.save(job);
            }

            if (isJobCancelled(jobId)) {
                log.info("Extraction job {} was cancelled before AI extraction", jobId);
                return;
            }

            job.setStage(AiExtractionJobStage.EXTRACTING);
            job.setProgress(30);
            jobRepository.save(job);

            // 2. Call Gemini
            RawExtractionOutput output = geminiProvider.extract(combinedText.toString());

            if (isJobCancelled(jobId)) {
                log.info("Extraction job {} was cancelled after AI extraction", jobId);
                return;
            }

            Project project = projectRepository.findById(projectId)
                    .orElseThrow(() -> new ResourceNotFoundException("Project not found: " + projectId));

            // Capture raw detected company name BEFORE applying project target overrides
            String detectedCompanyName = null;
            if (output != null && output.getExtractedData() != null) {
                if (org.springframework.util.StringUtils.hasText(output.getExtractedData().getLegalName())) {
                    detectedCompanyName = output.getExtractedData().getLegalName().trim();
                } else if (org.springframework.util.StringUtils.hasText(output.getExtractedData().getTradeName())) {
                    detectedCompanyName = output.getExtractedData().getTradeName().trim();
                }
            }

            if (!org.springframework.util.StringUtils.hasText(detectedCompanyName)) {
                for (String rawDocId : rawDocumentIds) {
                    RawDocument doc = rawDocumentRepository.findById(rawDocId).orElse(null);
                    if (doc != null) {
                        com.apms.domain.ai.dto.DocumentCompanyIdentity docIdentity = identityDetectionService.detectIdentity(doc);
                        if (docIdentity != null && org.springframework.util.StringUtils.hasText(docIdentity.getLegalName())) {
                            detectedCompanyName = docIdentity.getLegalName().trim();
                            break;
                        }
                    }
                }
            }

            String targetCompanyName = project.getTargetCompanyName();
            com.apms.domain.financial.DocumentCompanyValidationStatus companyMatchStatus =
                    companyMatcher.evaluateCompanyMatch(detectedCompanyName, targetCompanyName);

            boolean companyMatchConfirmed = (companyMatchStatus == com.apms.domain.financial.DocumentCompanyValidationStatus.MATCH);

            applyProjectControlledIdentity(output, project);
            removeAnalysisExtractionFields(output);

            job.setStage(AiExtractionJobStage.MERGING);
            job.setProgress(70);
            jobRepository.save(job);

            // 3. Map field evidence
            Map<String, List<CompanyCandidate.DocumentEvidence>> fieldEvidence = new HashMap<>();
            if (output.getFieldResults() != null) {
                output.getFieldResults().forEach((fieldName, fieldResult) -> {
                    if (fieldResult.getSourceDocumentIds() != null && !fieldResult.getSourceDocumentIds().isEmpty()) {
                        List<CompanyCandidate.DocumentEvidence> evidences = new ArrayList<>();
                        for (String docId : fieldResult.getSourceDocumentIds()) {
                            rawDocumentRepository.findById(docId).ifPresent(doc -> {
                                String fName = doc.getSource() != null && doc.getSource().getFileName() != null ? doc.getSource().getFileName() : "Unknown";
                                evidences.add(CompanyCandidate.DocumentEvidence.builder()
                                        .rawDocumentId(docId)
                                        .fileName(fName)
                                        .page(fieldResult.getPageNumber())
                                        .evidenceText(evidenceTextForSource(fieldResult.getEvidenceText(), fName, docId))
                                        .confidence(fieldResult.getConfidence())
                                        .build());
                            });
                        }
                        fieldEvidence.put(fieldName, evidences);
                    }
                });
            }

            // 3b. Validate fields and compute quality metrics
            qualityService.validateExtraction(output.getFieldResults());
            ExtractionQualityMetrics qualityMetrics = qualityService.computeMetrics(output.getFieldResults());
            ExtractionQualityStatus qualityStatus = qualityService.determineOverallStatus(qualityMetrics);

            log.info("Extraction quality for task {}: totalFields={}, fieldsWithValue={}, avgConfidence={}",
                    taskId,
                    qualityMetrics.getTotalFields(),
                    qualityMetrics.getFieldsWithValue(),
                    qualityMetrics.getAverageConfidence());

            // 3c. Initialize staffReviewedValue = value for each field result, doing deep copy for lists
            if (output.getFieldResults() != null) {
                output.getFieldResults().values().forEach(result -> {
                    if (result.getStaffReviewedValue() == null && result.getValue() != null) {
                        Object val = result.getValue();
                        if (val instanceof java.util.List) {
                            result.setStaffReviewedValue(new java.util.ArrayList<>((java.util.List<?>) val));
                        } else if (val instanceof java.util.Map) {
                            result.setStaffReviewedValue(new java.util.LinkedHashMap<>((java.util.Map<?, ?>) val));
                        } else {
                            result.setStaffReviewedValue(val);
                        }
                    }
                });
            }

            if (isJobCancelled(jobId)) {
                log.info("Extraction job {} was cancelled before creating candidate", jobId);
                return;
            }

            job.setStage(AiExtractionJobStage.CREATING_CANDIDATE);
            job.setProgress(90);
            jobRepository.save(job);

            // 4. Create Candidate (flat keys stored in MongoDB — frontend handles key mapping)
            LocalDateTime now = LocalDateTime.now();
            int nextSeq = candidateService.getNextDraftSequence(taskId);
            String draftName = "Draft " + nextSeq;

            CompanyCandidate candidate = CompanyCandidate.builder()
                    .projectId(String.valueOf(projectId))
                    .taskId(taskId)
                    .draftName(draftName)
                    .draftSequence(nextSeq)
                    .sourceDocumentIds(rawDocumentIds)
                    .status(CandidateStatus.DRAFT)
                    .companyMatchStatus(companyMatchStatus)
                    .companyMatchConfirmed(companyMatchConfirmed)
                    .companyMatchConfirmedBy(null)
                    .companyMatchConfirmedAt(null)
                    .detectedCompanyName(detectedCompanyName)
                    .identity(mapIdentity(output.getExtractedData(), project))
                    .business(mapBusiness(output.getExtractedData()))
                    .contact(mapContact(output.getExtractedData()))
                    .companySize(mapCompanySize(output.getExtractedData()))
                    .insights(null)
                    .financial(null)
                    .market(null)
                    .innovation(null)
                    .risk(null)
                    .compliance(null)
                    .fieldEvidence(encodeFieldEvidence(fieldEvidence))
                    .fieldResults(encodeFieldResults(output.getFieldResults()))
                    .qualityStatus(qualityStatus)
                    .qualityMetrics(qualityMetrics)
                    .rawAiOutput(output.getRawAiOutput())
                    .metadata(CompanyCandidate.Metadata.builder()
                            .createdBy(String.valueOf(creatorId))
                            .createdAt(now)
                            .lastModifiedBy(String.valueOf(creatorId))
                            .updatedAt(now)
                            .build())
                    .extractionSource(CompanyCandidate.ExtractionSource.builder()
                            .extractionMethod("SPRING_AI_MULTI_DOC")
                            .build())
                    .aiMetadata(CompanyCandidate.AiMetadata.builder()
                            .modelUsed(geminiModel)
                            .build())
                    .build();

            if (isJobCancelled(jobId)) {
                log.info("Extraction job {} was cancelled before candidate save", jobId);
                return;
            }

            CompanyCandidate saved = candidateRepository.save(candidate);
            log.info("Created Candidate {} from multi-document extraction", saved.getId());

            job.setCandidateId(saved.getId());
            job.setStatus(AiExtractionJobStatus.COMPLETED);
            job.setStage(AiExtractionJobStage.COMPLETED);
            job.setProgress(100);
            job.setCompletedAt(LocalDateTime.now());
            jobRepository.save(job);

        } catch (Exception e) {
            log.error("Failed async extraction for job {}", jobId, e);
            
            // Check if failure is due to application context closing (e.g. during restart)
            boolean isContextClosed = e instanceof IllegalStateException && 
                                    e.getMessage() != null && 
                                    (e.getMessage().contains("has been closed already") || 
                                     e.getMessage().contains("ApplicationContext"));
                                     
            if (isContextClosed || Thread.currentThread().isInterrupted()) {
                log.warn("Extraction job {} interrupted due to application shutdown.", jobId);
                return; // Do not attempt to save, the DB context/datasource might already be closed
            }
            
            try {
                AiExtractionJob latestJob = jobRepository.findById(jobId).orElse(job);
                if (latestJob.getStatus() == AiExtractionJobStatus.CANCELLED) {
                    log.info("Extraction job {} was cancelled, skipping failure status update", jobId);
                    return;
                }

                job.setStatus(AiExtractionJobStatus.FAILED);
                job.setStage(AiExtractionJobStage.FAILED);
                 
                if (e.getMessage() != null && e.getMessage().contains("GEMINI_JSON_PARSE_FAILED")) {
                    job.setErrorMessage("AI returned an invalid structured response.");
                } else {
                    job.setErrorMessage(e.getMessage() != null ? e.getMessage() : "Unknown error");
                }
                
                job.setCompletedAt(LocalDateTime.now());
                jobRepository.save(job);
            } catch (Exception secondaryError) {
                log.error("Failed to update job status after extraction failure. Job: {}", jobId, secondaryError);
            }
        }
    }

    public AiExtractionJob getExtractionJob(String jobId) {
        return jobRepository.findById(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Job not found: " + jobId));
    }

    public AiExtractionJob getExtractionJob(Long projectId, Long taskId, String jobId, Long currentUserId) {
        validateTaskAccess(projectId, taskId, currentUserId);
        AiExtractionJob job = getExtractionJob(jobId);
        if (!job.getTaskId().equals(taskId)) {
            throw new com.apms.common.exception.BusinessValidationException("Job does not belong to task: " + taskId);
        }
        return job;
    }

    public AiExtractionJob getLatestExtractionJob(Long projectId, Long taskId, Long currentUserId) {
        validateTaskAccess(projectId, taskId, currentUserId);
        return jobRepository.findFirstByTaskIdOrderByCreatedAtDesc(taskId).orElse(null);
    }

    public AiExtractionJob getActiveExtractionJob(Long projectId, Long taskId, Long currentUserId) {
        validateTaskAccess(projectId, taskId, currentUserId);
        return jobRepository.findFirstByTaskIdAndStatusInOrderByCreatedAtDesc(
                taskId, List.of(AiExtractionJobStatus.PENDING, AiExtractionJobStatus.PROCESSING))
                .orElse(null);
    }

    public AiExtractionJob cancelExtractionJob(Long projectId, Long taskId, String jobId, Long currentUserId) {
        validateTaskAccess(projectId, taskId, currentUserId);
        AiExtractionJob job = getExtractionJob(jobId);
        if (!job.getTaskId().equals(taskId)) {
            throw new com.apms.common.exception.BusinessValidationException("Job does not belong to task: " + taskId);
        }
        if (job.getStatus() == AiExtractionJobStatus.COMPLETED) {
            throw new com.apms.common.exception.BusinessValidationException("Cannot cancel a completed extraction job");
        }
        if (job.getStatus() == AiExtractionJobStatus.FAILED) {
            throw new com.apms.common.exception.BusinessValidationException("Cannot cancel a failed extraction job");
        }
        if (job.getStatus() == AiExtractionJobStatus.CANCELLED) {
            return job;
        }

        job.setStatus(AiExtractionJobStatus.CANCELLED);
        job.setStage(AiExtractionJobStage.CANCELLED);
        job.setCancelledAt(LocalDateTime.now());
        job.setCancelledBy(currentUserId);
        log.info("Extraction job {} cancelled by user {}", jobId, currentUserId);
        return jobRepository.save(job);
    }

    private boolean isJobCancelled(String jobId) {
        return jobRepository.findById(jobId)
                .map(j -> j.getStatus() == AiExtractionJobStatus.CANCELLED)
                .orElse(false);
    }

    public void validateTaskAccess(Long projectId, Long taskId, Long currentUserId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found: " + projectId));
        com.apms.domain.project.ProjectTask task = projectTaskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found: " + taskId));
        if (!task.getProject().getId().equals(projectId)) {
            throw new com.apms.common.exception.BusinessValidationException("Task does not belong to the specified project");
        }
        if (task.getTaskType() != com.apms.common.enums.TaskType.COMPANY_DATA_PREPARATION) {
            throw new com.apms.common.exception.BusinessValidationException("AI extraction is only allowed for COMPANY_DATA_PREPARATION research documents");
        }

        if (currentUserId != null) {
            boolean isMember = projectRepository.existsByIdAndMembersAccountId(projectId, currentUserId)
                    || projectRepository.existsByIdAndCreatedByAccountId(projectId, currentUserId);
            if (!isMember) {
                throw new AccessDeniedException("User is not a member of this project");
            }

            Account user = accountRepository.findById(currentUserId)
                    .orElseThrow(() -> new ResourceNotFoundException("Account not found"));
            boolean isManager = user.getRoles().stream().anyMatch(r -> r == com.apms.common.enums.SystemRole.BUSINESS_DEVELOPMENT_MANAGER);
            boolean isSystemAdmin = user.getRoles().stream().anyMatch(r -> r == com.apms.common.enums.SystemRole.SYSTEM_ADMIN);
            boolean isOwner = user.getRoles().stream().anyMatch(r -> r == com.apms.common.enums.SystemRole.BUSINESS_OWNER);

            if (!isManager && !isSystemAdmin && !isOwner) {
                if (task.getAssignedToAccount() == null || !task.getAssignedToAccount().getId().equals(currentUserId)) {
                    throw new AccessDeniedException("Staff can only access tasks assigned to them");
                }
            }
        }
    }

    private String evidenceTextForSource(String evidenceText, String fileName, String rawDocumentId) {
        if (evidenceText == null || evidenceText.isBlank()) {
            return evidenceText;
        }

        Pattern sourcePattern = Pattern.compile("\\[([^|\\]]+)\\s*\\|\\s*([^|\\]]+)(?:\\s*\\|\\s*([^\\]]+))?\\]\\s*([^\\[]+)");
        Matcher matcher = sourcePattern.matcher(evidenceText);
        List<String> matches = new ArrayList<>();
        while (matcher.find()) {
            String taggedFileName = matcher.group(1).trim();
            String taggedRawDocumentId = matcher.group(2).trim();
            String taggedPage = matcher.group(3) != null ? matcher.group(3).trim() : null;
            String quote = matcher.group(4).trim();
            
            if (taggedRawDocumentId.equals(rawDocumentId) || taggedFileName.equalsIgnoreCase(fileName)) {
                if (taggedPage != null) {
                    matches.add("[" + taggedPage + "] " + quote);
                } else {
                    matches.add(quote);
                }
            }
        }

        return matches.isEmpty() ? evidenceText : String.join("\n", matches);
    }

    private java.util.Map<String, java.util.List<CompanyCandidate.DocumentEvidence>> encodeFieldEvidence(
            java.util.Map<String, java.util.List<CompanyCandidate.DocumentEvidence>> fieldEvidence) {
        if (fieldEvidence == null) return null;
        java.util.Map<String, java.util.List<CompanyCandidate.DocumentEvidence>> encoded = new java.util.HashMap<>();
        fieldEvidence.forEach((flatKey, evidenceList) -> {
            String path = CandidateFieldRegistry.toPath(flatKey);
            encoded.put(FieldKeyCodec.encode(path), evidenceList);
        });
        return encoded;
    }

    private java.util.Map<String, com.apms.domain.ai.dto.ExtractionFieldResult> encodeFieldResults(
            java.util.Map<String, com.apms.domain.ai.dto.ExtractionFieldResult> fieldResults) {
        if (fieldResults == null) return null;
        java.util.Map<String, com.apms.domain.ai.dto.ExtractionFieldResult> encoded = new java.util.HashMap<>();
        fieldResults.forEach((flatKey, result) -> {
            String path = CandidateFieldRegistry.toPath(flatKey);
            result.setFieldName(path); // Update original fieldName to Canonical Domain Path
            encoded.put(FieldKeyCodec.encode(path), result);
        });
        return encoded;
    }

    private void applyProjectControlledIdentity(RawExtractionOutput output, Project project) {
        if (output == null || project == null) return;

        if (output.getExtractedData() == null) {
            output.setExtractedData(new com.apms.domain.ai.dto.ExtractedCompanyData());
        }
        output.getExtractedData().setLegalName(project.getTargetCompanyName());
        output.getExtractedData().setTaxCode(project.getTargetCompanyTaxCode());

        if (output.getFieldResults() == null) {
            output.setFieldResults(new java.util.HashMap<>());
        }
        output.getFieldResults().put("legalName",
                projectControlledField("legalName", project.getTargetCompanyName()));
        output.getFieldResults().put("taxCode",
                projectControlledField("taxCode", project.getTargetCompanyTaxCode()));
    }

    private com.apms.domain.ai.dto.ExtractionFieldResult projectControlledField(String fieldName, Object value) {
        return com.apms.domain.ai.dto.ExtractionFieldResult.builder()
                .fieldName(fieldName)
                .value(value)
                .normalizedValue(value)
                .confidence(1.0)
                .validationStatus(com.apms.domain.ai.dto.ExtractionValidationStatus.PASS)
                .validationMessages("Provided by manager at project creation.")
                .staffReviewStatus(com.apms.domain.ai.dto.StaffFieldReviewStatus.CONFIRMED)
                .staffReviewedValue(value)
                .managerReviewStatus(com.apms.domain.ai.dto.ExtractionReviewStatus.ACCEPTED)
                .build();
    }

    private void removeAnalysisExtractionFields(RawExtractionOutput output) {
        if (output == null) return;

        if (output.getExtractedData() != null) {
            output.getExtractedData().setStrengths(null);
            output.getExtractedData().setWeaknesses(null);
            output.getExtractedData().setOpportunities(null);
            output.getExtractedData().setThreats(null);
            output.getExtractedData().setFinancial(null);
            output.getExtractedData().setInnovation(null);
            output.getExtractedData().setMarket(null);
            output.getExtractedData().setRisk(null);
            output.getExtractedData().setCompliance(null);
        }

        if (output.getFieldResults() != null) {
            for (String field : java.util.List.of(
                    "strengths", "weaknesses", "opportunities", "threats",
                    "financial", "innovation", "market", "risk", "compliance")) {
                output.getFieldResults().remove(field);
            }
        }
    }

    private String extractTextFromDocument(RawDocument rawDocument) {
        String mimeType = rawDocument.getStorage() != null ? rawDocument.getStorage().getMimeType() : null;
        if (!"application/pdf".equals(mimeType)) {
            log.warn("Document {} is not a PDF, returning empty text for now.", rawDocument.getId());
            return "";
        }
        try {
            String path = rawDocument.getStorage() != null ? rawDocument.getStorage().getPath() : null;
            if (path == null) return "";
            File file = Paths.get(uploadDir, path).toFile();
            if (!file.exists()) {
                log.warn("File {} not found", file.getAbsolutePath());
                return "";
            }
            try (PDDocument document = Loader.loadPDF(file)) {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setSortByPosition(true);
                StringBuilder sb = new StringBuilder();
                int totalPages = document.getNumberOfPages();
                for (int i = 1; i <= totalPages; i++) {
                    stripper.setStartPage(i);
                    stripper.setEndPage(i);
                    sb.append("\n--- Page ").append(i).append(" ---\n");
                    sb.append(stripper.getText(document));
                }
                return sb.toString();
            }
        } catch (Exception e) {
            log.error("Failed to extract text from RawDocument {}", rawDocument.getId(), e);
            return "";
        }
    }

    private CompanyCandidate.Identity mapIdentity(com.apms.domain.ai.dto.ExtractedCompanyData d, Project project) {
        String legalName = project != null ? project.getTargetCompanyName() : null;
        String taxCode = project != null ? project.getTargetCompanyTaxCode() : null;
        if (d == null) {
            return CompanyCandidate.Identity.builder()
                    .legalName(legalName)
                    .taxCode(taxCode)
                    .build();
        }
        return CompanyCandidate.Identity.builder()
                .legalName(legalName)
                .tradeName(d.getTradeName())
                .taxCode(taxCode)
                .build();
    }

    private CompanyCandidate.Business mapBusiness(com.apms.domain.ai.dto.ExtractedCompanyData d) {
        if (d == null) return null;
        List<CompanyCandidate.Product> products = null;
        if (d.getProducts() != null) {
            Set<String> seen = new java.util.HashSet<>();
            products = d.getProducts().stream()
                    .map(com.apms.domain.ai.dto.ExtractedCompanyData.Product::getName)
                    .filter(java.util.Objects::nonNull)
                    .map(String::trim)
                    .filter(n -> !n.isEmpty())
                    .filter(n -> seen.add(n.toLowerCase(java.util.Locale.ROOT)))
                    .map(n -> CompanyCandidate.Product.builder().name(n).build())
                    .toList();
        }
        return CompanyCandidate.Business.builder()
                .industries(d.getIndustries())
                .businessModel(d.getBusinessModel())
                .products(products)
                .markets(d.getMarkets())
                .targetCustomers(d.getTargetCustomers())
                .build();
    }

    private CompanyCandidate.Contact mapContact(com.apms.domain.ai.dto.ExtractedCompanyData d) {
        if (d == null) return null;
        List<CompanyCandidate.Address> addresses = null;
        if (d.getAddresses() != null && !d.getAddresses().isEmpty()) {
            addresses = CompanyCandidate.Contact.toAddressObjects(d.getAddresses());
        } else if (d.getAddress() != null && !d.getAddress().trim().isEmpty()) {
            addresses = CompanyCandidate.Contact.toAddressObjects(List.of(d.getAddress().trim()));
        }
        return CompanyCandidate.Contact.builder()
                .website(d.getWebsite())
                .emails(d.getEmail())
                .phones(d.getPhone())
                .addresses(addresses)
                .build();
    }

    private CompanyCandidate.CompanySize mapCompanySize(com.apms.domain.ai.dto.ExtractedCompanyData d) {
        if (d == null) return null;
        return CompanyCandidate.CompanySize.builder()
                .employeeTier(d.getEmployeeTier())
                .build();
    }

    private CompanyCandidate.Insights mapInsights(com.apms.domain.ai.dto.ExtractedCompanyData d) {
        if (d == null) return null;
        return CompanyCandidate.Insights.builder()
                .strengths(d.getStrengths())
                .weaknesses(d.getWeaknesses())
                .opportunities(d.getOpportunities())
                .threats(d.getThreats())
                .build();
    }
}
