package com.apms.domain.ai.service;

import com.apms.common.enums.CandidateStatus;
import com.apms.common.exception.ResourceNotFoundException;
import com.apms.domain.ai.dto.RawExtractionOutput;
import com.apms.domain.ai.dto.ExtractionFieldResult;
import com.apms.domain.ai.dto.ExtractionQualityMetrics;
import com.apms.domain.ai.dto.ExtractionQualityStatus;
import com.apms.domain.ai.service.provider.GeminiExtractionProvider;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
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

    @Value("${app.storage.upload-dir:uploads/}")
    private String uploadDir;

    @Value("${app.ai.gemini.model:gemini-3.6-flash}")
    private String geminiModel;

    @Transactional
    public String startExtractionJob(Long projectId, Long taskId, List<String> rawDocumentIds, Long creatorId) {
        validateResearchExtractionRequest(projectId, taskId, rawDocumentIds);

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

    private void validateResearchExtractionRequest(Long projectId, Long taskId, List<String> rawDocumentIds) {
        com.apms.domain.project.ProjectTask task = projectTaskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found: " + taskId));
        if (!task.getProject().getId().equals(projectId)) {
            throw new com.apms.common.exception.BusinessValidationException("Task does not belong to the specified project");
        }
        if (task.getTaskType() != com.apms.common.enums.TaskType.COMPANY_DATA_PREPARATION) {
            throw new com.apms.common.exception.BusinessValidationException("AI extraction is only allowed for COMPANY_DATA_PREPARATION research documents");
        }

        for (String rawDocId : rawDocumentIds) {
            RawDocument doc = rawDocumentRepository.findById(rawDocId)
                    .orElseThrow(() -> new ResourceNotFoundException("RawDocument not found: " + rawDocId));
            if (!String.valueOf(projectId).equals(doc.getProjectId())) {
                throw new com.apms.common.exception.BusinessValidationException("RawDocument does not belong to this project: " + rawDocId);
            }
            if (doc.getSource() != null && SOURCE_TYPE_PARTNER_CONTRACT.equalsIgnoreCase(doc.getSource().getType())) {
                throw new com.apms.common.exception.BusinessValidationException("Partner contract documents cannot be used for AI company extraction: " + rawDocId);
            }
            if (Boolean.TRUE.equals(doc.getIsHidden())) {
                throw new com.apms.common.exception.BusinessValidationException("Hidden documents cannot be used for AI extraction: " + rawDocId);
            }
        }
    }

    @Async
    public void processExtraction(String jobId, Long projectId, Long taskId, List<String> rawDocumentIds, Long creatorId) {
        AiExtractionJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Job not found: " + jobId));

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

            job.setStage(AiExtractionJobStage.EXTRACTING);
            job.setProgress(30);
            jobRepository.save(job);

            // 2. Call Gemini
            RawExtractionOutput output = geminiProvider.extract(combinedText.toString());

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

            job.setStage(AiExtractionJobStage.CREATING_CANDIDATE);
            job.setProgress(90);
            jobRepository.save(job);

            // 4. Create Candidate (flat keys stored in MongoDB — frontend handles key mapping)
            LocalDateTime now = LocalDateTime.now();
            CompanyCandidate candidate = CompanyCandidate.builder()
                    .projectId(String.valueOf(projectId))
                    .taskId(taskId)
                    .sourceDocumentIds(rawDocumentIds)
                    .status(CandidateStatus.DRAFT)
                    .identity(mapIdentity(output.getExtractedData()))
                    .business(mapBusiness(output.getExtractedData()))
                    .contact(mapContact(output.getExtractedData()))
                    .companySize(mapCompanySize(output.getExtractedData()))
                    .insights(mapInsights(output.getExtractedData()))
                    .financial(output.getExtractedData().getFinancial())
                    .market(output.getExtractedData().getMarket())
                    .innovation(output.getExtractedData().getInnovation())
                    .risk(output.getExtractedData().getRisk())
                    .compliance(output.getExtractedData().getCompliance())
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

    private String evidenceTextForSource(String evidenceText, String fileName, String rawDocumentId) {
        if (evidenceText == null || evidenceText.isBlank()) {
            return evidenceText;
        }

        Pattern sourcePattern = Pattern.compile("\\[([^|\\]]+)\\s*\\|\\s*([^\\]]+)\\]\\s*([^\\[]+)");
        Matcher matcher = sourcePattern.matcher(evidenceText);
        List<String> matches = new ArrayList<>();
        while (matcher.find()) {
            String taggedFileName = matcher.group(1).trim();
            String taggedRawDocumentId = matcher.group(2).trim();
            if (taggedRawDocumentId.equals(rawDocumentId) || taggedFileName.equalsIgnoreCase(fileName)) {
                matches.add(matcher.group(3).trim());
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
                return stripper.getText(document);
            }
        } catch (Exception e) {
            log.error("Failed to extract text from RawDocument {}", rawDocument.getId(), e);
            return "";
        }
    }

    private CompanyCandidate.Identity mapIdentity(com.apms.domain.ai.dto.ExtractedCompanyData d) {
        if (d == null) return null;
        return CompanyCandidate.Identity.builder()
                .legalName(d.getLegalName())
                .tradeName(d.getTradeName())
                .taxCode(d.getTaxCode())
                .build();
    }

    private CompanyCandidate.Business mapBusiness(com.apms.domain.ai.dto.ExtractedCompanyData d) {
        if (d == null) return null;
        List<CompanyCandidate.Product> products = null;
        if (d.getProducts() != null) {
            products = d.getProducts().stream().map(p -> CompanyCandidate.Product.builder()
                    .name(p.getName()).category(p.getCategory()).description(p.getDescription()).build()
            ).toList();
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
        return CompanyCandidate.Contact.builder()
                .website(d.getWebsite())
                .emails(d.getEmail())
                .phones(d.getPhone())
                .addresses(d.getAddress() != null ? List.of(CompanyCandidate.Address.builder().fullAddress(d.getAddress()).build()) : null)
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
