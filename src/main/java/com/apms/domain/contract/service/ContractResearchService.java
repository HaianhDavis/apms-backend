package com.apms.domain.contract.service;

import com.apms.common.enums.AuditAction;
import com.apms.common.enums.SubmissionStatus;
import com.apms.common.enums.SubmissionType;
import com.apms.common.enums.TaskStatus;
import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.contract.dto.*;
import com.apms.domain.contract.dto.ai.AiContractClassificationCandidate;
import com.apms.domain.contract.dto.ai.AiContractExtractionCandidate;
import com.apms.domain.contract.enums.*;
import com.apms.domain.contract.model.*;
import com.apms.domain.contract.repository.mongo.ContractResearchRepository;
import com.apms.domain.document.ImportJob;
import com.apms.domain.document.RawDocument;
import com.apms.domain.document.repository.mongo.RawDocumentRepository;
import com.apms.domain.document.repository.sql.ImportJobRepository;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import com.apms.domain.project.Project;
import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.ProjectTaskSubmission;
import com.apms.domain.project.repository.sql.ProjectTaskRepository;
import com.apms.domain.project.repository.sql.ProjectTaskSubmissionRepository;
import com.apms.domain.user.Account;
import com.apms.domain.user.repository.sql.UserProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ContractResearchService {

    private final ContractResearchRepository contractResearchRepository;
    private final RawDocumentRepository rawDocumentRepository;
    private final ImportJobRepository importJobRepository;
    private final ProjectTaskRepository projectTaskRepository;
    private final ProjectTaskSubmissionRepository projectTaskSubmissionRepository;
    private final CompanyProfileRepository companyProfileRepository;
    private final UserProfileRepository userProfileRepository;
    private final com.apms.domain.user.repository.sql.AccountRepository accountRepository;
    private final ContractExtractionService extractionService;
    private final ContractExtractionNormalizer normalizer;
    private final ContractCompanyMatcher companyMatcher;
    private final AuditLogService auditLogService;
    private final com.apms.domain.document.service.DocumentService documentService;

    @Autowired
    @Lazy
    private ContractResearchService self;

    @Value("${app.contract.extraction.stale-timeout-minutes:10}")
    private int staleTimeoutMinutes;

    private static final int PROGRESS_QUEUED = 5;
    private static final int PROGRESS_PARSING = 15;
    private static final int PROGRESS_CLASSIFYING = 30;
    private static final int PROGRESS_EXTRACTING = 55;
    private static final int PROGRESS_VALIDATING = 80;
    private static final int PROGRESS_SAVING = 95;
    private static final int PROGRESS_COMPLETED = 100;

    @Transactional
    public Optional<ContractResearchResponse> getResearch(Long projectId, Long taskId) {
        ContractResearch research = contractResearchRepository.findByTaskId(taskId).orElse(null);
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
            research = ContractResearch.builder()
                    .taskId(taskId)
                    .projectId(projectId)
                    .companyProfileId(targetProfileId)
                    .status(ContractResearchStatus.DRAFT)
                    .contracts(new ArrayList<>())
                    .build();
            research = contractResearchRepository.save(research);
        } else {
            if (research.getCompanyProfileId() == null && targetProfileId != null) {
                research.setCompanyProfileId(targetProfileId);
                research = contractResearchRepository.save(research);
            }
            recoverStaleExtractions(research);
            syncTaskAndResearchStatusIfComplete(research);
        }

        return Optional.of(toResponse(research));
    }

    private void recoverStaleExtractions(ContractResearch research) {
        if (research.getContracts() == null) return;
        boolean modified = false;
        LocalDateTime now = LocalDateTime.now();

        for (ContractEntry contract : research.getContracts()) {
            if (contract.getExtractionStatus() == ContractExtractionStatus.PROCESSING
                    && contract.getExtractionStartedAt() != null) {
                long minutesRunning = Duration.between(contract.getExtractionStartedAt(), now).toMinutes();
                if (minutesRunning >= staleTimeoutMinutes) {
                    log.warn("Recovering stale extraction for contract {} (running for {} mins)", contract.getId(), minutesRunning);
                    contract.setExtractionStatus(ContractExtractionStatus.FAILED);
                    contract.setExtractionErrorCode("EXTRACTION_TIMEOUT");
                    contract.setExtractionErrorMessage("Extraction timed out after " + staleTimeoutMinutes + " minutes. Please retry.");
                    contract.setExtractionCompletedAt(now);
                    contract.setExtractionProgress(0);
                    modified = true;
                }
            }
        }
        if (modified) {
            contractResearchRepository.save(research);
        }
    }

    private boolean requiresSourceDocument(ContractDataEntryMethod method) {
        return method == ContractDataEntryMethod.AI_EXTRACTION;
    }

    @Transactional
    public ContractResearchResponse createContractEntry(Long taskId, CreateContractEntryRequest req, Long userId) {
        ContractResearch research = getOrCreateResearchEntity(taskId);

        ContractDataEntryMethod method = req.getDataEntryMethod() != null
                ? req.getDataEntryMethod()
                : ContractDataEntryMethod.AI_EXTRACTION;

        String docId = null;
        String docName = null;

        if (requiresSourceDocument(method)) {
            if (req.getDocumentId() == null || req.getDocumentId().trim().isEmpty()) {
                throw new BusinessValidationException("DOCUMENT_REQUIRED", "Document ID is required for AI extraction contracts.");
            }

            RawDocument doc = rawDocumentRepository.findById(req.getDocumentId().trim())
                    .orElseGet(() -> {
                        try {
                            Long jobId = Long.parseLong(req.getDocumentId().trim());
                            ImportJob job = importJobRepository.findById(jobId).orElse(null);
                            if (job != null && job.getRawDocumentId() != null) {
                                return rawDocumentRepository.findById(job.getRawDocumentId()).orElse(null);
                            }
                        } catch (Exception ignored) {}
                        return null;
                    });

            if (doc == null) {
                throw new BusinessValidationException("DOCUMENT_NOT_FOUND", "RawDocument not found: " + req.getDocumentId());
            }

            docId = doc.getId();
            docName = (doc.getSource() != null && doc.getSource().getFileName() != null)
                    ? doc.getSource().getFileName()
                    : "Contract Document.pdf";
        } else {
            // MANUAL contract: document is optional
            if (StringUtils.hasText(req.getDocumentId())) {
                RawDocument doc = rawDocumentRepository.findById(req.getDocumentId().trim())
                        .orElseGet(() -> {
                            try {
                                Long jobId = Long.parseLong(req.getDocumentId().trim());
                                ImportJob job = importJobRepository.findById(jobId).orElse(null);
                                if (job != null && job.getRawDocumentId() != null) {
                                    return rawDocumentRepository.findById(job.getRawDocumentId()).orElse(null);
                                }
                            } catch (Exception ignored) {}
                            return null;
                        });
                if (doc != null) {
                    docId = doc.getId();
                    docName = (doc.getSource() != null && doc.getSource().getFileName() != null)
                            ? doc.getSource().getFileName()
                            : "Contract Document.pdf";
                }
            }
        }

        LocalDate docDate = req.getDocumentDate();

        ContractEntry entry = ContractEntry.builder()
                .id(UUID.randomUUID().toString())
                .dataEntryMethod(method)
                .documentId(docId)
                .documentName(docName)
                .title(req.getTitle().trim())
                .documentDate(docDate)
                .declaredContractType(ContractType.COOPERATION_AGREEMENT)
                .confirmedContractType(ContractType.COOPERATION_AGREEMENT)
                .typeValidationStatus(TypeValidationStatus.MATCH)
                .companyMatchStatus(CompanyMatchStatus.UNKNOWN)
                .companyMatchConfirmed(method == ContractDataEntryMethod.MANUAL)
                .derivedContractStatus(ContractStatus.UNKNOWN)
                .extractionStatus(method == ContractDataEntryMethod.MANUAL ? ContractExtractionStatus.COMPLETED : ContractExtractionStatus.NOT_EXTRACTED)
                .reviewStatus(ContractEntryReviewStatus.DRAFT)
                .reviewHistory(new ArrayList<>())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        research.getContracts().add(entry);
        research = contractResearchRepository.save(research);

        auditLogService.log(userId, AuditAction.CONTRACT_RESEARCH_CREATED, "CONTRACT_ENTRY", entry.getId(),
                "Task " + taskId + ": Created contract entry " + entry.getTitle() + " (" + method + ")");

        return toResponse(research);
    }

    @Transactional
    public ContractResearchResponse updateContractEntry(Long taskId, String contractId, UpdateContractEntryRequest req, Long userId) {
        ContractResearch research = getResearchEntity(taskId);
        ContractEntry entry = findContractOrThrow(research, contractId);

        validateContractEditable(research, entry);

        entry.setTitle(req.getTitle().trim());
        entry.setDocumentDate(req.getDocumentDate());
        entry.setUpdatedAt(LocalDateTime.now());

        research = contractResearchRepository.save(research);
        return toResponse(research);
    }

    @Transactional
    public ContractResearchResponse saveManualContract(Long projectId, Long taskId, String contractId, SaveManualContractRequest req, Long userId) {
        ProjectTask task = projectTaskRepository.findWithProjectById(taskId)
                .orElseThrow(() -> new BusinessValidationException("TASK_NOT_FOUND", "Project task not found: " + taskId));
        if (task.getProject() == null || !projectId.equals(task.getProject().getId())) {
            throw new BusinessValidationException("PROJECT_TASK_MISMATCH", "Task " + taskId + " does not belong to project " + projectId);
        }

        ContractResearch research = getResearchEntity(taskId);
        ContractEntry entry = findContractOrThrow(research, contractId);

        // Correction 1: Enforce dataEntryMethod immutability (never convert AI to MANUAL or vice versa)
        if (entry.getDataEntryMethod() != ContractDataEntryMethod.MANUAL) {
            throw new BusinessValidationException("INVALID_DATA_ENTRY_METHOD",
                    "Manual contract endpoint can only modify MANUAL contracts.");
        }

        validateContractEditable(research, entry);

        if (req != null) {
            validateContractDates(req.getSigningDate(), req.getEffectiveDate(), req.getExpiryDate());

            if (StringUtils.hasText(req.getTitle())) {
                entry.setTitle(req.getTitle().trim());
            }
            if (req.getDocumentDate() != null) {
                entry.setDocumentDate(req.getDocumentDate());
            }

            CommonContractData common = entry.getCommonData();
            if (common == null) {
                common = CommonContractData.builder().build();
                entry.setCommonData(common);
            }

            common.setContractNumber(createManualStringField(req.getContractNumber()));
            common.setSigningDate(createManualLocalDateField(req.getSigningDate()));
            common.setEffectiveDate(createManualLocalDateField(req.getEffectiveDate()));
            common.setExpiryDate(createManualLocalDateField(req.getExpiryDate()));
            common.setTerm(createManualStringField(req.getTerm()));
            common.setGoverningLaw(createManualStringField(req.getGoverningLaw()));
            common.setPurpose(createManualStringField(req.getPurpose()));
            common.setContractValue(createManualContractValue(req.getContractValueAmount(), req.getContractValueCurrency(), req.getRawContractValueText()));

            // Correction 6: Preserve ContractParty Identity During Edits
            List<ContractParty> existingParties = common.getParties() != null ? common.getParties() : new ArrayList<>();
            Map<String, ContractParty> existingPartyMap = existingParties.stream()
                    .filter(p -> p != null && StringUtils.hasText(p.getId()))
                    .collect(Collectors.toMap(ContractParty::getId, p -> p, (a, b) -> a));

            List<ContractParty> updatedParties = new ArrayList<>();
            if (req.getParties() != null) {
                for (ManualContractPartyDto pDto : req.getParties()) {
                    if (pDto == null) continue;
                    boolean hasPartyData = StringUtils.hasText(pDto.getLegalName())
                            || StringUtils.hasText(pDto.getRole())
                            || StringUtils.hasText(pDto.getTaxCode())
                            || StringUtils.hasText(pDto.getRepresentative())
                            || StringUtils.hasText(pDto.getAddress());
                    if (!hasPartyData) continue;

                    String partyId;
                    if (StringUtils.hasText(pDto.getId()) && existingPartyMap.containsKey(pDto.getId().trim())) {
                        partyId = pDto.getId().trim();
                    } else if (StringUtils.hasText(pDto.getId())) {
                        partyId = pDto.getId().trim();
                    } else {
                        partyId = UUID.randomUUID().toString();
                    }

                    ContractParty party = existingPartyMap.getOrDefault(partyId, ContractParty.builder().id(partyId).build());
                    party.setId(partyId);
                    party.setLegalName(StringUtils.hasText(pDto.getLegalName()) ? pDto.getLegalName().trim() : null);
                    party.setRole(StringUtils.hasText(pDto.getRole()) ? pDto.getRole().trim() : null);
                    party.setTaxCode(StringUtils.hasText(pDto.getTaxCode()) ? pDto.getTaxCode().trim() : null);
                    party.setRepresentative(StringUtils.hasText(pDto.getRepresentative()) ? pDto.getRepresentative().trim() : null);
                    party.setAddress(StringUtils.hasText(pDto.getAddress()) ? pDto.getAddress().trim() : null);
                    party.setInputMethod(ContractFieldInputMethod.MANUAL);
                    party.setQualityStatus(ContractFieldQualityStatus.VALID);
                    party.setVerificationStatus(null);

                    updatedParties.add(party);
                }
            }
            common.setParties(updatedParties);

            if (normalizer != null) {
                LocalDate eff = common.getEffectiveDate() != null ? common.getEffectiveDate().getValue() : null;
                LocalDate exp = common.getExpiryDate() != null ? common.getExpiryDate().getValue() : null;
                ContractStatus status = normalizer.deriveContractStatus(eff, exp, false);
                entry.setDerivedContractStatus(status);
                entry.setStatusDerivedAt(LocalDateTime.now());
                entry.setStatusDerivationReason("Manual contract status derived from effective/expiry dates");
            }
        }

        entry.setUpdatedAt(LocalDateTime.now());
        research.setUpdatedAt(LocalDateTime.now());
        research = contractResearchRepository.save(research);

        auditLogService.log(userId, AuditAction.CONTRACT_RESEARCH_UPDATED, "CONTRACT_ENTRY", entry.getId(),
                "Task " + taskId + ": Saved manual contract data for " + entry.getTitle());

        return toResponse(research);
    }

    @Transactional
    public ContractResearchResponse deleteContractEntry(Long taskId, String contractId, Long userId) {
        ContractResearch research = getResearchEntity(taskId);
        ContractEntry entry = findContractOrThrow(research, contractId);

        if (entry.getReviewStatus() != ContractEntryReviewStatus.DRAFT) {
            throw new BusinessValidationException("CANNOT_DELETE_CONTRACT", "Only DRAFT contracts can be deleted.");
        }

        // Check if contract has ever appeared in any submission history
        List<ProjectTaskSubmission> historicalSubmissions = projectTaskSubmissionRepository.findByProjectTask_Id(taskId);
        for (ProjectTaskSubmission sub : historicalSubmissions) {
            if (sub.getTargetItemIdList().contains(contractId)) {
                throw new BusinessValidationException("CANNOT_DELETE_SUBMITTED_CONTRACT",
                        "Contract cannot be deleted because it exists in historical submissions. Deletion rejected to preserve audit history.");
            }
        }

        research.getContracts().removeIf(c -> c.getId().equals(contractId));
        research = contractResearchRepository.save(research);
        return toResponse(research);
    }

    @Transactional
    public ContractResearchResponse replaceContractFile(Long projectId, Long taskId, String contractId, MultipartFile file, Long userId) {
        ProjectTask task = projectTaskRepository.findWithProjectById(taskId)
                .orElseThrow(() -> new BusinessValidationException("TASK_NOT_FOUND", "Project task not found: " + taskId));
        if (task.getProject() == null || !projectId.equals(task.getProject().getId())) {
            throw new BusinessValidationException("PROJECT_TASK_MISMATCH", "Task " + taskId + " does not belong to project " + projectId);
        }

        ContractResearch research = getResearchEntity(taskId);
        ContractEntry entry = findContractOrThrow(research, contractId);

        if (research.getStatus() != null && research.getStatus() != ContractResearchStatus.DRAFT && research.getStatus() != ContractResearchStatus.CHANGES_REQUESTED) {
            throw new BusinessValidationException("RESEARCH_NOT_EDITABLE", "Cannot replace document in submitted or approved research");
        }

        if (entry.getReviewStatus() == ContractEntryReviewStatus.APPROVED) {
            throw new BusinessValidationException("CONTRACT_APPROVED", "Cannot replace document for an approved contract");
        }

        if (entry.getReviewStatus() == ContractEntryReviewStatus.PENDING_REVIEW) {
            throw new BusinessValidationException("CONTRACT_IN_REVIEW", "Contract is currently under Manager review and cannot be modified.");
        }

        if (entry.getExtractionStatus() == ContractExtractionStatus.PROCESSING) {
            throw new BusinessValidationException("EXTRACTION_IN_PROGRESS", "Cannot replace document while AI extraction is in progress");
        }

        boolean anyOtherExtracting = research.getContracts() != null && research.getContracts().stream()
                .anyMatch(c -> !c.getId().equals(contractId) && c.getExtractionStatus() == ContractExtractionStatus.PROCESSING);
        if (anyOtherExtracting) {
            throw new BusinessValidationException("EXTRACTION_IN_PROGRESS", "Một tài liệu khác đang được AI trích xuất. Vui lòng đợi hoàn tất trước khi thao tác tiếp.");
        }

        if (file == null || file.isEmpty()) {
            throw new BusinessValidationException("EMPTY_FILE", "Uploaded file cannot be empty");
        }
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !originalFilename.toLowerCase().endsWith(".pdf")) {
            throw new BusinessValidationException("INVALID_FILE_TYPE", "Only PDF documents are supported for contracts");
        }

        Long effectiveUserId = userId != null ? userId : 1L;

        // Upload and store new document via DocumentService
        com.apms.domain.document.dto.ImportJobResponse importJob = documentService.uploadDocument(projectId, taskId, file, effectiveUserId);
        String newDocumentId = importJob.getRawDocumentId();
        if (newDocumentId == null) {
            throw new BusinessValidationException("UPLOAD_FAILED", "Failed to obtain document ID for uploaded file");
        }

        // Update contract document reference
        entry.setDocumentId(newDocumentId);
        entry.setDocumentName(originalFilename);
        entry.setUpdatedAt(LocalDateTime.now());

        if (entry.getDataEntryMethod() == ContractDataEntryMethod.MANUAL) {
            // Manual contract: document is for reference only;
            // preserve all manual fields, extraction status (COMPLETED), and review status
        } else {
            // AI extraction contract: reset extraction state
            entry.setExtractionStatus(ContractExtractionStatus.NOT_EXTRACTED);
            entry.setExtractionStage(null);
            entry.setExtractionProgress(0);
            entry.setExtractionStartedAt(null);
            entry.setExtractionCompletedAt(null);
            entry.setExtractionErrorCode(null);
            entry.setExtractionErrorMessage(null);
            entry.setCommonData(null);
            entry.setCooperationAgreementData(null);
            entry.setPartnershipAgreementData(null);
            entry.setJointVentureAgreementData(null);
            entry.setBusinessCooperationContractData(null);
            entry.setDerivedContractStatus(ContractStatus.UNKNOWN);
            entry.setCompanyMatchConfirmed(false);
            entry.setCompanyMatchStatus(CompanyMatchStatus.UNKNOWN);
        }

        // Do NOT soft-delete old document to avoid cross-record document loss

        research = contractResearchRepository.save(research);

        auditLogService.log(effectiveUserId, AuditAction.UPLOAD_DOCUMENT, "CONTRACT_ENTRY", entry.getId(),
                "Task " + taskId + ": Replaced document for contract " + entry.getTitle() + " with " + originalFilename);

        return toResponse(research);
    }

    @Transactional
    public ContractResearchResponse extractContractEntry(Long taskId, String contractId, Long userId) {
        ContractResearch research = getResearchEntity(taskId);
        ContractEntry entry = findContractOrThrow(research, contractId);

        validateContractEditable(research, entry);

        if (entry.getDataEntryMethod() == ContractDataEntryMethod.MANUAL) {
            throw new BusinessValidationException("CANNOT_EXTRACT_MANUAL_CONTRACT", "Cannot run AI extraction on a Manual Entry contract.");
        }
        if (entry.getDocumentId() == null || entry.getDocumentId().isBlank()) {
            throw new BusinessValidationException("DOCUMENT_REQUIRED", "AI extraction requires a source document (PDF).");
        }

        if (entry.getExtractionStatus() == ContractExtractionStatus.PROCESSING) {
            throw new BusinessValidationException("EXTRACTION_IN_PROGRESS", "Extraction is already in progress for this contract.");
        }

        boolean anyExtracting = research.getContracts() != null && research.getContracts().stream()
                .anyMatch(c -> c.getExtractionStatus() == ContractExtractionStatus.PROCESSING);
        if (anyExtracting) {
            throw new BusinessValidationException("EXTRACTION_IN_PROGRESS", "Một tài liệu khác đang được AI trích xuất. Vui lòng đợi hoàn tất trước khi thao tác tiếp.");
        }

        entry.setExtractionStatus(ContractExtractionStatus.PROCESSING);
        entry.setExtractionStage(ContractExtractionStage.QUEUED);
        entry.setExtractionProgress(PROGRESS_QUEUED);
        entry.setExtractionStartedAt(LocalDateTime.now());
        entry.setExtractionCompletedAt(null);
        entry.setExtractionErrorCode(null);
        entry.setExtractionErrorMessage(null);
        entry.setUpdatedAt(LocalDateTime.now());

        contractResearchRepository.save(research);

        self.runAsyncExtraction(taskId, contractId, userId);

        return toResponse(research);
    }

    @Async("taskExecutor")
    public void runAsyncExtraction(Long taskId, String contractId, Long userId) {
        log.info("Starting async AI contract extraction for task {}, contract {}", taskId, contractId);
        try {
            ContractResearch research = getResearchEntity(taskId);
            ContractEntry entry = findContractOrThrow(research, contractId);
            final String docId = entry.getDocumentId();

            RawDocument doc = rawDocumentRepository.findById(docId)
                    .orElseThrow(() -> new BusinessValidationException("DOCUMENT_NOT_FOUND", "Document not found: " + docId));

            // Stage 1: Document Parsing
            updateProgress(taskId, contractId, ContractExtractionStage.PARSING_DOCUMENT, PROGRESS_PARSING);
            int totalPages = extractionService.getDocumentPageCount(doc);
            String docText = extractionService.parseDocumentText(doc);

            research = getResearchEntity(taskId);
            entry = findContractOrThrow(research, contractId);
            if (entry.getExtractionStatus() != ContractExtractionStatus.PROCESSING) {
                log.info("Async extraction for task {}, contract {} was canceled. Aborting job.", taskId, contractId);
                return;
            }

            ContractType confirmedType = ContractType.COOPERATION_AGREEMENT;
            entry.setDeclaredContractType(confirmedType);
            entry.setConfirmedContractType(confirmedType);
            entry.setTypeValidationStatus(TypeValidationStatus.CONFIRMED);
            contractResearchRepository.save(research);

            // Continue directly to Common Terms Extraction
            executeStage2Extraction(taskId, contractId, confirmedType, doc, totalPages, docText, userId);

        } catch (Exception e) {
            log.error("Async contract extraction failed for task {}, contract {}", taskId, contractId, e);
            handleExtractionFailure(taskId, contractId, e.getMessage());
        }
    }

    @Transactional
    public ContractResearchResponse resolveContractType(Long taskId, String contractId, ContractType confirmedType, Long userId) {
        ContractResearch research = getResearchEntity(taskId);
        ContractEntry entry = findContractOrThrow(research, contractId);

        validateContractEditable(research, entry);

        if (confirmedType == null || confirmedType == ContractType.UNKNOWN) {
            throw new BusinessValidationException("INVALID_CONFIRMED_TYPE", "Please select one of the 4 supported contract types.");
        }

        entry.setConfirmedContractType(confirmedType);
        entry.setTypeValidationStatus(TypeValidationStatus.CONFIRMED);
        entry.setTypeConfirmedBy(userId);
        entry.setTypeConfirmedAt(LocalDateTime.now());
        entry.setExtractionStatus(ContractExtractionStatus.PROCESSING);
        entry.setExtractionStage(ContractExtractionStage.EXTRACTING_FIELDS);
        entry.setExtractionProgress(PROGRESS_EXTRACTING);
        entry.setExtractionErrorMessage(null);
        entry.setUpdatedAt(LocalDateTime.now());

        contractResearchRepository.save(research);

        self.runAsyncStage2Extraction(taskId, contractId, confirmedType, userId);

        return toResponse(research);
    }

    @Async("taskExecutor")
    public void runAsyncStage2Extraction(Long taskId, String contractId, ContractType confirmedType, Long userId) {
        try {
            ContractResearch research = getResearchEntity(taskId);
            ContractEntry entry = findContractOrThrow(research, contractId);
            final String docId = entry.getDocumentId();
            RawDocument doc = rawDocumentRepository.findById(docId)
                    .orElseThrow(() -> new BusinessValidationException("DOCUMENT_NOT_FOUND", "Document not found: " + docId));

            int totalPages = extractionService.getDocumentPageCount(doc);
            String docText = extractionService.parseDocumentText(doc);

            executeStage2Extraction(taskId, contractId, confirmedType, doc, totalPages, docText, userId);
        } catch (Exception e) {
            log.error("Stage 2 extraction failed for task {}, contract {}", taskId, contractId, e);
            handleExtractionFailure(taskId, contractId, e.getMessage());
        }
    }

    private void executeStage2Extraction(Long taskId, String contractId, ContractType confirmedType,
                                        RawDocument doc, int totalPages, String docText, Long userId) {
        updateProgress(taskId, contractId, ContractExtractionStage.EXTRACTING_FIELDS, PROGRESS_EXTRACTING);
        AiContractExtractionCandidate extractionCandidate = extractionService.extractStructuredContract(doc, confirmedType);

        updateProgress(taskId, contractId, ContractExtractionStage.VALIDATING_RESULTS, PROGRESS_VALIDATING);
        CommonContractData common = normalizer.normalizeCommonData(extractionCandidate.getCommonData(), totalPages, docText);

        ContractResearch research = getResearchEntity(taskId);
        ContractEntry entry = findContractOrThrow(research, contractId);

        // Focus on Common Contract Data and active Subtype Data
        entry.setCooperationAgreementData(null);
        entry.setPartnershipAgreementData(null);
        entry.setJointVentureAgreementData(null);
        entry.setBusinessCooperationContractData(null);
        entry.setCommonData(common);

        // Normalize active subtype data according to confirmedType
        switch (confirmedType) {
            case COOPERATION_AGREEMENT -> entry.setCooperationAgreementData(
                    normalizer.normalizeCooperationData(extractionCandidate.getCooperationAgreementData(), totalPages, docText));
            case PARTNERSHIP_AGREEMENT -> entry.setPartnershipAgreementData(
                    normalizer.normalizePartnershipData(extractionCandidate.getPartnershipAgreementData(), totalPages, docText));
            case JOINT_VENTURE_AGREEMENT -> entry.setJointVentureAgreementData(
                    normalizer.normalizeJointVentureData(extractionCandidate.getJointVentureAgreementData(), totalPages, docText));
            case BUSINESS_COOPERATION_CONTRACT -> entry.setBusinessCooperationContractData(
                    normalizer.normalizeBccData(extractionCandidate.getBusinessCooperationContractData(), totalPages, docText));
            default -> {}
        }

        // Evaluate Target Company Match
        String targetCompanyName = getTargetCompanyName(taskId);
        CompanyMatchStatus matchStatus = companyMatcher.evaluateCompanyMatch(targetCompanyName, common.getParties());
        entry.setCompanyMatchStatus(matchStatus);
        entry.setCompanyMatchConfirmed(matchStatus == CompanyMatchStatus.MATCH);

        // Derive deterministic contract status
        LocalDate effective = common.getEffectiveDate() != null ? common.getEffectiveDate().getValue() : null;
        LocalDate expiry = common.getExpiryDate() != null ? common.getExpiryDate().getValue() : null;
        ContractStatus derivedStatus = normalizer.deriveContractStatus(effective, expiry, false);
        entry.setDerivedContractStatus(derivedStatus);
        entry.setStatusDerivedAt(LocalDateTime.now());
        entry.setStatusDerivationReason("Derived from effectiveDate (" + effective + ") and expiryDate (" + expiry + ")");

        updateProgress(taskId, contractId, ContractExtractionStage.SAVING_RESULTS, PROGRESS_SAVING);

        entry.setExtractionStatus(ContractExtractionStatus.COMPLETED);
        entry.setExtractionStage(ContractExtractionStage.SAVING_RESULTS);
        entry.setExtractionProgress(PROGRESS_COMPLETED);
        entry.setExtractionCompletedAt(LocalDateTime.now());
        entry.setExtractionErrorMessage(null);
        entry.setUpdatedAt(LocalDateTime.now());

        contractResearchRepository.save(research);

        auditLogService.log(userId, AuditAction.CONTRACT_AI_EXTRACTION_RUN, "CONTRACT_ENTRY", contractId,
                "Task " + taskId + ": Extracted " + confirmedType.name() + " (COMPLETED)");
    }

    @Transactional
    public ContractResearchResponse reExtractContractEntry(Long taskId, String contractId, Long userId) {
        ContractResearch research = getResearchEntity(taskId);
        ContractEntry entry = findContractOrThrow(research, contractId);

        validateContractEditable(research, entry);

        if (entry.getDataEntryMethod() == ContractDataEntryMethod.MANUAL) {
            throw new BusinessValidationException("CANNOT_EXTRACT_MANUAL_CONTRACT", "Cannot run AI extraction on a Manual Entry contract.");
        }
        if (entry.getDocumentId() == null || entry.getDocumentId().isBlank()) {
            throw new BusinessValidationException("DOCUMENT_REQUIRED", "AI extraction requires a source document (PDF).");
        }

        if (entry.getExtractionStatus() == ContractExtractionStatus.PROCESSING) {
            throw new BusinessValidationException("EXTRACTION_IN_PROGRESS", "Extraction is already in progress for this contract.");
        }

        boolean anyOtherExtracting = research.getContracts() != null && research.getContracts().stream()
                .anyMatch(c -> !c.getId().equals(contractId) && c.getExtractionStatus() == ContractExtractionStatus.PROCESSING);
        if (anyOtherExtracting) {
            throw new BusinessValidationException("EXTRACTION_IN_PROGRESS", "Một tài liệu khác đang được AI trích xuất. Vui lòng đợi hoàn tất trước khi thao tác tiếp.");
        }

        ContractType confirmedType = entry.getConfirmedContractType() != null ? entry.getConfirmedContractType() : ContractType.COOPERATION_AGREEMENT;

        entry.setExtractionStatus(ContractExtractionStatus.PROCESSING);
        entry.setExtractionStage(ContractExtractionStage.EXTRACTING_FIELDS);
        entry.setExtractionProgress(PROGRESS_EXTRACTING);
        entry.setExtractionStartedAt(LocalDateTime.now());
        entry.setExtractionCompletedAt(null);
        entry.setExtractionErrorMessage(null);
        entry.setUpdatedAt(LocalDateTime.now());

        contractResearchRepository.save(research);

        self.runAsyncAtomicReExtraction(taskId, contractId, confirmedType, userId);

        return toResponse(research);
    }

    @Transactional
    public ContractResearchResponse cancelExtraction(Long taskId, String contractId, Long userId) {
        ContractResearch research = getResearchEntity(taskId);
        ContractEntry entry = findContractOrThrow(research, contractId);

        validateContractEditable(research, entry);

        if (entry.getExtractionStatus() == ContractExtractionStatus.PROCESSING) {
            if (entry.getCommonData() != null) {
                entry.setExtractionStatus(ContractExtractionStatus.COMPLETED);
            } else {
                entry.setExtractionStatus(ContractExtractionStatus.NOT_EXTRACTED);
            }
            entry.setExtractionStage(null);
            entry.setExtractionProgress(0);
            entry.setExtractionErrorMessage(null);
            entry.setExtractionErrorCode(null);
            entry.setUpdatedAt(LocalDateTime.now());

            contractResearchRepository.save(research);
            log.info("Canceled extraction for task {}, contract {}", taskId, contractId);
        }

        return toResponse(research);
    }

    @Async("taskExecutor")
    public void runAsyncAtomicReExtraction(Long taskId, String contractId, ContractType confirmedType, Long userId) {
        log.info("Starting atomic re-extraction for task {}, contract {}", taskId, contractId);
        try {
            ContractResearch research = getResearchEntity(taskId);
            ContractEntry entry = findContractOrThrow(research, contractId);
            final String docId = entry.getDocumentId();
            RawDocument doc = rawDocumentRepository.findById(docId)
                    .orElseThrow(() -> new BusinessValidationException("DOCUMENT_NOT_FOUND", "Document not found: " + docId));

            int totalPages = extractionService.getDocumentPageCount(doc);
            String docText = extractionService.parseDocumentText(doc);

            updateProgress(taskId, contractId, ContractExtractionStage.EXTRACTING_FIELDS, PROGRESS_EXTRACTING);
            AiContractExtractionCandidate candidate = extractionService.extractStructuredContract(doc, confirmedType);

            updateProgress(taskId, contractId, ContractExtractionStage.VALIDATING_RESULTS, PROGRESS_VALIDATING);
            CommonContractData newCommon = normalizer.normalizeCommonData(candidate.getCommonData(), totalPages, docText);

            research = getResearchEntity(taskId);
            entry = findContractOrThrow(research, contractId);

            // Merge common parties preserving VERIFIED/MANUAL items
            CommonContractData existingCommon = entry.getCommonData();
            if (existingCommon != null && existingCommon.getParties() != null) {
                List<ContractParty> mergedParties = new ArrayList<>();
                for (ContractParty cp : existingCommon.getParties()) {
                    if (cp.getVerificationStatus() == ContractFieldVerificationStatus.VERIFIED || cp.getInputMethod() == ContractFieldInputMethod.MANUAL) {
                        mergedParties.add(cp);
                    }
                }
                if (newCommon.getParties() != null) {
                    for (ContractParty np : newCommon.getParties()) {
                        boolean exists = mergedParties.stream().anyMatch(mp -> mp.getLegalName().equalsIgnoreCase(np.getLegalName()));
                        if (!exists) {
                            mergedParties.add(np);
                        }
                    }
                }
                newCommon.setParties(mergedParties);
            }
            entry.setCommonData(newCommon);

            // Reset and normalize subtype data
            entry.setCooperationAgreementData(null);
            entry.setPartnershipAgreementData(null);
            entry.setJointVentureAgreementData(null);
            entry.setBusinessCooperationContractData(null);

            switch (confirmedType) {
                case COOPERATION_AGREEMENT -> entry.setCooperationAgreementData(
                        normalizer.normalizeCooperationData(candidate.getCooperationAgreementData(), totalPages, docText));
                case PARTNERSHIP_AGREEMENT -> entry.setPartnershipAgreementData(
                        normalizer.normalizePartnershipData(candidate.getPartnershipAgreementData(), totalPages, docText));
                case JOINT_VENTURE_AGREEMENT -> entry.setJointVentureAgreementData(
                        normalizer.normalizeJointVentureData(candidate.getJointVentureAgreementData(), totalPages, docText));
                case BUSINESS_COOPERATION_CONTRACT -> entry.setBusinessCooperationContractData(
                        normalizer.normalizeBccData(candidate.getBusinessCooperationContractData(), totalPages, docText));
                default -> {}
            }

            // Evaluate Target Company Match
            String targetCompanyName = getTargetCompanyName(taskId);
            CompanyMatchStatus matchStatus = companyMatcher.evaluateCompanyMatch(targetCompanyName, newCommon.getParties());
            entry.setCompanyMatchStatus(matchStatus);
            if (!Boolean.TRUE.equals(entry.getCompanyMatchConfirmed())) {
                entry.setCompanyMatchConfirmed(matchStatus == CompanyMatchStatus.MATCH);
            }

            // Derive deterministic contract status
            LocalDate effective = newCommon.getEffectiveDate() != null ? newCommon.getEffectiveDate().getValue() : null;
            LocalDate expiry = newCommon.getExpiryDate() != null ? newCommon.getExpiryDate().getValue() : null;
            ContractStatus derivedStatus = normalizer.deriveContractStatus(effective, expiry, false);
            entry.setDerivedContractStatus(derivedStatus);
            entry.setStatusDerivedAt(LocalDateTime.now());
            entry.setStatusDerivationReason("Derived from effectiveDate (" + effective + ") and expiryDate (" + expiry + ")");

            entry.setExtractionStatus(ContractExtractionStatus.COMPLETED);
            entry.setExtractionStage(ContractExtractionStage.SAVING_RESULTS);
            entry.setExtractionProgress(PROGRESS_COMPLETED);
            entry.setExtractionCompletedAt(LocalDateTime.now());
            entry.setExtractionErrorMessage(null);
            entry.setUpdatedAt(LocalDateTime.now());

            contractResearchRepository.save(research);
        } catch (Exception e) {
            log.error("Atomic re-extraction failed for task {}, contract {}", taskId, contractId, e);
            handleExtractionFailure(taskId, contractId, e.getMessage());
        }
    }

    @Transactional
    public ContractResearchResponse confirmCompanyMatch(Long taskId, String contractId, boolean confirmed, Long userId) {
        ContractResearch research = getResearchEntity(taskId);
        ContractEntry entry = findContractOrThrow(research, contractId);

        validateContractEditable(research, entry);

        entry.setCompanyMatchConfirmed(confirmed);
        entry.setCompanyMatchConfirmedBy(userId);
        entry.setCompanyMatchConfirmedAt(LocalDateTime.now());
        entry.setUpdatedAt(LocalDateTime.now());

        research = contractResearchRepository.save(research);
        return toResponse(research);
    }

    @Transactional
    public ContractResearchResponse updateScalarField(Long taskId, String contractId, String fieldPath, UpdateScalarFieldRequest req, Long userId) {
        ContractResearch research = getResearchEntity(taskId);
        ContractEntry entry = findContractOrThrow(research, contractId);

        validateContractEditable(research, entry);

        applyScalarFieldUpdate(entry, fieldPath, req);
        entry.setUpdatedAt(LocalDateTime.now());

        research = contractResearchRepository.save(research);
        return toResponse(research);
    }

    @Transactional
    public ContractResearchResponse verifyScalarField(Long taskId, String contractId, String fieldPath, Long userId) {
        ContractResearch research = getResearchEntity(taskId);
        ContractEntry entry = findContractOrThrow(research, contractId);

        validateContractEditable(research, entry);

        applyScalarFieldVerification(entry, fieldPath, ContractFieldVerificationStatus.VERIFIED);
        entry.setUpdatedAt(LocalDateTime.now());

        research = contractResearchRepository.save(research);
        return toResponse(research);
    }

    @Transactional
    public ContractResearchResponse unverifyScalarField(Long taskId, String contractId, String fieldPath, Long userId) {
        ContractResearch research = getResearchEntity(taskId);
        ContractEntry entry = findContractOrThrow(research, contractId);

        validateContractEditable(research, entry);

        applyScalarFieldVerification(entry, fieldPath, ContractFieldVerificationStatus.UNVERIFIED);
        entry.setUpdatedAt(LocalDateTime.now());

        research = contractResearchRepository.save(research);
        return toResponse(research);
    }

    @Transactional
    public ContractResearchResponse updateArrayItem(Long taskId, String contractId, String fieldPath, String itemId, UpdateArrayItemRequest req, Long userId) {
        ContractResearch research = getResearchEntity(taskId);
        ContractEntry entry = findContractOrThrow(research, contractId);

        validateContractEditable(research, entry);

        applyArrayItemUpdate(entry, fieldPath, itemId, req);
        entry.setUpdatedAt(LocalDateTime.now());

        research = contractResearchRepository.save(research);
        return toResponse(research);
    }

    @Transactional
    public ContractResearchResponse verifyArrayItem(Long taskId, String contractId, String fieldPath, String itemId, Long userId) {
        ContractResearch research = getResearchEntity(taskId);
        ContractEntry entry = findContractOrThrow(research, contractId);

        validateContractEditable(research, entry);

        applyArrayItemVerification(entry, fieldPath, itemId, ContractFieldVerificationStatus.VERIFIED);
        entry.setUpdatedAt(LocalDateTime.now());

        research = contractResearchRepository.save(research);
        return toResponse(research);
    }

    @Transactional
    public ContractResearchResponse unverifyArrayItem(Long taskId, String contractId, String fieldPath, String itemId, Long userId) {
        ContractResearch research = getResearchEntity(taskId);
        ContractEntry entry = findContractOrThrow(research, contractId);

        validateContractEditable(research, entry);

        applyArrayItemVerification(entry, fieldPath, itemId, ContractFieldVerificationStatus.UNVERIFIED);
        entry.setUpdatedAt(LocalDateTime.now());

        research = contractResearchRepository.save(research);
        return toResponse(research);
    }

    @Transactional
    public ContractResearchResponse verifyAllContractFields(Long taskId, String contractId, Long userId) {
        ContractResearch research = getResearchEntity(taskId);
        ContractEntry entry = findContractOrThrow(research, contractId);
        validateContractEditable(research, entry);

        setAllFieldsVerificationStatus(entry, ContractFieldVerificationStatus.VERIFIED);

        entry.setUpdatedAt(LocalDateTime.now());
        research = contractResearchRepository.save(research);
        return toResponse(research);
    }

    @Transactional
    public ContractResearchResponse unverifyAllContractFields(Long taskId, String contractId, Long userId) {
        ContractResearch research = getResearchEntity(taskId);
        ContractEntry entry = findContractOrThrow(research, contractId);
        validateContractEditable(research, entry);

        setAllFieldsVerificationStatus(entry, ContractFieldVerificationStatus.UNVERIFIED);

        entry.setUpdatedAt(LocalDateTime.now());
        research = contractResearchRepository.save(research);
        return toResponse(research);
    }

    private void setAllFieldsVerificationStatus(ContractEntry entry, ContractFieldVerificationStatus status) {
        List<String> scalarFields = List.of(
            "common.contractTitle", "common.contractNumber", "common.signingDate", "common.effectiveDate",
            "common.expiryDate", "common.term", "common.contractValue", "common.governingLaw", "common.purpose"
        );
        for (String fieldPath : scalarFields) {
            applyScalarFieldVerification(entry, fieldPath, status);
        }

        if (entry.getCommonData() != null && entry.getCommonData().getParties() != null) {
            for (com.apms.domain.contract.model.ContractParty p : entry.getCommonData().getParties()) {
                if (p.getId() != null) {
                    applyArrayItemVerification(entry, "parties", p.getId(), status);
                }
            }
        }
        if (entry.getCooperationAgreementData() != null) {
            applyScalarFieldVerification(entry, "cooperationscope", status);
            applyScalarFieldVerification(entry, "informationsharing", status);
            applyScalarFieldVerification(entry, "coordinationmechanism", status);
            if (entry.getCooperationAgreementData().getResponsibilities() != null) {
                for (com.apms.domain.contract.model.PartyResponsibility r : entry.getCooperationAgreementData().getResponsibilities()) {
                    if (r.getId() != null) applyArrayItemVerification(entry, "responsibilities", r.getId(), status);
                }
            }
            if (entry.getCooperationAgreementData().getResourceCommitments() != null) {
                for (com.apms.domain.contract.model.ResourceCommitment rc : entry.getCooperationAgreementData().getResourceCommitments()) {
                    if (rc.getId() != null) applyArrayItemVerification(entry, "resourcecommitments", rc.getId(), status);
                }
            }
        }
        if (entry.getPartnershipAgreementData() != null) {
            applyScalarFieldVerification(entry, "partnershipscope", status);
            applyScalarFieldVerification(entry, "benefitsharing", status);
            applyScalarFieldVerification(entry, "salesormarketrights", status);
            applyScalarFieldVerification(entry, "relationshipgovernance", status);
            applyScalarFieldVerification(entry, "exclusivity", status);
            if (entry.getPartnershipAgreementData().getPartnerRoles() != null) {
                for (com.apms.domain.contract.model.PartnerRole pr : entry.getPartnershipAgreementData().getPartnerRoles()) {
                    if (pr.getId() != null) applyArrayItemVerification(entry, "partnerroles", pr.getId(), status);
                }
            }
            if (entry.getPartnershipAgreementData().getMutualCommitments() != null) {
                for (com.apms.domain.contract.model.MutualCommitment mc : entry.getPartnershipAgreementData().getMutualCommitments()) {
                    if (mc.getId() != null) applyArrayItemVerification(entry, "mutualcommitments", mc.getId(), status);
                }
            }
            if (entry.getPartnershipAgreementData().getPerformanceRequirements() != null) {
                for (com.apms.domain.contract.model.PerformanceRequirement pr : entry.getPartnershipAgreementData().getPerformanceRequirements()) {
                    if (pr.getId() != null) applyArrayItemVerification(entry, "performancerequirements", pr.getId(), status);
                }
            }
        }
        if (entry.getJointVentureAgreementData() != null) {
            applyScalarFieldVerification(entry, "jointventurename", status);
            applyScalarFieldVerification(entry, "jointventurepurpose", status);
            applyScalarFieldVerification(entry, "governancestructure", status);
            if (entry.getJointVentureAgreementData().getCapitalContributions() != null) {
                for (com.apms.domain.contract.model.CapitalContribution c : entry.getJointVentureAgreementData().getCapitalContributions()) {
                    if (c.getId() != null) applyArrayItemVerification(entry, "capitalcontributions", c.getId(), status);
                }
            }
            if (entry.getJointVentureAgreementData().getOwnershipPercentages() != null) {
                for (com.apms.domain.contract.model.OwnershipPercentage o : entry.getJointVentureAgreementData().getOwnershipPercentages()) {
                    if (o.getId() != null) applyArrayItemVerification(entry, "ownershippercentages", o.getId(), status);
                }
            }
            if (entry.getJointVentureAgreementData().getVotingRights() != null) {
                for (com.apms.domain.contract.model.VotingRight v : entry.getJointVentureAgreementData().getVotingRights()) {
                    if (v.getId() != null) applyArrayItemVerification(entry, "votingrights", v.getId(), status);
                }
            }
            if (entry.getJointVentureAgreementData().getProfitDistribution() != null) {
                for (com.apms.domain.contract.model.DistributionShare p : entry.getJointVentureAgreementData().getProfitDistribution()) {
                    if (p.getId() != null) applyArrayItemVerification(entry, "profitdistribution", p.getId(), status);
                }
            }
            if (entry.getJointVentureAgreementData().getLossSharing() != null) {
                for (com.apms.domain.contract.model.DistributionShare l : entry.getJointVentureAgreementData().getLossSharing()) {
                    if (l.getId() != null) applyArrayItemVerification(entry, "losssharing", l.getId(), status);
                }
            }
            if (entry.getJointVentureAgreementData().getManagementAppointments() != null) {
                for (com.apms.domain.contract.model.ManagementAppointment m : entry.getJointVentureAgreementData().getManagementAppointments()) {
                    if (m.getId() != null) applyArrayItemVerification(entry, "managementappointments", m.getId(), status);
                }
            }
        }
        if (entry.getBusinessCooperationContractData() != null) {
            applyScalarFieldVerification(entry, "businessscope", status);
            applyScalarFieldVerification(entry, "managementmechanism", status);
            applyScalarFieldVerification(entry, "financialmanagement", status);
            applyScalarFieldVerification(entry, "assetownership", status);
            applyScalarFieldVerification(entry, "terminationsettlement", status);
            if (entry.getBusinessCooperationContractData().getContributions() != null) {
                for (com.apms.domain.contract.model.BccContribution c : entry.getBusinessCooperationContractData().getContributions()) {
                    if (c.getId() != null) applyArrayItemVerification(entry, "contributions", c.getId(), status);
                }
            }
            if (entry.getBusinessCooperationContractData().getContributionRatios() != null) {
                for (com.apms.domain.contract.model.ContributionRatio r : entry.getBusinessCooperationContractData().getContributionRatios()) {
                    if (r.getId() != null) applyArrayItemVerification(entry, "contributionratios", r.getId(), status);
                }
            }
            if (entry.getBusinessCooperationContractData().getRevenueSharing() != null) {
                for (com.apms.domain.contract.model.SharingArrangement s : entry.getBusinessCooperationContractData().getRevenueSharing()) {
                    if (s.getId() != null) applyArrayItemVerification(entry, "revenuesharing", s.getId(), status);
                }
            }
            if (entry.getBusinessCooperationContractData().getProfitSharing() != null) {
                for (com.apms.domain.contract.model.SharingArrangement s : entry.getBusinessCooperationContractData().getProfitSharing()) {
                    if (s.getId() != null) applyArrayItemVerification(entry, "profitsharing", s.getId(), status);
                }
            }
            if (entry.getBusinessCooperationContractData().getCostSharing() != null) {
                for (com.apms.domain.contract.model.SharingArrangement s : entry.getBusinessCooperationContractData().getCostSharing()) {
                    if (s.getId() != null) applyArrayItemVerification(entry, "costsharing", s.getId(), status);
                }
            }
            if (entry.getBusinessCooperationContractData().getLossSharing() != null) {
                for (com.apms.domain.contract.model.SharingArrangement s : entry.getBusinessCooperationContractData().getLossSharing()) {
                    if (s.getId() != null) applyArrayItemVerification(entry, "losssharing", s.getId(), status);
                }
            }
            if (entry.getBusinessCooperationContractData().getRightsAndObligations() != null) {
                for (com.apms.domain.contract.model.PartyRightsAndObligations ro : entry.getBusinessCooperationContractData().getRightsAndObligations()) {
                    if (ro.getId() != null) applyArrayItemVerification(entry, "rightsandobligations", ro.getId(), status);
                }
            }
        }
    }

    @Transactional
    public ContractResearchResponse submitResearch(Long projectId, Long taskId, SubmitContractResearchRequest req, Long userId) {
        if (req.getContractEntryIds() == null || req.getContractEntryIds().isEmpty()) {
            throw new BusinessValidationException("EMPTY_SUBMISSION", "At least one contract must be selected for submission.");
        }

        // Check single active submission constraint
        List<ProjectTaskSubmission> activeSubs = projectTaskSubmissionRepository.findByProjectTask_Id(taskId).stream()
                .filter(s -> s.getStatus() == SubmissionStatus.IN_REVIEW)
                .toList();
        if (!activeSubs.isEmpty()) {
            throw new BusinessValidationException("ACTIVE_SUBMISSION_EXISTS", "A submission is already active and awaiting review for this task.");
        }

        ContractResearch research = getResearchEntity(taskId);
        ProjectTask task = projectTaskRepository.findWithProjectById(taskId)
                .orElseThrow(() -> new BusinessValidationException("TASK_NOT_FOUND", "Project task not found: " + taskId));

        boolean isRevision = research.getStatus() == ContractResearchStatus.CHANGES_REQUESTED
                || (research.getContracts() != null && research.getContracts().stream().anyMatch(c -> c.getReviewStatus() == ContractEntryReviewStatus.CHANGES_REQUESTED));
        List<ContractEntry> selectedContracts = new ArrayList<>();
        for (String cId : req.getContractEntryIds()) {
            ContractEntry entry = findContractOrThrow(research, cId);
            if (entry.getReviewStatus() == ContractEntryReviewStatus.APPROVED) {
                throw new BusinessValidationException("CONTRACT_APPROVED_IMMUTABLE",
                        "Approved contract '" + entry.getTitle() + "' cannot be resubmitted.");
            }
            if (isRevision && entry.getReviewStatus() != ContractEntryReviewStatus.CHANGES_REQUESTED
                    && entry.getReviewStatus() != ContractEntryReviewStatus.PENDING_REVIEW) {
                throw new BusinessValidationException("CONTRACT_NOT_IN_REVISION",
                        "Only contracts with CHANGES_REQUESTED can be resubmitted during revision. Contract '" + entry.getTitle() + "' is in status " + entry.getReviewStatus());
            }
            validateSubmissionEligibility(entry);
            selectedContracts.add(entry);
        }

        // Mark contracts PENDING_REVIEW and reset active decision fields
        for (ContractEntry c : selectedContracts) {
            c.setReviewStatus(ContractEntryReviewStatus.PENDING_REVIEW);
            c.setReviewComment(null);
            c.setReviewedBy(null);
            c.setReviewedByName(null);
            c.setReviewedAt(null);
            c.setUpdatedAt(LocalDateTime.now());
        }

        research.setStatus(ContractResearchStatus.SUBMITTED);
        research.setSubmittedAt(LocalDateTime.now());
        research.setUpdatedAt(LocalDateTime.now());

        // Create immutable ProjectTaskSubmission record
        Account submitter = userProfileRepository.findById(userId)
                .map(p -> Account.builder().id(userId).build())
                .orElse(null);

        ProjectTaskSubmission submission = ProjectTaskSubmission.builder()
                .projectTask(task)
                .project(task.getProject())
                .submittedByAccount(submitter)
                .submissionType(SubmissionType.PARTNER_CONTRACT_COLLECTION)
                .targetEntityType("CONTRACT_RESEARCH")
                .targetEntityId(research.getId())
                .status(SubmissionStatus.IN_REVIEW)
                .note(req.getNote())
                .targetItemIds(String.join(",", req.getContractEntryIds()))
                .submittedAt(LocalDateTime.now())
                .build();

        projectTaskSubmissionRepository.save(submission);

        task.setStatus(TaskStatus.IN_REVIEW);
        projectTaskRepository.save(task);

        research = contractResearchRepository.save(research);

        auditLogService.log(userId, AuditAction.CONTRACT_RESEARCH_SUBMITTED, "CONTRACT_RESEARCH", research.getId(),
                "Task " + taskId + ": Submitted " + req.getContractEntryIds().size() + " contracts (submission #" + submission.getId() + ")");

        return toResponse(research);
    }

    @Transactional
    public ContractResearchResponse recallSubmission(Long projectId, Long taskId, Long userId) {
        ContractResearch research = getResearchEntity(taskId);
        ProjectTask task = projectTaskRepository.findWithProjectById(taskId)
                .orElseThrow(() -> new BusinessValidationException("TASK_NOT_FOUND", "Project task not found: " + taskId));

        ProjectTaskSubmission activeSub = projectTaskSubmissionRepository.findByProjectTask_Id(taskId).stream()
                .filter(s -> s.getStatus() == SubmissionStatus.IN_REVIEW)
                .findFirst()
                .orElseThrow(() -> new BusinessValidationException("NO_ACTIVE_SUBMISSION", "No active submission found to recall."));

        // Transactional race safety check: Verify Manager has made NO decision on any contract in this submission
        List<String> submittedIds = activeSub.getTargetItemIdList();
        for (String cId : submittedIds) {
            ContractEntry entry = findContractOrThrow(research, cId);
            if (entry.getReviewStatus() == ContractEntryReviewStatus.APPROVED || entry.getReviewStatus() == ContractEntryReviewStatus.CHANGES_REQUESTED) {
                throw new BusinessValidationException("CANNOT_RECALL_DECIDED_SUBMISSION",
                        "Cannot recall submission: Manager has already made a decision on one or more contracts.");
            }
        }

        // Mark submission WITHDRAWN
        activeSub.setStatus(SubmissionStatus.WITHDRAWN);
        projectTaskSubmissionRepository.save(activeSub);

        // Restore contracts to previous editable state
        for (String cId : submittedIds) {
            ContractEntry entry = findContractOrThrow(research, cId);
            if (StringUtils.hasText(entry.getReviewComment())) {
                // Was previously returned with changes requested
                entry.setReviewStatus(ContractEntryReviewStatus.CHANGES_REQUESTED);
            } else {
                entry.setReviewStatus(ContractEntryReviewStatus.DRAFT);
            }
            entry.setUpdatedAt(LocalDateTime.now());
        }

        task.setStatus(TaskStatus.IN_PROGRESS);
        projectTaskRepository.save(task);

        // Recompute package status precedence
        research.setStatus(computePackageStatusPrecedence(research));
        research.setUpdatedAt(LocalDateTime.now());
        research = contractResearchRepository.save(research);

        auditLogService.log(userId, AuditAction.CONTRACT_RESEARCH_SUBMISSION_RECALLED, "CONTRACT_RESEARCH", research.getId(),
                "Task " + taskId + ": Recalled submission #" + activeSub.getId());

        return toResponse(research);
    }

    @Transactional
    public ContractResearchResponse reviewContractEntry(Long projectId, Long taskId, Long submissionId, String contractId, ReviewContractEntryRequest req, Long userId) {
        ContractResearch research = getResearchEntity(taskId);
        ProjectTask task = projectTaskRepository.findWithProjectById(taskId)
                .orElseThrow(() -> new BusinessValidationException("TASK_NOT_FOUND", "Project task not found: " + taskId));

        ProjectTaskSubmission submission = projectTaskSubmissionRepository.findById(submissionId)
                .orElseThrow(() -> new BusinessValidationException("SUBMISSION_NOT_FOUND", "Submission not found: " + submissionId));

        if (submission.getStatus() != SubmissionStatus.IN_REVIEW) {
            throw new BusinessValidationException("SUBMISSION_NOT_ACTIVE", "Submission is not in an active reviewable state.");
        }

        if (!submission.getTargetItemIdList().contains(contractId)) {
            throw new BusinessValidationException("CONTRACT_NOT_IN_SUBMISSION", "Contract " + contractId + " does not belong to submission " + submissionId);
        }

        ContractEntry entry = findContractOrThrow(research, contractId);
        if (!isPendingReview(entry)) {
            throw new BusinessValidationException("CONTRACT_NOT_PENDING_REVIEW", "This contract has already been reviewed in the current review cycle.");
        }

        if (req.getStatus() == ContractEntryReviewStatus.CHANGES_REQUESTED && !StringUtils.hasText(req.getReason())) {
            throw new BusinessValidationException("REASON_REQUIRED", "Review comment is required when requesting changes.");
        }

        String reviewerName = userProfileRepository.findById(userId)
                .map(p -> ((p.getFirstName() != null ? p.getFirstName() : "") + " " + (p.getLastName() != null ? p.getLastName() : "")).trim())
                .filter(StringUtils::hasText)
                .orElse("Manager");

        entry.setReviewStatus(req.getStatus());
        entry.setReviewComment(req.getReason());
        entry.setReviewedBy(userId);
        entry.setReviewedByName(reviewerName);
        entry.setReviewedAt(LocalDateTime.now());
        entry.setUpdatedAt(LocalDateTime.now());

        // Append immutable ContractReviewEvent
        ContractReviewEvent event = ContractReviewEvent.builder()
                .id(UUID.randomUUID().toString())
                .submissionId(submissionId)
                .contractEntryId(contractId)
                .decision(req.getStatus())
                .reason(req.getReason())
                .reviewedBy(userId)
                .reviewedByName(reviewerName)
                .reviewedAt(LocalDateTime.now())
                .build();
        entry.getReviewHistory().add(event);

        Account reviewerAccount = accountRepository != null ? accountRepository.findById(userId).orElse(null) : null;
        recalculateContractReviewState(research, task, submission, userId, reviewerAccount);

        research = contractResearchRepository.save(research);

        AuditAction auditAction = req.getStatus() == ContractEntryReviewStatus.APPROVED
                ? AuditAction.CONTRACT_RESEARCH_APPROVED
                : AuditAction.CONTRACT_RESEARCH_CHANGES_REQUESTED;

        auditLogService.log(userId, auditAction, "CONTRACT_ENTRY", contractId,
                "Task " + taskId + ": Submission #" + submissionId + " - " + req.getStatus().name());

        return toResponse(research);
    }

    public List<ContractEntry> getApprovedContractsForProfile(String companyProfileId) {
        Set<String> possibleIds = new HashSet<>();
        if (StringUtils.hasText(companyProfileId)) {
            possibleIds.add(companyProfileId.trim());
        }

        try {
            companyProfileRepository.findById(companyProfileId).ifPresent(p -> {
                if (p.getCompanyId() != null) possibleIds.add(p.getCompanyId());
                if (p.getIdentity() != null) {
                    if (StringUtils.hasText(p.getIdentity().getLegalName())) {
                        possibleIds.add(p.getIdentity().getLegalName().trim());
                    }
                    if (StringUtils.hasText(p.getIdentity().getTradeName())) {
                        possibleIds.add(p.getIdentity().getTradeName().trim());
                    }
                }
            });
            companyProfileRepository.findByCompanyId(companyProfileId).ifPresent(p -> {
                if (p.getId() != null) possibleIds.add(p.getId());
                if (p.getIdentity() != null) {
                    if (StringUtils.hasText(p.getIdentity().getLegalName())) {
                        possibleIds.add(p.getIdentity().getLegalName().trim());
                    }
                    if (StringUtils.hasText(p.getIdentity().getTradeName())) {
                        possibleIds.add(p.getIdentity().getTradeName().trim());
                    }
                }
            });
        } catch (Exception ignored) {}

        Set<String> processedResearchIds = new HashSet<>();
        List<ContractResearch> approvedResearches = new ArrayList<>();
        for (String pid : possibleIds) {
            List<ContractResearch> list = contractResearchRepository.findByCompanyProfileIdAndStatus(
                    pid, ContractResearchStatus.APPROVED);
            for (ContractResearch cr : list) {
                if (cr.getId() != null && processedResearchIds.add(cr.getId())) {
                    approvedResearches.add(cr);
                }
            }
        }

        List<ContractEntry> approvedContracts = new ArrayList<>();
        for (ContractResearch r : approvedResearches) {
            if (r.getContracts() != null) {
                for (ContractEntry c : r.getContracts()) {
                    if (c.getReviewStatus() == ContractEntryReviewStatus.APPROVED) {
                        c.setProjectId(r.getProjectId());
                        c.setTaskId(r.getTaskId());
                        approvedContracts.add(c);
                    }
                }
            }
        }
        return approvedContracts;
    }

    // --- Helper Validation & Resolution Methods ---

    private boolean isPendingReview(ContractEntry entry) {
        if (entry == null) return false;
        return entry.getReviewStatus() == null
                || entry.getReviewStatus() == ContractEntryReviewStatus.PENDING_REVIEW;
    }

    private void recalculateContractReviewState(
            ContractResearch research,
            ProjectTask task,
            ProjectTaskSubmission activeSubmission,
            Long reviewerId,
            Account reviewerAccount
    ) {
        Set<String> packageContractIds = new HashSet<>(activeSubmission.getTargetItemIdList());
        List<ContractEntry> packageContracts = research.getContracts().stream()
                .filter(c -> packageContractIds.contains(c.getId()))
                .collect(Collectors.toList());

        boolean packageHasPending = packageContracts.stream().anyMatch(this::isPendingReview);
        boolean packageHasChangesRequested = packageContracts.stream()
                .anyMatch(c -> c.getReviewStatus() == ContractEntryReviewStatus.CHANGES_REQUESTED);
        boolean packageAllApproved = !packageContracts.isEmpty() && packageContracts.stream()
                .allMatch(c -> c.getReviewStatus() == ContractEntryReviewStatus.APPROVED);

        LocalDateTime now = LocalDateTime.now();

        // Priority 1: Pending contracts exist in current review package
        if (packageHasPending) {
            research.setStatus(ContractResearchStatus.SUBMITTED);
            if (task.getStatus() != TaskStatus.IN_REVIEW) {
                task.setStatus(TaskStatus.IN_REVIEW);
                task.setCompletedAt(null);
                projectTaskRepository.save(task);
            }
            if (activeSubmission.getStatus() != SubmissionStatus.IN_REVIEW) {
                activeSubmission.setStatus(SubmissionStatus.IN_REVIEW);
                projectTaskSubmissionRepository.save(activeSubmission);
            }
            return;
        }

        // Priority 2: Current package has changes requested (and 0 pending in package)
        if (packageHasChangesRequested) {
            research.setStatus(ContractResearchStatus.CHANGES_REQUESTED);
            research.setReviewedBy(reviewerId);
            research.setReviewedAt(now);

            // Aggregate feedback ONLY from CHANGES_REQUESTED contracts in packageContracts
            List<ContractEntry> changedContracts = packageContracts.stream()
                    .filter(c -> c.getReviewStatus() == ContractEntryReviewStatus.CHANGES_REQUESTED)
                    .collect(Collectors.toList());

            String aggregatedFeedback = changedContracts.stream()
                    .map(c -> {
                        String title = StringUtils.hasText(c.getTitle()) ? c.getTitle() : "Contract";
                        String comment = StringUtils.hasText(c.getReviewComment()) ? c.getReviewComment() : "Changes requested";
                        return title + ": " + comment;
                    })
                    .collect(Collectors.joining("\n"));

            research.setReviewReason(aggregatedFeedback);

            task.setStatus(TaskStatus.IN_PROGRESS);
            task.setCompletedAt(null);
            projectTaskRepository.save(task);

            activeSubmission.setStatus(SubmissionStatus.REVISION_REQUESTED);
            activeSubmission.setReviewedByAccount(reviewerAccount);
            activeSubmission.setReviewedAt(now);
            activeSubmission.setReviewComment(aggregatedFeedback);
            projectTaskSubmissionRepository.save(activeSubmission);
            return;
        }

        // Priority 3: Current package is all approved
        if (packageAllApproved) {
            activeSubmission.setStatus(SubmissionStatus.APPROVED);
            activeSubmission.setReviewedByAccount(reviewerAccount);
            activeSubmission.setReviewedAt(now);
            activeSubmission.setReviewComment("Contracts approved");
            projectTaskSubmissionRepository.save(activeSubmission);

            // Separately evaluate if the WHOLE Contract task is complete
            List<ContractEntry> allContracts = research.getContracts() != null ? research.getContracts() : Collections.emptyList();
            boolean anyTaskContractPendingOrDraftOrChanges = allContracts.stream()
                    .anyMatch(c -> c.getReviewStatus() == ContractEntryReviewStatus.DRAFT
                            || c.getReviewStatus() == ContractEntryReviewStatus.CHANGES_REQUESTED
                            || isPendingReview(c));

            boolean allTaskContractsApproved = !allContracts.isEmpty() && allContracts.stream()
                    .allMatch(c -> c.getReviewStatus() == ContractEntryReviewStatus.APPROVED);

            if (allTaskContractsApproved && !anyTaskContractPendingOrDraftOrChanges) {
                research.setStatus(ContractResearchStatus.APPROVED);
                research.setReviewedBy(reviewerId);
                research.setReviewedAt(now);
                research.setReviewReason("All contracts approved");

                task.setStatus(TaskStatus.DONE);
                task.setCompletedAt(now);
                projectTaskRepository.save(task);
            } else {
                // Current submission is approved, but task still has other active draft or changes requested contracts
                research.setStatus(computePackageStatusPrecedence(research));
                task.setStatus(TaskStatus.IN_PROGRESS);
                task.setCompletedAt(null);
                projectTaskRepository.save(task);
            }
        }
    }

    private ContractResearchStatus computePackageStatusPrecedence(ContractResearch research) {
        if (research.getContracts() == null || research.getContracts().isEmpty()) {
            return ContractResearchStatus.DRAFT;
        }

        boolean anyChangesRequested = false;
        boolean anyPendingReview = false;
        boolean anyApproved = false;
        boolean anyDraft = false;

        for (ContractEntry c : research.getContracts()) {
            if (c.getReviewStatus() == ContractEntryReviewStatus.CHANGES_REQUESTED) {
                anyChangesRequested = true;
            } else if (c.getReviewStatus() == ContractEntryReviewStatus.PENDING_REVIEW) {
                anyPendingReview = true;
            } else if (c.getReviewStatus() == ContractEntryReviewStatus.APPROVED) {
                anyApproved = true;
            } else if (c.getReviewStatus() == ContractEntryReviewStatus.DRAFT || c.getReviewStatus() == null) {
                anyDraft = true;
            }
        }

        if (anyPendingReview) return ContractResearchStatus.SUBMITTED;
        if (anyChangesRequested) return ContractResearchStatus.CHANGES_REQUESTED;
        if (anyApproved && !anyDraft) return ContractResearchStatus.APPROVED;
        if (anyDraft) return ContractResearchStatus.DRAFT;

        return ContractResearchStatus.DRAFT;
    }

    private void syncTaskAndResearchStatusIfComplete(ContractResearch research) {
        if (research == null || research.getContracts() == null) return;

        try {
            Long taskId = research.getTaskId();
            if (taskId == null) return;

            ProjectTask task = projectTaskRepository.findWithProjectById(taskId).orElse(null);
            if (task == null) return;

            List<ProjectTaskSubmission> subs = projectTaskSubmissionRepository.findByProjectTask_Id(taskId);

            // 1. Check if there is an active IN_REVIEW submission
            Optional<ProjectTaskSubmission> inReviewSub = subs.stream()
                    .filter(s -> s.getStatus() == SubmissionStatus.IN_REVIEW)
                    .findFirst();

            if (inReviewSub.isPresent()) {
                boolean modified = false;
                if (research.getStatus() != ContractResearchStatus.SUBMITTED) {
                    research.setStatus(ContractResearchStatus.SUBMITTED);
                    modified = true;
                }
                if (task.getStatus() != TaskStatus.IN_REVIEW) {
                    task.setStatus(TaskStatus.IN_REVIEW);
                    projectTaskRepository.save(task);
                }
                if (modified) {
                    contractResearchRepository.save(research);
                }
                return;
            }

            // 2. No active IN_REVIEW submission.
            Optional<ProjectTaskSubmission> revSub = subs.stream()
                    .filter(s -> s.getStatus() == SubmissionStatus.REVISION_REQUESTED)
                    .findFirst();

            boolean hasChangesRequestedContracts = research.getContracts().stream()
                    .anyMatch(c -> c.getReviewStatus() == ContractEntryReviewStatus.CHANGES_REQUESTED);

            boolean allApproved = !research.getContracts().isEmpty() && research.getContracts().stream()
                    .allMatch(c -> c.getReviewStatus() == ContractEntryReviewStatus.APPROVED);

            if (allApproved) {
                boolean modified = false;
                if (research.getStatus() != ContractResearchStatus.APPROVED) {
                    research.setStatus(ContractResearchStatus.APPROVED);
                    modified = true;
                }
                if (task.getStatus() != TaskStatus.DONE) {
                    task.setStatus(TaskStatus.DONE);
                    task.setCompletedAt(LocalDateTime.now());
                    projectTaskRepository.save(task);
                    log.info("Auto-synced task {} to DONE as all submitted contracts are approved", task.getId());
                }
                if (modified) {
                    contractResearchRepository.save(research);
                }
            } else if (revSub.isPresent() || (task.getStatus() == TaskStatus.IN_PROGRESS && hasChangesRequestedContracts)) {
                boolean modified = false;
                if (research.getStatus() != ContractResearchStatus.CHANGES_REQUESTED) {
                    research.setStatus(ContractResearchStatus.CHANGES_REQUESTED);
                    modified = true;
                }
                if (task.getStatus() != TaskStatus.IN_PROGRESS) {
                    task.setStatus(TaskStatus.IN_PROGRESS);
                    projectTaskRepository.save(task);
                }

                // AUTO-HEAL: If the submission was returned for revision, any contract in that submission
                // that was left in PENDING_REVIEW (due to legacy premature return before review completed)
                // MUST be transitioned to CHANGES_REQUESTED so Staff can edit and resubmit it!
                if (revSub.isPresent()) {
                    Set<String> subItemIds = new HashSet<>(revSub.get().getTargetItemIdList());
                    String comment = StringUtils.hasText(revSub.get().getReviewComment())
                            ? revSub.get().getReviewComment()
                            : "Returned for revision with submission package";
                    for (ContractEntry c : research.getContracts()) {
                        if (subItemIds.contains(c.getId()) && isPendingReview(c)) {
                            c.setReviewStatus(ContractEntryReviewStatus.CHANGES_REQUESTED);
                            if (!StringUtils.hasText(c.getReviewComment())) {
                                c.setReviewComment(comment);
                            }
                            c.setUpdatedAt(LocalDateTime.now());
                            modified = true;
                            log.info("Auto-healed legacy pending contract {} to CHANGES_REQUESTED for revision task {}", c.getId(), taskId);
                        }
                    }
                }

                if (modified) {
                    contractResearchRepository.save(research);
                }
            } else if (task.getStatus() == TaskStatus.IN_PROGRESS) {
                boolean modified = false;
                if (research.getStatus() != ContractResearchStatus.DRAFT) {
                    research.setStatus(ContractResearchStatus.DRAFT);
                    modified = true;
                }
                if (modified) {
                    contractResearchRepository.save(research);
                }
            }
        } catch (Exception e) {
            log.warn("Could not sync task status in getResearch: {}", e.getMessage());
        }
    }

    public void validateContractDates(LocalDate signingDate, LocalDate effectiveDate, LocalDate expiryDate) {
        if (signingDate != null && effectiveDate != null && effectiveDate.isBefore(signingDate)) {
            throw new BusinessValidationException("INVALID_CONTRACT_DATES", "Ngày hiệu lực phải bằng hoặc sau ngày ký.");
        }
        if (effectiveDate != null && expiryDate != null && !expiryDate.isAfter(effectiveDate)) {
            throw new BusinessValidationException("INVALID_CONTRACT_DATES", "Ngày hết hạn phải sau ngày hiệu lực.");
        }
    }

    private void validateSubmissionEligibility(ContractEntry entry) {
        if (entry.getReviewStatus() == ContractEntryReviewStatus.APPROVED) {
            throw new BusinessValidationException("CONTRACT_APPROVED_IMMUTABLE",
                    "Approved contract '" + entry.getTitle() + "' cannot be resubmitted.");
        }
        if (entry.getReviewStatus() != null
                && entry.getReviewStatus() != ContractEntryReviewStatus.DRAFT
                && entry.getReviewStatus() != ContractEntryReviewStatus.CHANGES_REQUESTED) {
            throw new BusinessValidationException("CONTRACT_NOT_SUBMITTABLE",
                    "Contract '" + entry.getTitle() + "' is in status " + entry.getReviewStatus() + " and cannot be submitted.");
        }

        // Validate contract dates for all submitted contracts (both manual and extracted)
        LocalDate signingDate = entry.getCommonData() != null && entry.getCommonData().getSigningDate() != null
                ? entry.getCommonData().getSigningDate().getValue()
                : entry.getDocumentDate();
        LocalDate effectiveDate = entry.getCommonData() != null && entry.getCommonData().getEffectiveDate() != null
                ? entry.getCommonData().getEffectiveDate().getValue()
                : null;
        LocalDate expiryDate = entry.getCommonData() != null && entry.getCommonData().getExpiryDate() != null
                ? entry.getCommonData().getExpiryDate().getValue()
                : null;

        try {
            validateContractDates(signingDate, effectiveDate, expiryDate);
        } catch (BusinessValidationException ex) {
            throw new BusinessValidationException("INVALID_CONTRACT_DATES",
                    "Hợp đồng '" + entry.getTitle() + "' có ngày không hợp lệ: " + ex.getMessage());
        }

        // Manual contracts bypass AI verification, but require meaningful data
        if (entry.getDataEntryMethod() == ContractDataEntryMethod.MANUAL) {
            if (!hasMeaningfulManualContractData(entry)) {
                throw new BusinessValidationException("MANUAL_CONTRACT_EMPTY",
                        "Hợp đồng nhập thủ công '" + entry.getTitle() + "' chưa có số liệu. Vui lòng nhập thông tin trước khi gửi duyệt.");
            }
            return;
        }

        // If extraction is completed, enforce data quality validation
        if (entry.getExtractionStatus() == ContractExtractionStatus.COMPLETED) {
            if (entry.getTypeValidationStatus() == TypeValidationStatus.MISMATCH) {
                throw new BusinessValidationException("TYPE_MISMATCH_UNRESOLVED",
                        "Contract '" + entry.getTitle() + "' has an unresolved type mismatch. Please resolve contract type before submission.");
            }

            if (!Boolean.TRUE.equals(entry.getCompanyMatchConfirmed()) && entry.getCompanyMatchStatus() != CompanyMatchStatus.MATCH) {
                throw new BusinessValidationException("COMPANY_MATCH_UNCONFIRMED",
                        "Contract '" + entry.getTitle() + "' requires company match confirmation before submission.");
            }

            // Verify no unresolved NEEDS_REVIEW + UNVERIFIED fields exist
            if (hasUnresolvedNeedsReview(entry)) {
                throw new BusinessValidationException("UNRESOLVED_NEEDS_REVIEW",
                        "Contract '" + entry.getTitle() + "' contains fields flagged as NEEDS_REVIEW that have not been verified.");
            }

            // Verify all fields are verified by staff before submission
            if (hasUnverifiedFields(entry)) {
                throw new BusinessValidationException("UNVERIFIED_FIELDS",
                        "Hợp đồng '" + entry.getTitle() + "' chưa hoàn tất xác thực các trường dữ liệu trước khi nộp cho Manager.");
            }
        }
    }

    private boolean hasUnresolvedNeedsReview(ContractEntry entry) {
        if (entry.getCommonData() != null) {
            CommonContractData c = entry.getCommonData();
            if (isFieldUnresolved(c.getContractTitle()) || isFieldUnresolved(c.getContractNumber())
                    || isFieldUnresolved(c.getSigningDate()) || isFieldUnresolved(c.getEffectiveDate())
                    || isFieldUnresolved(c.getExpiryDate()) || isFieldUnresolved(c.getTerm())
                    || isFieldUnresolved(c.getPurpose()) || isFieldUnresolved(c.getContractValue())
                    || isFieldUnresolved(c.getGoverningLaw())) {
                return true;
            }
            if (c.getParties() != null) {
                for (ContractParty p : c.getParties()) {
                    if (p.getQualityStatus() == ContractFieldQualityStatus.NEEDS_REVIEW && p.getVerificationStatus() == ContractFieldVerificationStatus.UNVERIFIED) {
                        return true;
                    }
                }
            }
        }
        if (entry.getCooperationAgreementData() != null) {
            CooperationAgreementData c = entry.getCooperationAgreementData();
            if (isFieldUnresolved(c.getCooperationScope()) || isFieldUnresolved(c.getInformationSharing())
                    || isFieldUnresolved(c.getCoordinationMechanism())) {
                return true;
            }
            if (c.getResponsibilities() != null) {
                for (PartyResponsibility r : c.getResponsibilities()) {
                    if (r.getQualityStatus() == ContractFieldQualityStatus.NEEDS_REVIEW && r.getVerificationStatus() == ContractFieldVerificationStatus.UNVERIFIED) return true;
                }
            }
            if (c.getResourceCommitments() != null) {
                for (ResourceCommitment rc : c.getResourceCommitments()) {
                    if (rc.getQualityStatus() == ContractFieldQualityStatus.NEEDS_REVIEW && rc.getVerificationStatus() == ContractFieldVerificationStatus.UNVERIFIED) return true;
                }
            }
            if (c.getCooperationActivities() != null) {
                for (ExtractedContractField<String> a : c.getCooperationActivities()) {
                    if (isFieldUnresolved(a)) return true;
                }
            }
            if (c.getTerminationConditions() != null) {
                for (ExtractedContractField<String> tc : c.getTerminationConditions()) {
                    if (isFieldUnresolved(tc)) return true;
                }
            }
        }
        if (entry.getPartnershipAgreementData() != null) {
            PartnershipAgreementData p = entry.getPartnershipAgreementData();
            if (isFieldUnresolved(p.getPartnershipScope()) || isFieldUnresolved(p.getBenefitSharing())
                    || isFieldUnresolved(p.getSalesOrMarketRights()) || isFieldUnresolved(p.getExclusivity())
                    || isFieldUnresolved(p.getRelationshipGovernance())) {
                return true;
            }
            if (p.getPartnerRoles() != null) {
                for (PartnerRole r : p.getPartnerRoles()) {
                    if (r.getQualityStatus() == ContractFieldQualityStatus.NEEDS_REVIEW && r.getVerificationStatus() == ContractFieldVerificationStatus.UNVERIFIED) {
                        return true;
                    }
                }
            }
            if (p.getMutualCommitments() != null) {
                for (MutualCommitment mc : p.getMutualCommitments()) {
                    if (mc.getQualityStatus() == ContractFieldQualityStatus.NEEDS_REVIEW && mc.getVerificationStatus() == ContractFieldVerificationStatus.UNVERIFIED) {
                        return true;
                    }
                }
            }
            if (p.getPerformanceRequirements() != null) {
                for (PerformanceRequirement pr : p.getPerformanceRequirements()) {
                    if (pr.getQualityStatus() == ContractFieldQualityStatus.NEEDS_REVIEW && pr.getVerificationStatus() == ContractFieldVerificationStatus.UNVERIFIED) {
                        return true;
                    }
                }
            }
            if (p.getTerminationConditions() != null) {
                for (ExtractedContractField<String> tc : p.getTerminationConditions()) {
                    if (isFieldUnresolved(tc)) {
                        return true;
                    }
                }
            }
        }
        if (entry.getJointVentureAgreementData() != null) {
            JointVentureAgreementData jv = entry.getJointVentureAgreementData();
            if (isFieldUnresolved(jv.getJointVentureName()) || isFieldUnresolved(jv.getJointVenturePurpose())
                    || isFieldUnresolved(jv.getGovernanceStructure())) {
                return true;
            }
            if (jv.getCapitalContributions() != null) {
                for (CapitalContribution cc : jv.getCapitalContributions()) {
                    if (cc.getQualityStatus() == ContractFieldQualityStatus.NEEDS_REVIEW && cc.getVerificationStatus() == ContractFieldVerificationStatus.UNVERIFIED) return true;
                }
            }
            if (jv.getOwnershipPercentages() != null) {
                for (OwnershipPercentage op : jv.getOwnershipPercentages()) {
                    if (op.getQualityStatus() == ContractFieldQualityStatus.NEEDS_REVIEW && op.getVerificationStatus() == ContractFieldVerificationStatus.UNVERIFIED) return true;
                }
            }
            if (jv.getVotingRights() != null) {
                for (VotingRight vr : jv.getVotingRights()) {
                    if (vr.getQualityStatus() == ContractFieldQualityStatus.NEEDS_REVIEW && vr.getVerificationStatus() == ContractFieldVerificationStatus.UNVERIFIED) return true;
                }
            }
            if (jv.getProfitDistribution() != null) {
                for (DistributionShare ds : jv.getProfitDistribution()) {
                    if (ds.getQualityStatus() == ContractFieldQualityStatus.NEEDS_REVIEW && ds.getVerificationStatus() == ContractFieldVerificationStatus.UNVERIFIED) return true;
                }
            }
            if (jv.getLossSharing() != null) {
                for (DistributionShare ls : jv.getLossSharing()) {
                    if (ls.getQualityStatus() == ContractFieldQualityStatus.NEEDS_REVIEW && ls.getVerificationStatus() == ContractFieldVerificationStatus.UNVERIFIED) return true;
                }
            }
            if (jv.getManagementAppointments() != null) {
                for (ManagementAppointment ma : jv.getManagementAppointments()) {
                    if (ma.getQualityStatus() == ContractFieldQualityStatus.NEEDS_REVIEW && ma.getVerificationStatus() == ContractFieldVerificationStatus.UNVERIFIED) return true;
                }
            }
            if (jv.getDecisionMakingRules() != null) {
                for (ExtractedContractField<String> dmr : jv.getDecisionMakingRules()) {
                    if (isFieldUnresolved(dmr)) return true;
                }
            }
            if (jv.getExitConditions() != null) {
                for (ExtractedContractField<String> ec : jv.getExitConditions()) {
                    if (isFieldUnresolved(ec)) return true;
                }
            }
            if (jv.getTransferRestrictions() != null) {
                for (ExtractedContractField<String> tr : jv.getTransferRestrictions()) {
                    if (isFieldUnresolved(tr)) return true;
                }
            }
        }
        if (entry.getBusinessCooperationContractData() != null) {
            BusinessCooperationContractData bcc = entry.getBusinessCooperationContractData();
            if (isFieldUnresolved(bcc.getBusinessScope()) || isFieldUnresolved(bcc.getManagementMechanism())
                    || isFieldUnresolved(bcc.getFinancialManagement()) || isFieldUnresolved(bcc.getAssetOwnership())
                    || isFieldUnresolved(bcc.getTerminationSettlement())) {
                return true;
            }
            if (bcc.getContributions() != null) {
                for (BccContribution c : bcc.getContributions()) {
                    if (c.getQualityStatus() == ContractFieldQualityStatus.NEEDS_REVIEW && c.getVerificationStatus() == ContractFieldVerificationStatus.UNVERIFIED) return true;
                }
            }
            if (bcc.getContributionRatios() != null) {
                for (ContributionRatio cr : bcc.getContributionRatios()) {
                    if (cr.getQualityStatus() == ContractFieldQualityStatus.NEEDS_REVIEW && cr.getVerificationStatus() == ContractFieldVerificationStatus.UNVERIFIED) return true;
                }
            }
            if (bcc.getRevenueSharing() != null) {
                for (SharingArrangement sa : bcc.getRevenueSharing()) {
                    if (sa.getQualityStatus() == ContractFieldQualityStatus.NEEDS_REVIEW && sa.getVerificationStatus() == ContractFieldVerificationStatus.UNVERIFIED) return true;
                }
            }
            if (bcc.getProfitSharing() != null) {
                for (SharingArrangement sa : bcc.getProfitSharing()) {
                    if (sa.getQualityStatus() == ContractFieldQualityStatus.NEEDS_REVIEW && sa.getVerificationStatus() == ContractFieldVerificationStatus.UNVERIFIED) return true;
                }
            }
            if (bcc.getCostSharing() != null) {
                for (SharingArrangement sa : bcc.getCostSharing()) {
                    if (sa.getQualityStatus() == ContractFieldQualityStatus.NEEDS_REVIEW && sa.getVerificationStatus() == ContractFieldVerificationStatus.UNVERIFIED) return true;
                }
            }
            if (bcc.getLossSharing() != null) {
                for (SharingArrangement sa : bcc.getLossSharing()) {
                    if (sa.getQualityStatus() == ContractFieldQualityStatus.NEEDS_REVIEW && sa.getVerificationStatus() == ContractFieldVerificationStatus.UNVERIFIED) return true;
                }
            }
            if (bcc.getRightsAndObligations() != null) {
                for (PartyRightsAndObligations ro : bcc.getRightsAndObligations()) {
                    if (ro.getQualityStatus() == ContractFieldQualityStatus.NEEDS_REVIEW && ro.getVerificationStatus() == ContractFieldVerificationStatus.UNVERIFIED) return true;
                }
            }
        }
        return false;
    }

    private boolean hasFieldValue(ExtractedContractField<?> field) {
        if (field == null || field.getValue() == null) return false;
        if (field.getValue() instanceof String s) {
            return !s.isBlank();
        }
        return true;
    }

    private boolean isFieldUnresolved(ExtractedContractField<?> field) {
        return hasFieldValue(field)
                && field.getQualityStatus() == ContractFieldQualityStatus.NEEDS_REVIEW
                && field.getVerificationStatus() == ContractFieldVerificationStatus.UNVERIFIED;
    }

    private boolean hasUnverifiedFields(ContractEntry entry) {
        if (entry.getCommonData() != null) {
            CommonContractData c = entry.getCommonData();
            if (isFieldUnverified(c.getContractNumber())
                    || isFieldUnverified(c.getSigningDate()) || isFieldUnverified(c.getEffectiveDate())
                    || isFieldUnverified(c.getExpiryDate()) || isFieldUnverified(c.getTerm())
                    || isFieldUnverified(c.getPurpose()) || isFieldUnverified(c.getContractValue())
                    || isFieldUnverified(c.getGoverningLaw())) {
                return true;
            }
            if (c.getParties() != null) {
                for (ContractParty p : c.getParties()) {
                    if (p.getVerificationStatus() != ContractFieldVerificationStatus.VERIFIED) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean isFieldUnverified(ExtractedContractField<?> field) {
        return hasFieldValue(field) && field.getVerificationStatus() != ContractFieldVerificationStatus.VERIFIED;
    }

    private void validateContractEditable(ContractResearch research, ContractEntry entry) {
        if (entry.getReviewStatus() == ContractEntryReviewStatus.APPROVED) {
            throw new BusinessValidationException("CONTRACT_APPROVED_IMMUTABLE", "Approved contract is immutable and cannot be modified.");
        }
        if (entry.getReviewStatus() == ContractEntryReviewStatus.PENDING_REVIEW) {
            throw new BusinessValidationException("CONTRACT_IN_REVIEW", "Contract is currently under Manager review and cannot be modified.");
        }
        if (research != null && research.getStatus() != null && research.getStatus() != ContractResearchStatus.DRAFT && research.getStatus() != ContractResearchStatus.CHANGES_REQUESTED) {
            throw new BusinessValidationException("RESEARCH_NOT_EDITABLE", "Research is currently not in an editable state.");
        }
    }

    private ExtractedContractField<String> createManualStringField(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return ExtractedContractField.<String>builder()
                .value(value.trim())
                .inputMethod(ContractFieldInputMethod.MANUAL)
                .qualityStatus(ContractFieldQualityStatus.VALID)
                .build();
    }

    private ExtractedContractField<LocalDate> createManualLocalDateField(LocalDate value) {
        if (value == null) {
            return null;
        }
        return ExtractedContractField.<LocalDate>builder()
                .value(value)
                .inputMethod(ContractFieldInputMethod.MANUAL)
                .qualityStatus(ContractFieldQualityStatus.VALID)
                .build();
    }

    private ExtractedContractField<ContractValue> createManualContractValue(BigDecimal amount, String currency, String rawText) {
        boolean hasAmount = amount != null;
        boolean hasRaw = StringUtils.hasText(rawText);
        if (!hasAmount && !hasRaw) {
            return null;
        }
        ContractValue cv = ContractValue.builder()
                .amount(amount)
                .currency(StringUtils.hasText(currency) ? currency.trim() : "VND")
                .rawAmountText(hasRaw ? rawText.trim() : null)
                .build();
        return ExtractedContractField.<ContractValue>builder()
                .value(cv)
                .inputMethod(ContractFieldInputMethod.MANUAL)
                .qualityStatus(ContractFieldQualityStatus.VALID)
                .build();
    }

    public boolean hasMeaningfulManualContractData(ContractEntry entry) {
        if (entry == null || entry.getCommonData() == null) {
            return false;
        }
        CommonContractData common = entry.getCommonData();
        if (common.getContractNumber() != null && StringUtils.hasText(common.getContractNumber().getValue())) {
            return true;
        }
        if (common.getSigningDate() != null && common.getSigningDate().getValue() != null) {
            return true;
        }
        if (common.getEffectiveDate() != null && common.getEffectiveDate().getValue() != null) {
            return true;
        }
        if (common.getExpiryDate() != null && common.getExpiryDate().getValue() != null) {
            return true;
        }
        if (common.getTerm() != null && StringUtils.hasText(common.getTerm().getValue())) {
            return true;
        }
        if (common.getGoverningLaw() != null && StringUtils.hasText(common.getGoverningLaw().getValue())) {
            return true;
        }
        if (common.getPurpose() != null && StringUtils.hasText(common.getPurpose().getValue())) {
            return true;
        }
        if (common.getContractValue() != null && common.getContractValue().getValue() != null) {
            ContractValue cv = common.getContractValue().getValue();
            if (cv.getAmount() != null || StringUtils.hasText(cv.getRawAmountText())) {
                return true;
            }
        }
        if (common.getParties() != null) {
            for (ContractParty p : common.getParties()) {
                if (p != null && (StringUtils.hasText(p.getLegalName()) || StringUtils.hasText(p.getTaxCode()) || StringUtils.hasText(p.getRole()))) {
                    return true;
                }
            }
        }
        return false;
    }

    private void updateProgress(Long taskId, String contractId, ContractExtractionStage stage, int progress) {
        try {
            ContractResearch research = getResearchEntity(taskId);
            ContractEntry entry = findContractOrThrow(research, contractId);
            entry.setExtractionStage(stage);
            entry.setExtractionProgress(progress);
            entry.setUpdatedAt(LocalDateTime.now());
            contractResearchRepository.save(research);
        } catch (Exception e) {
            log.warn("Failed to update progress for task {}, contract {}", taskId, contractId, e);
        }
    }

    private void handleExtractionFailure(Long taskId, String contractId, String errorMessage) {
        try {
            ContractResearch research = getResearchEntity(taskId);
            ContractEntry entry = findContractOrThrow(research, contractId);
            entry.setExtractionStatus(ContractExtractionStatus.FAILED);
            entry.setExtractionErrorCode("EXTRACTION_ERROR");
            entry.setExtractionErrorMessage(errorMessage != null ? errorMessage : "Extraction failed. Please retry.");
            entry.setExtractionCompletedAt(LocalDateTime.now());
            entry.setUpdatedAt(LocalDateTime.now());
            contractResearchRepository.save(research);
        } catch (Exception ex) {
            log.error("Failed to set extraction failure state", ex);
        }
    }

    private String getTargetCompanyName(Long taskId) {
        try {
            return projectTaskRepository.findWithProjectById(taskId)
                    .map(t -> {
                        String profileId = t.getTargetCompanyProfileId() != null ? t.getTargetCompanyProfileId() :
                                (t.getProject() != null ? t.getProject().getTargetCompanyProfileId() : null);
                        if (profileId != null) {
                            return companyProfileRepository.findById(profileId)
                                    .map(p -> p.getIdentity() != null ? p.getIdentity().getLegalName() : null)
                                    .orElse(null);
                        }
                        return null;
                    }).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private ContractResearch getOrCreateResearchEntity(Long taskId) {
        return contractResearchRepository.findByTaskId(taskId)
                .orElseGet(() -> {
                    ProjectTask task = projectTaskRepository.findWithProjectById(taskId).orElse(null);
                    return contractResearchRepository.save(ContractResearch.builder()
                            .taskId(taskId)
                            .projectId(task != null && task.getProject() != null ? task.getProject().getId() : null)
                            .companyProfileId(task != null ? task.getTargetCompanyProfileId() : null)
                            .status(ContractResearchStatus.DRAFT)
                            .contracts(new ArrayList<>())
                            .build());
                });
    }

    private ContractResearch getResearchEntity(Long taskId) {
        return contractResearchRepository.findByTaskId(taskId)
                .orElseThrow(() -> new BusinessValidationException("RESEARCH_NOT_FOUND", "Contract research not found for task " + taskId));
    }

    private ContractEntry findContractOrThrow(ContractResearch research, String contractId) {
        if (research.getContracts() == null) {
            throw new BusinessValidationException("CONTRACT_NOT_FOUND", "Contract not found: " + contractId);
        }
        return research.getContracts().stream()
                .filter(c -> c.getId().equals(contractId))
                .findFirst()
                .orElseThrow(() -> new BusinessValidationException("CONTRACT_NOT_FOUND", "Contract not found: " + contractId));
    }

    private void applyScalarFieldUpdate(ContractEntry entry, String fieldPath, UpdateScalarFieldRequest req) {
        String path = fieldPath != null ? fieldPath.toLowerCase().trim() : "";
        String valStr = req.getValue() != null ? req.getValue().toString() : null;

        if (entry.getCommonData() == null) {
            entry.setCommonData(new CommonContractData());
        }

        if ((path.endsWith("contracttitle") || path.equals("title"))) {
            entry.getCommonData().setContractTitle(ExtractedContractField.<String>builder()
                    .value(valStr)
                    .evidence(req.getEvidence())
                    .sourcePage(req.getSourcePage())
                    .qualityStatus(ContractFieldQualityStatus.VALID)
                    .verificationStatus(ContractFieldVerificationStatus.VERIFIED)
                    .inputMethod(ContractFieldInputMethod.MANUAL)
                    .build());
        } else if (path.endsWith("contractnumber")) {
            entry.getCommonData().setContractNumber(ExtractedContractField.<String>builder()
                    .value(valStr)
                    .evidence(req.getEvidence())
                    .sourcePage(req.getSourcePage())
                    .qualityStatus(ContractFieldQualityStatus.VALID)
                    .verificationStatus(ContractFieldVerificationStatus.VERIFIED)
                    .inputMethod(ContractFieldInputMethod.MANUAL)
                    .build());
        } else if (path.endsWith("signingdate") || path.endsWith("signdate")) {
            LocalDate d = null;
            if (StringUtils.hasText(valStr)) {
                try {
                    d = LocalDate.parse(valStr.trim().substring(0, 10));
                } catch (Exception ignored) {}
            }
            entry.setDocumentDate(d);
            entry.getCommonData().setSigningDate(ExtractedContractField.<LocalDate>builder()
                    .value(d)
                    .evidence(req.getEvidence())
                    .sourcePage(req.getSourcePage())
                    .qualityStatus(ContractFieldQualityStatus.VALID)
                    .verificationStatus(ContractFieldVerificationStatus.VERIFIED)
                    .inputMethod(ContractFieldInputMethod.MANUAL)
                    .build());
        } else if (path.endsWith("effectivedate")) {
            LocalDate d = null;
            if (StringUtils.hasText(valStr)) {
                try {
                    d = LocalDate.parse(valStr.trim().substring(0, 10));
                } catch (Exception ignored) {}
            }
            entry.getCommonData().setEffectiveDate(ExtractedContractField.<LocalDate>builder()
                    .value(d)
                    .evidence(req.getEvidence())
                    .sourcePage(req.getSourcePage())
                    .qualityStatus(ContractFieldQualityStatus.VALID)
                    .verificationStatus(ContractFieldVerificationStatus.VERIFIED)
                    .inputMethod(ContractFieldInputMethod.MANUAL)
                    .build());
        } else if (path.endsWith("expirydate") || path.endsWith("expirationdate")) {
            LocalDate d = null;
            if (StringUtils.hasText(valStr)) {
                try {
                    d = LocalDate.parse(valStr.trim().substring(0, 10));
                } catch (Exception ignored) {}
            }
            entry.getCommonData().setExpiryDate(ExtractedContractField.<LocalDate>builder()
                    .value(d)
                    .evidence(req.getEvidence())
                    .sourcePage(req.getSourcePage())
                    .qualityStatus(ContractFieldQualityStatus.VALID)
                    .verificationStatus(ContractFieldVerificationStatus.VERIFIED)
                    .inputMethod(ContractFieldInputMethod.MANUAL)
                    .build());
        } else if (path.endsWith("contractvalue")) {
            BigDecimal amt = null;
            if (StringUtils.hasText(valStr)) {
                try {
                    String cleanAmt = valStr.replaceAll("[^0-9.]", "");
                    if (StringUtils.hasText(cleanAmt)) {
                        amt = new BigDecimal(cleanAmt);
                    }
                } catch (Exception ignored) {}
            }
            String curr = entry.getCommonData().getContractValue() != null && entry.getCommonData().getContractValue().getValue() != null
                    ? entry.getCommonData().getContractValue().getValue().getCurrency()
                    : "VND";
            ContractValue cv = ContractValue.builder()
                    .amount(amt)
                    .currency(curr)
                    .rawAmountText(valStr)
                    .build();
            entry.getCommonData().setContractValue(ExtractedContractField.<ContractValue>builder()
                    .value(cv)
                    .evidence(req.getEvidence())
                    .sourcePage(req.getSourcePage())
                    .qualityStatus(ContractFieldQualityStatus.VALID)
                    .verificationStatus(ContractFieldVerificationStatus.VERIFIED)
                    .inputMethod(ContractFieldInputMethod.MANUAL)
                    .build());
        } else if (path.endsWith("purpose")) {
            entry.getCommonData().setPurpose(ExtractedContractField.<String>builder()
                    .value(valStr)
                    .evidence(req.getEvidence())
                    .sourcePage(req.getSourcePage())
                    .qualityStatus(ContractFieldQualityStatus.VALID)
                    .verificationStatus(ContractFieldVerificationStatus.VERIFIED)
                    .inputMethod(ContractFieldInputMethod.MANUAL)
                    .build());
        } else if (path.endsWith("governinglaw")) {
            entry.getCommonData().setGoverningLaw(ExtractedContractField.<String>builder()
                    .value(valStr)
                    .evidence(req.getEvidence())
                    .sourcePage(req.getSourcePage())
                    .qualityStatus(ContractFieldQualityStatus.VALID)
                    .verificationStatus(ContractFieldVerificationStatus.VERIFIED)
                    .inputMethod(ContractFieldInputMethod.MANUAL)
                    .build());
        } else if (path.endsWith("term")) {
            entry.getCommonData().setTerm(ExtractedContractField.<String>builder()
                    .value(valStr)
                    .evidence(req.getEvidence())
                    .sourcePage(req.getSourcePage())
                    .qualityStatus(ContractFieldQualityStatus.VALID)
                    .verificationStatus(ContractFieldVerificationStatus.VERIFIED)
                    .inputMethod(ContractFieldInputMethod.MANUAL)
                    .build());
        } else if (path.endsWith("cooperationscope")) {
            if (entry.getCooperationAgreementData() == null) entry.setCooperationAgreementData(new CooperationAgreementData());
            entry.getCooperationAgreementData().setCooperationScope(ExtractedContractField.<String>builder()
                    .value(valStr)
                    .evidence(req.getEvidence())
                    .sourcePage(req.getSourcePage())
                    .qualityStatus(ContractFieldQualityStatus.VALID)
                    .verificationStatus(ContractFieldVerificationStatus.VERIFIED)
                    .inputMethod(ContractFieldInputMethod.MANUAL)
                    .build());
        } else if (path.endsWith("informationsharing")) {
            if (entry.getCooperationAgreementData() == null) entry.setCooperationAgreementData(new CooperationAgreementData());
            entry.getCooperationAgreementData().setInformationSharing(ExtractedContractField.<String>builder()
                    .value(valStr)
                    .evidence(req.getEvidence())
                    .sourcePage(req.getSourcePage())
                    .qualityStatus(ContractFieldQualityStatus.VALID)
                    .verificationStatus(ContractFieldVerificationStatus.VERIFIED)
                    .inputMethod(ContractFieldInputMethod.MANUAL)
                    .build());
        } else if (path.endsWith("coordinationmechanism")) {
            if (entry.getCooperationAgreementData() == null) entry.setCooperationAgreementData(new CooperationAgreementData());
            entry.getCooperationAgreementData().setCoordinationMechanism(ExtractedContractField.<String>builder()
                    .value(valStr)
                    .evidence(req.getEvidence())
                    .sourcePage(req.getSourcePage())
                    .qualityStatus(ContractFieldQualityStatus.VALID)
                    .verificationStatus(ContractFieldVerificationStatus.VERIFIED)
                    .inputMethod(ContractFieldInputMethod.MANUAL)
                    .build());
        } else if (path.endsWith("partnershipscope")) {
            if (entry.getPartnershipAgreementData() == null) entry.setPartnershipAgreementData(new PartnershipAgreementData());
            entry.getPartnershipAgreementData().setPartnershipScope(ExtractedContractField.<String>builder()
                    .value(valStr)
                    .evidence(req.getEvidence())
                    .sourcePage(req.getSourcePage())
                    .qualityStatus(ContractFieldQualityStatus.VALID)
                    .verificationStatus(ContractFieldVerificationStatus.VERIFIED)
                    .inputMethod(ContractFieldInputMethod.MANUAL)
                    .build());
        } else if (path.endsWith("salesormarketrights")) {
            if (entry.getPartnershipAgreementData() == null) entry.setPartnershipAgreementData(new PartnershipAgreementData());
            entry.getPartnershipAgreementData().setSalesOrMarketRights(ExtractedContractField.<String>builder()
                    .value(valStr)
                    .evidence(req.getEvidence())
                    .sourcePage(req.getSourcePage())
                    .qualityStatus(ContractFieldQualityStatus.VALID)
                    .verificationStatus(ContractFieldVerificationStatus.VERIFIED)
                    .inputMethod(ContractFieldInputMethod.MANUAL)
                    .build());
        } else if (path.endsWith("relationshipgovernance")) {
            if (entry.getPartnershipAgreementData() == null) entry.setPartnershipAgreementData(new PartnershipAgreementData());
            entry.getPartnershipAgreementData().setRelationshipGovernance(ExtractedContractField.<String>builder()
                    .value(valStr)
                    .evidence(req.getEvidence())
                    .sourcePage(req.getSourcePage())
                    .qualityStatus(ContractFieldQualityStatus.VALID)
                    .verificationStatus(ContractFieldVerificationStatus.VERIFIED)
                    .inputMethod(ContractFieldInputMethod.MANUAL)
                    .build());
        } else if (path.endsWith("jointventurename")) {
            if (entry.getJointVentureAgreementData() == null) entry.setJointVentureAgreementData(new JointVentureAgreementData());
            entry.getJointVentureAgreementData().setJointVentureName(ExtractedContractField.<String>builder()
                    .value(valStr)
                    .evidence(req.getEvidence())
                    .sourcePage(req.getSourcePage())
                    .qualityStatus(ContractFieldQualityStatus.VALID)
                    .verificationStatus(ContractFieldVerificationStatus.VERIFIED)
                    .inputMethod(ContractFieldInputMethod.MANUAL)
                    .build());
        } else if (path.endsWith("jointventurepurpose")) {
            if (entry.getJointVentureAgreementData() == null) entry.setJointVentureAgreementData(new JointVentureAgreementData());
            entry.getJointVentureAgreementData().setJointVenturePurpose(ExtractedContractField.<String>builder()
                    .value(valStr)
                    .evidence(req.getEvidence())
                    .sourcePage(req.getSourcePage())
                    .qualityStatus(ContractFieldQualityStatus.VALID)
                    .verificationStatus(ContractFieldVerificationStatus.VERIFIED)
                    .inputMethod(ContractFieldInputMethod.MANUAL)
                    .build());
        } else if (path.endsWith("governancestructure")) {
            if (entry.getJointVentureAgreementData() == null) entry.setJointVentureAgreementData(new JointVentureAgreementData());
            entry.getJointVentureAgreementData().setGovernanceStructure(ExtractedContractField.<String>builder()
                    .value(valStr)
                    .evidence(req.getEvidence())
                    .sourcePage(req.getSourcePage())
                    .qualityStatus(ContractFieldQualityStatus.VALID)
                    .verificationStatus(ContractFieldVerificationStatus.VERIFIED)
                    .inputMethod(ContractFieldInputMethod.MANUAL)
                    .build());
        } else if (path.endsWith("businessscope")) {
            if (entry.getBusinessCooperationContractData() == null) entry.setBusinessCooperationContractData(new BusinessCooperationContractData());
            entry.getBusinessCooperationContractData().setBusinessScope(ExtractedContractField.<String>builder()
                    .value(valStr)
                    .evidence(req.getEvidence())
                    .sourcePage(req.getSourcePage())
                    .qualityStatus(ContractFieldQualityStatus.VALID)
                    .verificationStatus(ContractFieldVerificationStatus.VERIFIED)
                    .inputMethod(ContractFieldInputMethod.MANUAL)
                    .build());
        } else if (path.endsWith("managementmechanism")) {
            if (entry.getBusinessCooperationContractData() == null) entry.setBusinessCooperationContractData(new BusinessCooperationContractData());
            entry.getBusinessCooperationContractData().setManagementMechanism(ExtractedContractField.<String>builder()
                    .value(valStr)
                    .evidence(req.getEvidence())
                    .sourcePage(req.getSourcePage())
                    .qualityStatus(ContractFieldQualityStatus.VALID)
                    .verificationStatus(ContractFieldVerificationStatus.VERIFIED)
                    .inputMethod(ContractFieldInputMethod.MANUAL)
                    .build());
        } else if (path.endsWith("financialmanagement")) {
            if (entry.getBusinessCooperationContractData() == null) entry.setBusinessCooperationContractData(new BusinessCooperationContractData());
            entry.getBusinessCooperationContractData().setFinancialManagement(ExtractedContractField.<String>builder()
                    .value(valStr)
                    .evidence(req.getEvidence())
                    .sourcePage(req.getSourcePage())
                    .qualityStatus(ContractFieldQualityStatus.VALID)
                    .verificationStatus(ContractFieldVerificationStatus.VERIFIED)
                    .inputMethod(ContractFieldInputMethod.MANUAL)
                    .build());
        } else if (path.endsWith("assetownership")) {
            if (entry.getBusinessCooperationContractData() == null) entry.setBusinessCooperationContractData(new BusinessCooperationContractData());
            entry.getBusinessCooperationContractData().setAssetOwnership(ExtractedContractField.<String>builder()
                    .value(valStr)
                    .evidence(req.getEvidence())
                    .sourcePage(req.getSourcePage())
                    .qualityStatus(ContractFieldQualityStatus.VALID)
                    .verificationStatus(ContractFieldVerificationStatus.VERIFIED)
                    .inputMethod(ContractFieldInputMethod.MANUAL)
                    .build());
        } else if (path.endsWith("terminationsettlement")) {
            if (entry.getBusinessCooperationContractData() == null) entry.setBusinessCooperationContractData(new BusinessCooperationContractData());
            entry.getBusinessCooperationContractData().setTerminationSettlement(ExtractedContractField.<String>builder()
                    .value(valStr)
                    .evidence(req.getEvidence())
                    .sourcePage(req.getSourcePage())
                    .qualityStatus(ContractFieldQualityStatus.VALID)
                    .verificationStatus(ContractFieldVerificationStatus.VERIFIED)
                    .inputMethod(ContractFieldInputMethod.MANUAL)
                    .build());
        }
    }

    private void applyScalarFieldVerification(ContractEntry entry, String fieldPath) {
        applyScalarFieldVerification(entry, fieldPath, ContractFieldVerificationStatus.VERIFIED);
    }

    private void applyScalarFieldVerification(ContractEntry entry, String fieldPath, ContractFieldVerificationStatus status) {
        String path = fieldPath != null ? fieldPath.toLowerCase().trim() : "";

        if (entry.getCommonData() != null) {
            if (path.endsWith("contracttitle")) {
                if (entry.getCommonData().getContractTitle() == null) {
                    if (status == ContractFieldVerificationStatus.VERIFIED) {
                        entry.getCommonData().setContractTitle(ExtractedContractField.<String>builder().value("").qualityStatus(ContractFieldQualityStatus.VALID).verificationStatus(status).build());
                    }
                } else {
                    entry.getCommonData().getContractTitle().setVerificationStatus(status);
                }
            } else if (path.endsWith("contractnumber")) {
                if (entry.getCommonData().getContractNumber() == null) {
                    if (status == ContractFieldVerificationStatus.VERIFIED) {
                        entry.getCommonData().setContractNumber(ExtractedContractField.<String>builder().value("").qualityStatus(ContractFieldQualityStatus.VALID).verificationStatus(status).build());
                    }
                } else {
                    entry.getCommonData().getContractNumber().setVerificationStatus(status);
                }
            } else if (path.endsWith("signingdate") || path.endsWith("signdate")) {
                if (entry.getCommonData().getSigningDate() == null) {
                    if (status == ContractFieldVerificationStatus.VERIFIED) {
                        entry.getCommonData().setSigningDate(ExtractedContractField.<LocalDate>builder().qualityStatus(ContractFieldQualityStatus.VALID).verificationStatus(status).build());
                    }
                } else {
                    entry.getCommonData().getSigningDate().setVerificationStatus(status);
                }
            } else if (path.endsWith("effectivedate")) {
                if (entry.getCommonData().getEffectiveDate() == null) {
                    if (status == ContractFieldVerificationStatus.VERIFIED) {
                        entry.getCommonData().setEffectiveDate(ExtractedContractField.<LocalDate>builder().qualityStatus(ContractFieldQualityStatus.VALID).verificationStatus(status).build());
                    }
                } else {
                    entry.getCommonData().getEffectiveDate().setVerificationStatus(status);
                }
            } else if (path.endsWith("expirydate") || path.endsWith("expirationdate")) {
                if (entry.getCommonData().getExpiryDate() == null) {
                    if (status == ContractFieldVerificationStatus.VERIFIED) {
                        entry.getCommonData().setExpiryDate(ExtractedContractField.<LocalDate>builder().qualityStatus(ContractFieldQualityStatus.VALID).verificationStatus(status).build());
                    }
                } else {
                    entry.getCommonData().getExpiryDate().setVerificationStatus(status);
                }
            } else if (path.endsWith("term")) {
                if (entry.getCommonData().getTerm() == null) {
                    if (status == ContractFieldVerificationStatus.VERIFIED) {
                        entry.getCommonData().setTerm(ExtractedContractField.<String>builder().value("").qualityStatus(ContractFieldQualityStatus.VALID).verificationStatus(status).build());
                    }
                } else {
                    entry.getCommonData().getTerm().setVerificationStatus(status);
                }
            } else if (path.endsWith("purpose")) {
                if (entry.getCommonData().getPurpose() == null) {
                    if (status == ContractFieldVerificationStatus.VERIFIED) {
                        entry.getCommonData().setPurpose(ExtractedContractField.<String>builder().value("").qualityStatus(ContractFieldQualityStatus.VALID).verificationStatus(status).build());
                    }
                } else {
                    entry.getCommonData().getPurpose().setVerificationStatus(status);
                }
            } else if (path.endsWith("contractvalue")) {
                if (entry.getCommonData().getContractValue() == null) {
                    if (status == ContractFieldVerificationStatus.VERIFIED) {
                        entry.getCommonData().setContractValue(ExtractedContractField.<ContractValue>builder().qualityStatus(ContractFieldQualityStatus.VALID).verificationStatus(status).build());
                    }
                } else {
                    entry.getCommonData().getContractValue().setVerificationStatus(status);
                }
            } else if (path.endsWith("governinglaw")) {
                if (entry.getCommonData().getGoverningLaw() == null) {
                    if (status == ContractFieldVerificationStatus.VERIFIED) {
                        entry.getCommonData().setGoverningLaw(ExtractedContractField.<String>builder().value("").qualityStatus(ContractFieldQualityStatus.VALID).verificationStatus(status).build());
                    }
                } else {
                    entry.getCommonData().getGoverningLaw().setVerificationStatus(status);
                }
            }
        }

        if (entry.getCooperationAgreementData() != null) {
            CooperationAgreementData c = entry.getCooperationAgreementData();
            if (path.endsWith("cooperationscope") && c.getCooperationScope() != null) {
                c.getCooperationScope().setVerificationStatus(status);
            } else if (path.endsWith("informationsharing") && c.getInformationSharing() != null) {
                c.getInformationSharing().setVerificationStatus(status);
            } else if (path.endsWith("coordinationmechanism") && c.getCoordinationMechanism() != null) {
                c.getCoordinationMechanism().setVerificationStatus(status);
            }
        }

        if (entry.getPartnershipAgreementData() != null) {
            PartnershipAgreementData p = entry.getPartnershipAgreementData();
            if (path.endsWith("partnershipscope") && p.getPartnershipScope() != null) {
                p.getPartnershipScope().setVerificationStatus(status);
            } else if (path.endsWith("benefitsharing") && p.getBenefitSharing() != null) {
                p.getBenefitSharing().setVerificationStatus(status);
            } else if (path.endsWith("salesormarketrights") && p.getSalesOrMarketRights() != null) {
                p.getSalesOrMarketRights().setVerificationStatus(status);
            } else if (path.endsWith("relationshipgovernance") && p.getRelationshipGovernance() != null) {
                p.getRelationshipGovernance().setVerificationStatus(status);
            } else if (path.endsWith("exclusivity") && p.getExclusivity() != null) {
                p.getExclusivity().setVerificationStatus(status);
            }
        }

        if (entry.getJointVentureAgreementData() != null) {
            JointVentureAgreementData jv = entry.getJointVentureAgreementData();
            if (path.endsWith("jointventurename") && jv.getJointVentureName() != null) {
                jv.getJointVentureName().setVerificationStatus(status);
            } else if (path.endsWith("jointventurepurpose") && jv.getJointVenturePurpose() != null) {
                jv.getJointVenturePurpose().setVerificationStatus(status);
            } else if (path.endsWith("governancestructure") && jv.getGovernanceStructure() != null) {
                jv.getGovernanceStructure().setVerificationStatus(status);
            }
        }

        if (entry.getBusinessCooperationContractData() != null) {
            BusinessCooperationContractData bcc = entry.getBusinessCooperationContractData();
            if (path.endsWith("businessscope") && bcc.getBusinessScope() != null) {
                bcc.getBusinessScope().setVerificationStatus(status);
            } else if (path.endsWith("managementmechanism") && bcc.getManagementMechanism() != null) {
                bcc.getManagementMechanism().setVerificationStatus(status);
            } else if (path.endsWith("financialmanagement") && bcc.getFinancialManagement() != null) {
                bcc.getFinancialManagement().setVerificationStatus(status);
            } else if (path.endsWith("assetownership") && bcc.getAssetOwnership() != null) {
                bcc.getAssetOwnership().setVerificationStatus(status);
            } else if (path.endsWith("terminationsettlement") && bcc.getTerminationSettlement() != null) {
                bcc.getTerminationSettlement().setVerificationStatus(status);
            }
        }
    }

    private void applyArrayItemUpdate(ContractEntry entry, String fieldPath, String itemId, UpdateArrayItemRequest req) {
        String path = fieldPath != null ? fieldPath.toLowerCase().trim() : "";
        Map<String, Object> payload = req.getItemPayload() != null ? req.getItemPayload() : Map.of();

        if (path.endsWith("parties") && entry.getCommonData() != null && entry.getCommonData().getParties() != null) {
            entry.getCommonData().getParties().stream().filter(p -> p.getId().equals(itemId)).findFirst().ifPresent(p -> {
                if (payload.containsKey("legalName")) p.setLegalName(payload.get("legalName").toString());
                if (payload.containsKey("taxCode")) p.setTaxCode(payload.get("taxCode") != null ? payload.get("taxCode").toString() : null);
                if (payload.containsKey("address")) p.setAddress(payload.get("address") != null ? payload.get("address").toString() : null);
                if (payload.containsKey("representative")) p.setRepresentative(payload.get("representative") != null ? payload.get("representative").toString() : null);
                if (payload.containsKey("role")) p.setRole(payload.get("role") != null ? payload.get("role").toString() : null);
                p.setVerificationStatus(ContractFieldVerificationStatus.VERIFIED);
            });
        } else if (path.endsWith("responsibilities") && entry.getCooperationAgreementData() != null && entry.getCooperationAgreementData().getResponsibilities() != null) {
            entry.getCooperationAgreementData().getResponsibilities().stream().filter(r -> r.getId().equals(itemId)).findFirst().ifPresent(r -> {
                if (payload.containsKey("party")) r.setParty(payload.get("party") != null ? payload.get("party").toString() : r.getParty());
                if (payload.containsKey("responsibility")) r.setResponsibility(payload.get("responsibility") != null ? payload.get("responsibility").toString() : r.getResponsibility());
                r.setVerificationStatus(ContractFieldVerificationStatus.VERIFIED);
            });
        } else if (path.endsWith("resourcecommitments") && entry.getCooperationAgreementData() != null && entry.getCooperationAgreementData().getResourceCommitments() != null) {
            entry.getCooperationAgreementData().getResourceCommitments().stream().filter(rc -> rc.getId().equals(itemId)).findFirst().ifPresent(rc -> {
                if (payload.containsKey("party")) rc.setParty(payload.get("party") != null ? payload.get("party").toString() : rc.getParty());
                if (payload.containsKey("resourceType")) rc.setResourceType(payload.get("resourceType") != null ? payload.get("resourceType").toString() : rc.getResourceType());
                if (payload.containsKey("description")) rc.setDescription(payload.get("description") != null ? payload.get("description").toString() : rc.getDescription());
                rc.setVerificationStatus(ContractFieldVerificationStatus.VERIFIED);
            });
        } else if (path.endsWith("partnerroles") && entry.getPartnershipAgreementData() != null && entry.getPartnershipAgreementData().getPartnerRoles() != null) {
            entry.getPartnershipAgreementData().getPartnerRoles().stream().filter(r -> r.getId().equals(itemId)).findFirst().ifPresent(r -> {
                if (payload.containsKey("party")) r.setParty(payload.get("party") != null ? payload.get("party").toString() : r.getParty());
                if (payload.containsKey("role")) r.setRole(payload.get("role") != null ? payload.get("role").toString() : r.getRole());
                r.setVerificationStatus(ContractFieldVerificationStatus.VERIFIED);
            });
        } else if (path.endsWith("mutualcommitments") && entry.getPartnershipAgreementData() != null && entry.getPartnershipAgreementData().getMutualCommitments() != null) {
            entry.getPartnershipAgreementData().getMutualCommitments().stream().filter(mc -> mc.getId().equals(itemId)).findFirst().ifPresent(mc -> {
                if (payload.containsKey("party")) mc.setParty(payload.get("party") != null ? payload.get("party").toString() : mc.getParty());
                if (payload.containsKey("commitment")) mc.setCommitment(payload.get("commitment") != null ? payload.get("commitment").toString() : mc.getCommitment());
                mc.setVerificationStatus(ContractFieldVerificationStatus.VERIFIED);
            });
        } else if (path.endsWith("performancerequirements") && entry.getPartnershipAgreementData() != null && entry.getPartnershipAgreementData().getPerformanceRequirements() != null) {
            entry.getPartnershipAgreementData().getPerformanceRequirements().stream().filter(pr -> pr.getId().equals(itemId)).findFirst().ifPresent(pr -> {
                if (payload.containsKey("requirement")) pr.setRequirement(payload.get("requirement") != null ? payload.get("requirement").toString() : pr.getRequirement());
                if (payload.containsKey("target")) pr.setTarget(payload.get("target") != null ? payload.get("target").toString() : pr.getTarget());
                pr.setVerificationStatus(ContractFieldVerificationStatus.VERIFIED);
            });
        } else if (path.endsWith("capitalcontributions") && entry.getJointVentureAgreementData() != null && entry.getJointVentureAgreementData().getCapitalContributions() != null) {
            entry.getJointVentureAgreementData().getCapitalContributions().stream().filter(c -> c.getId().equals(itemId)).findFirst().ifPresent(c -> {
                if (payload.containsKey("party")) c.setParty(payload.get("party") != null ? payload.get("party").toString() : c.getParty());
                if (payload.containsKey("contributionType")) c.setContributionType(payload.get("contributionType") != null ? payload.get("contributionType").toString() : c.getContributionType());
                c.setVerificationStatus(ContractFieldVerificationStatus.VERIFIED);
            });
        } else if (path.endsWith("ownershippercentages") && entry.getJointVentureAgreementData() != null && entry.getJointVentureAgreementData().getOwnershipPercentages() != null) {
            entry.getJointVentureAgreementData().getOwnershipPercentages().stream().filter(o -> o.getId().equals(itemId)).findFirst().ifPresent(o -> {
                if (payload.containsKey("party")) o.setParty(payload.get("party") != null ? payload.get("party").toString() : o.getParty());
                o.setVerificationStatus(ContractFieldVerificationStatus.VERIFIED);
            });
        } else if (path.endsWith("votingrights") && entry.getJointVentureAgreementData() != null && entry.getJointVentureAgreementData().getVotingRights() != null) {
            entry.getJointVentureAgreementData().getVotingRights().stream().filter(v -> v.getId().equals(itemId)).findFirst().ifPresent(v -> {
                if (payload.containsKey("party")) v.setParty(payload.get("party") != null ? payload.get("party").toString() : v.getParty());
                if (payload.containsKey("description")) v.setDescription(payload.get("description") != null ? payload.get("description").toString() : v.getDescription());
                v.setVerificationStatus(ContractFieldVerificationStatus.VERIFIED);
            });
        } else if (path.endsWith("profitdistribution") && entry.getJointVentureAgreementData() != null && entry.getJointVentureAgreementData().getProfitDistribution() != null) {
            entry.getJointVentureAgreementData().getProfitDistribution().stream().filter(p -> p.getId().equals(itemId)).findFirst().ifPresent(p -> {
                if (payload.containsKey("party")) p.setParty(payload.get("party") != null ? payload.get("party").toString() : p.getParty());
                p.setVerificationStatus(ContractFieldVerificationStatus.VERIFIED);
            });
        } else if (path.endsWith("losssharing") && entry.getJointVentureAgreementData() != null && entry.getJointVentureAgreementData().getLossSharing() != null) {
            entry.getJointVentureAgreementData().getLossSharing().stream().filter(l -> l.getId().equals(itemId)).findFirst().ifPresent(l -> {
                if (payload.containsKey("party")) l.setParty(payload.get("party") != null ? payload.get("party").toString() : l.getParty());
                l.setVerificationStatus(ContractFieldVerificationStatus.VERIFIED);
            });
        } else if (path.endsWith("managementappointments") && entry.getJointVentureAgreementData() != null && entry.getJointVentureAgreementData().getManagementAppointments() != null) {
            entry.getJointVentureAgreementData().getManagementAppointments().stream().filter(m -> m.getId().equals(itemId)).findFirst().ifPresent(m -> {
                if (payload.containsKey("position")) m.setPosition(payload.get("position") != null ? payload.get("position").toString() : m.getPosition());
                if (payload.containsKey("appointedBy")) m.setAppointedBy(payload.get("appointedBy") != null ? payload.get("appointedBy").toString() : m.getAppointedBy());
                m.setVerificationStatus(ContractFieldVerificationStatus.VERIFIED);
            });
        } else if (path.endsWith("contributions") && entry.getBusinessCooperationContractData() != null && entry.getBusinessCooperationContractData().getContributions() != null) {
            entry.getBusinessCooperationContractData().getContributions().stream().filter(c -> c.getId().equals(itemId)).findFirst().ifPresent(c -> {
                if (payload.containsKey("party")) c.setParty(payload.get("party") != null ? payload.get("party").toString() : c.getParty());
                if (payload.containsKey("contributionType")) c.setContributionType(payload.get("contributionType") != null ? payload.get("contributionType").toString() : c.getContributionType());
                c.setVerificationStatus(ContractFieldVerificationStatus.VERIFIED);
            });
        } else if (path.endsWith("contributionratios") && entry.getBusinessCooperationContractData() != null && entry.getBusinessCooperationContractData().getContributionRatios() != null) {
            entry.getBusinessCooperationContractData().getContributionRatios().stream().filter(cr -> cr.getId().equals(itemId)).findFirst().ifPresent(cr -> {
                if (payload.containsKey("party")) cr.setParty(payload.get("party") != null ? payload.get("party").toString() : cr.getParty());
                cr.setVerificationStatus(ContractFieldVerificationStatus.VERIFIED);
            });
        } else if (path.endsWith("revenuesharing") && entry.getBusinessCooperationContractData() != null && entry.getBusinessCooperationContractData().getRevenueSharing() != null) {
            entry.getBusinessCooperationContractData().getRevenueSharing().stream().filter(rs -> rs.getId().equals(itemId)).findFirst().ifPresent(rs -> {
                if (payload.containsKey("party")) rs.setParty(payload.get("party") != null ? payload.get("party").toString() : rs.getParty());
                rs.setVerificationStatus(ContractFieldVerificationStatus.VERIFIED);
            });
        } else if (path.endsWith("profitsharing") && entry.getBusinessCooperationContractData() != null && entry.getBusinessCooperationContractData().getProfitSharing() != null) {
            entry.getBusinessCooperationContractData().getProfitSharing().stream().filter(ps -> ps.getId().equals(itemId)).findFirst().ifPresent(ps -> {
                if (payload.containsKey("party")) ps.setParty(payload.get("party") != null ? payload.get("party").toString() : ps.getParty());
                ps.setVerificationStatus(ContractFieldVerificationStatus.VERIFIED);
            });
        } else if (path.endsWith("costsharing") && entry.getBusinessCooperationContractData() != null && entry.getBusinessCooperationContractData().getCostSharing() != null) {
            entry.getBusinessCooperationContractData().getCostSharing().stream().filter(cs -> cs.getId().equals(itemId)).findFirst().ifPresent(cs -> {
                if (payload.containsKey("party")) cs.setParty(payload.get("party") != null ? payload.get("party").toString() : cs.getParty());
                cs.setVerificationStatus(ContractFieldVerificationStatus.VERIFIED);
            });
        } else if (path.endsWith("losssharing") && entry.getBusinessCooperationContractData() != null && entry.getBusinessCooperationContractData().getLossSharing() != null) {
            entry.getBusinessCooperationContractData().getLossSharing().stream().filter(ls -> ls.getId().equals(itemId)).findFirst().ifPresent(ls -> {
                if (payload.containsKey("party")) ls.setParty(payload.get("party") != null ? payload.get("party").toString() : ls.getParty());
                ls.setVerificationStatus(ContractFieldVerificationStatus.VERIFIED);
            });
        } else if (path.endsWith("rightsandobligations") && entry.getBusinessCooperationContractData() != null && entry.getBusinessCooperationContractData().getRightsAndObligations() != null) {
            entry.getBusinessCooperationContractData().getRightsAndObligations().stream().filter(ro -> ro.getId().equals(itemId)).findFirst().ifPresent(ro -> {
                if (payload.containsKey("party")) ro.setParty(payload.get("party") != null ? payload.get("party").toString() : ro.getParty());
                ro.setVerificationStatus(ContractFieldVerificationStatus.VERIFIED);
            });
        }
    }

    private void applyArrayItemVerification(ContractEntry entry, String fieldPath, String itemId) {
        applyArrayItemVerification(entry, fieldPath, itemId, ContractFieldVerificationStatus.VERIFIED);
    }

    private void applyArrayItemVerification(ContractEntry entry, String fieldPath, String itemId, ContractFieldVerificationStatus status) {
        String path = fieldPath != null ? fieldPath.toLowerCase().trim() : "";

        if (path.endsWith("parties") && entry.getCommonData() != null && entry.getCommonData().getParties() != null) {
            entry.getCommonData().getParties().stream().filter(p -> p.getId().equals(itemId)).findFirst().ifPresent(p -> {
                p.setVerificationStatus(status);
            });
        } else if (path.endsWith("responsibilities") && entry.getCooperationAgreementData() != null && entry.getCooperationAgreementData().getResponsibilities() != null) {
            entry.getCooperationAgreementData().getResponsibilities().stream().filter(r -> r.getId().equals(itemId)).findFirst().ifPresent(r -> {
                r.setVerificationStatus(status);
            });
        } else if (path.endsWith("resourcecommitments") && entry.getCooperationAgreementData() != null && entry.getCooperationAgreementData().getResourceCommitments() != null) {
            entry.getCooperationAgreementData().getResourceCommitments().stream().filter(rc -> rc.getId().equals(itemId)).findFirst().ifPresent(rc -> {
                rc.setVerificationStatus(status);
            });
        } else if (path.endsWith("partnerroles") && entry.getPartnershipAgreementData() != null && entry.getPartnershipAgreementData().getPartnerRoles() != null) {
            entry.getPartnershipAgreementData().getPartnerRoles().stream().filter(r -> r.getId().equals(itemId)).findFirst().ifPresent(r -> {
                r.setVerificationStatus(status);
            });
        } else if (path.endsWith("mutualcommitments") && entry.getPartnershipAgreementData() != null && entry.getPartnershipAgreementData().getMutualCommitments() != null) {
            entry.getPartnershipAgreementData().getMutualCommitments().stream().filter(mc -> mc.getId().equals(itemId)).findFirst().ifPresent(mc -> {
                mc.setVerificationStatus(status);
            });
        } else if (path.endsWith("performancerequirements") && entry.getPartnershipAgreementData() != null && entry.getPartnershipAgreementData().getPerformanceRequirements() != null) {
            entry.getPartnershipAgreementData().getPerformanceRequirements().stream().filter(pr -> pr.getId().equals(itemId)).findFirst().ifPresent(pr -> {
                pr.setVerificationStatus(status);
            });
        } else if (path.endsWith("capitalcontributions") && entry.getJointVentureAgreementData() != null && entry.getJointVentureAgreementData().getCapitalContributions() != null) {
            entry.getJointVentureAgreementData().getCapitalContributions().stream().filter(c -> c.getId().equals(itemId)).findFirst().ifPresent(c -> {
                c.setVerificationStatus(status);
            });
        } else if (path.endsWith("ownershippercentages") && entry.getJointVentureAgreementData() != null && entry.getJointVentureAgreementData().getOwnershipPercentages() != null) {
            entry.getJointVentureAgreementData().getOwnershipPercentages().stream().filter(o -> o.getId().equals(itemId)).findFirst().ifPresent(o -> {
                o.setVerificationStatus(status);
            });
        } else if (path.endsWith("votingrights") && entry.getJointVentureAgreementData() != null && entry.getJointVentureAgreementData().getVotingRights() != null) {
            entry.getJointVentureAgreementData().getVotingRights().stream().filter(v -> v.getId().equals(itemId)).findFirst().ifPresent(v -> {
                v.setVerificationStatus(status);
            });
        } else if (path.endsWith("profitdistribution") && entry.getJointVentureAgreementData() != null && entry.getJointVentureAgreementData().getProfitDistribution() != null) {
            entry.getJointVentureAgreementData().getProfitDistribution().stream().filter(p -> p.getId().equals(itemId)).findFirst().ifPresent(p -> {
                p.setVerificationStatus(status);
            });
        } else if (path.endsWith("losssharing") && entry.getJointVentureAgreementData() != null && entry.getJointVentureAgreementData().getLossSharing() != null) {
            entry.getJointVentureAgreementData().getLossSharing().stream().filter(l -> l.getId().equals(itemId)).findFirst().ifPresent(l -> {
                l.setVerificationStatus(status);
            });
        } else if (path.endsWith("managementappointments") && entry.getJointVentureAgreementData() != null && entry.getJointVentureAgreementData().getManagementAppointments() != null) {
            entry.getJointVentureAgreementData().getManagementAppointments().stream().filter(m -> m.getId().equals(itemId)).findFirst().ifPresent(m -> {
                m.setVerificationStatus(status);
            });
        } else if (path.endsWith("contributions") && entry.getBusinessCooperationContractData() != null && entry.getBusinessCooperationContractData().getContributions() != null) {
            entry.getBusinessCooperationContractData().getContributions().stream().filter(c -> c.getId().equals(itemId)).findFirst().ifPresent(c -> {
                c.setVerificationStatus(status);
            });
        } else if (path.endsWith("contributionratios") && entry.getBusinessCooperationContractData() != null && entry.getBusinessCooperationContractData().getContributionRatios() != null) {
            entry.getBusinessCooperationContractData().getContributionRatios().stream().filter(cr -> cr.getId().equals(itemId)).findFirst().ifPresent(cr -> {
                cr.setVerificationStatus(status);
            });
        } else if (path.endsWith("revenuesharing") && entry.getBusinessCooperationContractData() != null && entry.getBusinessCooperationContractData().getRevenueSharing() != null) {
            entry.getBusinessCooperationContractData().getRevenueSharing().stream().filter(rs -> rs.getId().equals(itemId)).findFirst().ifPresent(rs -> {
                rs.setVerificationStatus(status);
            });
        } else if (path.endsWith("profitsharing") && entry.getBusinessCooperationContractData() != null && entry.getBusinessCooperationContractData().getProfitSharing() != null) {
            entry.getBusinessCooperationContractData().getProfitSharing().stream().filter(ps -> ps.getId().equals(itemId)).findFirst().ifPresent(ps -> {
                ps.setVerificationStatus(status);
            });
        } else if (path.endsWith("costsharing") && entry.getBusinessCooperationContractData() != null && entry.getBusinessCooperationContractData().getCostSharing() != null) {
            entry.getBusinessCooperationContractData().getCostSharing().stream().filter(cs -> cs.getId().equals(itemId)).findFirst().ifPresent(cs -> {
                cs.setVerificationStatus(status);
            });
        } else if (path.endsWith("losssharing") && entry.getBusinessCooperationContractData() != null && entry.getBusinessCooperationContractData().getLossSharing() != null) {
            entry.getBusinessCooperationContractData().getLossSharing().stream().filter(ls -> ls.getId().equals(itemId)).findFirst().ifPresent(ls -> {
                ls.setVerificationStatus(status);
            });
        } else if (path.endsWith("rightsandobligations") && entry.getBusinessCooperationContractData() != null && entry.getBusinessCooperationContractData().getRightsAndObligations() != null) {
            entry.getBusinessCooperationContractData().getRightsAndObligations().stream().filter(ro -> ro.getId().equals(itemId)).findFirst().ifPresent(ro -> {
                ro.setVerificationStatus(status);
            });
        }
    }

    private ContractResearchResponse toResponse(ContractResearch research) {
        Long activeSubId = null;
        List<String> activeSubmittedIds = new ArrayList<>();
        LocalDateTime submittedAt = research.getSubmittedAt();
        Boolean canRecall = false;
        String activeSubmissionStatus = null;

        try {
            // First try to find IN_REVIEW submission (active review)
            Optional<ProjectTaskSubmission> activeSub = projectTaskSubmissionRepository.findByProjectTask_Id(research.getTaskId()).stream()
                    .filter(s -> s.getStatus() == SubmissionStatus.IN_REVIEW)
                    .findFirst();

            if (activeSub.isEmpty()) {
                // Fallback: look for REVISION_REQUESTED (Manager finished, returned to Staff)
                activeSub = projectTaskSubmissionRepository.findByProjectTask_Id(research.getTaskId()).stream()
                        .filter(s -> s.getStatus() == SubmissionStatus.REVISION_REQUESTED)
                        .findFirst();
            }

            if (activeSub.isPresent()) {
                ProjectTaskSubmission sub = activeSub.get();
                activeSubId = sub.getId();
                activeSubmittedIds = sub.getTargetItemIdList();
                submittedAt = sub.getSubmittedAt();
                activeSubmissionStatus = sub.getStatus().name();

                // canRecall = true only if IN_REVIEW and NO contracts have been reviewed yet
                if (sub.getStatus() == SubmissionStatus.IN_REVIEW) {
                    boolean anyDecisionMade = false;
                    for (String cId : sub.getTargetItemIdList()) {
                        ContractEntry entry = research.getContracts() != null
                                ? research.getContracts().stream().filter(c -> cId.equals(c.getId())).findFirst().orElse(null)
                                : null;
                        if (entry != null && entry.getReviewStatus() != null
                                && entry.getReviewStatus() != ContractEntryReviewStatus.PENDING_REVIEW) {
                            anyDecisionMade = true;
                            break;
                        }
                    }
                    canRecall = !anyDecisionMade;
                }
            }
        } catch (Exception ignored) {}

        return ContractResearchResponse.builder()
                .id(research.getId())
                .taskId(research.getTaskId())
                .projectId(research.getProjectId())
                .companyProfileId(research.getCompanyProfileId())
                .status(research.getStatus())
                .contracts(research.getContracts() != null ? research.getContracts() : new ArrayList<>())
                .activeSubmissionId(activeSubId)
                .activeSubmittedContractIds(activeSubmittedIds)
                .activeSubmissionStatus(activeSubmissionStatus)
                .canRecallSubmission(canRecall)
                .submittedAt(submittedAt)
                .reviewedBy(research.getReviewedBy())
                .reviewedAt(research.getReviewedAt())
                .reviewReason(research.getReviewReason())
                .createdAt(research.getCreatedAt())
                .updatedAt(research.getUpdatedAt())
                .build();
    }
}
