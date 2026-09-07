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
    private final ContractExtractionService extractionService;
    private final ContractExtractionNormalizer normalizer;
    private final ContractCompanyMatcher companyMatcher;
    private final AuditLogService auditLogService;

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

    @Transactional
    public ContractResearchResponse createContractEntry(Long taskId, CreateContractEntryRequest req, Long userId) {
        ContractResearch research = getOrCreateResearchEntity(taskId);

        RawDocument doc = rawDocumentRepository.findById(req.getDocumentId())
                .orElseGet(() -> {
                    try {
                        Long jobId = Long.parseLong(req.getDocumentId());
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

        LocalDate docDate = req.getDocumentDate();

        String docName = (doc.getSource() != null && doc.getSource().getFileName() != null)
                ? doc.getSource().getFileName()
                : "Contract Document.pdf";

        ContractEntry entry = ContractEntry.builder()
                .id(UUID.randomUUID().toString())
                .documentId(doc.getId())
                .documentName(docName)
                .title(req.getTitle().trim())
                .documentDate(docDate)
                .declaredContractType(ContractType.COOPERATION_AGREEMENT)
                .confirmedContractType(ContractType.COOPERATION_AGREEMENT)
                .typeValidationStatus(TypeValidationStatus.MATCH)
                .companyMatchStatus(CompanyMatchStatus.UNKNOWN)
                .companyMatchConfirmed(false)
                .derivedContractStatus(ContractStatus.UNKNOWN)
                .extractionStatus(ContractExtractionStatus.NOT_EXTRACTED)
                .reviewStatus(ContractEntryReviewStatus.DRAFT)
                .reviewHistory(new ArrayList<>())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        research.getContracts().add(entry);
        research = contractResearchRepository.save(research);

        auditLogService.log(userId, AuditAction.CONTRACT_RESEARCH_CREATED, "CONTRACT_ENTRY", entry.getId(),
                "Task " + taskId + ": Created contract entry " + entry.getTitle());

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
    public ContractResearchResponse extractContractEntry(Long taskId, String contractId, Long userId) {
        ContractResearch research = getResearchEntity(taskId);
        ContractEntry entry = findContractOrThrow(research, contractId);

        validateContractEditable(research, entry);

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
            "common.contractNumber", "common.signingDate", "common.effectiveDate",
            "common.expiryDate", "common.contractValue", "common.governingLaw", "common.purpose"
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

        List<ContractEntry> selectedContracts = new ArrayList<>();
        for (String cId : req.getContractEntryIds()) {
            ContractEntry entry = findContractOrThrow(research, cId);
            validateSubmissionEligibility(entry);
            selectedContracts.add(entry);
        }

        // Mark contracts PENDING_REVIEW
        for (ContractEntry c : selectedContracts) {
            c.setReviewStatus(ContractEntryReviewStatus.PENDING_REVIEW);
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
        if (entry.getReviewStatus() != ContractEntryReviewStatus.PENDING_REVIEW) {
            throw new BusinessValidationException("CONTRACT_NOT_PENDING_REVIEW", "Contract is not in PENDING_REVIEW status.");
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

        // Check if all contracts in current submission have been decided
        List<String> submittedIds = submission.getTargetItemIdList();
        boolean allDecided = true;
        boolean anyChangesRequested = false;

        for (String sId : submittedIds) {
            ContractEntry ce = findContractOrThrow(research, sId);
            if (ce.getReviewStatus() == ContractEntryReviewStatus.PENDING_REVIEW) {
                allDecided = false;
            }
            if (ce.getReviewStatus() == ContractEntryReviewStatus.CHANGES_REQUESTED) {
                anyChangesRequested = true;
            }
        }

        if (allDecided) {
            if (anyChangesRequested) {
                submission.setStatus(SubmissionStatus.REVISION_REQUESTED);
            } else {
                submission.setStatus(SubmissionStatus.APPROVED);
            }
            submission.setReviewedAt(LocalDateTime.now());
            projectTaskSubmissionRepository.save(submission);

            // Package Precedence Evaluation
            ContractResearchStatus nextPackageStatus = computePackageStatusPrecedence(research);
            research.setStatus(nextPackageStatus);
            research.setReviewedBy(userId);
            research.setReviewedAt(LocalDateTime.now());

            if (nextPackageStatus == ContractResearchStatus.APPROVED) {
                task.setStatus(TaskStatus.DONE);
                task.setCompletedAt(LocalDateTime.now());
            } else if (nextPackageStatus == ContractResearchStatus.CHANGES_REQUESTED) {
                task.setStatus(TaskStatus.IN_PROGRESS);
                task.setCompletedAt(null);
            }
            projectTaskRepository.save(task);
        }

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

    private ContractResearchStatus computePackageStatusPrecedence(ContractResearch research) {
        if (research.getContracts() == null || research.getContracts().isEmpty()) {
            return ContractResearchStatus.DRAFT;
        }

        boolean anyChangesRequested = false;
        boolean anyPendingReview = false;
        boolean anyApproved = false;

        for (ContractEntry c : research.getContracts()) {
            if (c.getReviewStatus() == ContractEntryReviewStatus.CHANGES_REQUESTED) {
                anyChangesRequested = true;
            } else if (c.getReviewStatus() == ContractEntryReviewStatus.PENDING_REVIEW) {
                anyPendingReview = true;
            } else if (c.getReviewStatus() == ContractEntryReviewStatus.APPROVED) {
                anyApproved = true;
            }
        }

        if (anyChangesRequested) return ContractResearchStatus.CHANGES_REQUESTED;
        if (anyPendingReview) return ContractResearchStatus.SUBMITTED;
        if (anyApproved) return ContractResearchStatus.APPROVED;

        return ContractResearchStatus.DRAFT;
    }

    private void syncTaskAndResearchStatusIfComplete(ContractResearch research) {
        if (research == null || research.getContracts() == null) return;
        ContractResearchStatus computedStatus = computePackageStatusPrecedence(research);
        if (computedStatus != research.getStatus()) {
            research.setStatus(computedStatus);
            contractResearchRepository.save(research);
        }

        if (computedStatus == ContractResearchStatus.APPROVED) {
            projectTaskRepository.findById(research.getTaskId()).ifPresent(task -> {
                if (task.getStatus() != TaskStatus.DONE) {
                    task.setStatus(TaskStatus.DONE);
                    task.setCompletedAt(LocalDateTime.now());
                    projectTaskRepository.save(task);
                    log.info("Auto-synced task {} to DONE as all submitted contracts are approved", task.getId());
                }
            });
        }
    }

    private void validateSubmissionEligibility(ContractEntry entry) {
        if (entry.getReviewStatus() != ContractEntryReviewStatus.DRAFT && entry.getReviewStatus() != ContractEntryReviewStatus.CHANGES_REQUESTED) {
            throw new BusinessValidationException("CONTRACT_NOT_SUBMITTABLE",
                    "Contract '" + entry.getTitle() + "' is in status " + entry.getReviewStatus() + " and cannot be submitted.");
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
                    || isFieldUnverified(c.getExpiryDate())
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
            } else if (path.endsWith("term") && entry.getCommonData().getTerm() != null) {
                entry.getCommonData().getTerm().setVerificationStatus(status);
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

        try {
            Optional<ProjectTaskSubmission> activeSub = projectTaskSubmissionRepository.findByProjectTask_Id(research.getTaskId()).stream()
                    .filter(s -> s.getStatus() == SubmissionStatus.IN_REVIEW)
                    .findFirst();
            if (activeSub.isPresent()) {
                activeSubId = activeSub.get().getId();
                activeSubmittedIds = activeSub.get().getTargetItemIdList();
                submittedAt = activeSub.get().getSubmittedAt();
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
                .submittedAt(submittedAt)
                .reviewedBy(research.getReviewedBy())
                .reviewedAt(research.getReviewedAt())
                .reviewReason(research.getReviewReason())
                .createdAt(research.getCreatedAt())
                .updatedAt(research.getUpdatedAt())
                .build();
    }
}
