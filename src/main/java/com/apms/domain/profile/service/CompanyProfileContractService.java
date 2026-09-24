package com.apms.domain.profile.service;

import com.apms.common.enums.AuditAction;
import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.contract.enums.*;
import com.apms.domain.contract.model.*;
import com.apms.domain.contract.repository.mongo.ContractResearchRepository;
import com.apms.domain.contract.service.ContractExtractionNormalizer;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.CompanyProfileContract;
import com.apms.domain.profile.dto.BatchUpdateCompanyContractsRequest;
import com.apms.domain.profile.dto.CompanyProfileContractDto;
import com.apms.domain.profile.dto.UpdateCompanyProfileContractRequest;
import com.apms.domain.profile.repository.mongo.CompanyProfileContractRepository;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.repository.sql.ProjectTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CompanyProfileContractService {

    private final CompanyProfileContractRepository contractRepository;
    private final ContractResearchRepository contractResearchRepository;
    private final CompanyProfileRepository companyProfileRepository;
    private final ProjectTaskRepository projectTaskRepository;
    private final ContractExtractionNormalizer contractExtractionNormalizer;
    private final AuditLogService auditLogService;

    /**
     * Resolves all alternative string identifiers for a company profile
     * (e.g. Mongo _id, relational UUID companyId, legalName, tradeName).
     */
    public Set<String> resolvePossibleProfileIds(String companyProfileId) {
        Set<String> possibleIds = new LinkedHashSet<>();
        if (!StringUtils.hasText(companyProfileId)) {
            return possibleIds;
        }
        possibleIds.add(companyProfileId.trim());

        try {
            companyProfileRepository.findById(companyProfileId.trim()).ifPresent(p -> {
                if (StringUtils.hasText(p.getCompanyId())) possibleIds.add(p.getCompanyId().trim());
                if (p.getId() != null) possibleIds.add(p.getId().trim());
                if (p.getIdentity() != null) {
                    if (StringUtils.hasText(p.getIdentity().getLegalName())) {
                        possibleIds.add(p.getIdentity().getLegalName().trim());
                    }
                    if (StringUtils.hasText(p.getIdentity().getTradeName())) {
                        possibleIds.add(p.getIdentity().getTradeName().trim());
                    }
                }
            });
            companyProfileRepository.findByCompanyId(companyProfileId.trim()).ifPresent(p -> {
                if (p.getId() != null) possibleIds.add(p.getId().trim());
                if (StringUtils.hasText(p.getCompanyId())) possibleIds.add(p.getCompanyId().trim());
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

        return possibleIds;
    }

    /**
     * Normalizes an input profile identifier to the canonical companyId if available.
     */
    public String resolveCanonicalCompanyProfileId(String inputId) {
        if (!StringUtils.hasText(inputId)) return inputId;
        try {
            Optional<CompanyProfile> byCompanyId = companyProfileRepository.findByCompanyId(inputId.trim());
            if (byCompanyId.isPresent() && StringUtils.hasText(byCompanyId.get().getCompanyId())) {
                return byCompanyId.get().getCompanyId();
            }
            Optional<CompanyProfile> byId = companyProfileRepository.findById(inputId.trim());
            if (byId.isPresent()) {
                CompanyProfile p = byId.get();
                return StringUtils.hasText(p.getCompanyId()) ? p.getCompanyId() : p.getId();
            }
        } catch (Exception ignored) {}
        return inputId.trim();
    }

    /**
     * Strictly read-only query for canonical company profile contracts.
     * Never performs database writes or mutations.
     */
    public List<CompanyProfileContractDto> getContractsForProfile(String companyProfileId) {
        if (!StringUtils.hasText(companyProfileId)) {
            return Collections.emptyList();
        }
        Set<String> possibleIds = resolvePossibleProfileIds(companyProfileId);
        List<CompanyProfileContract> contracts = contractRepository.findByCompanyProfileIdInOrderByCreatedAtDesc(possibleIds);
        return contracts.stream().map(this::toDto).collect(Collectors.toList());
    }

    /**
     * Single Authoritative Promotion:
     * Converts approved contracts from a ContractResearch package into canonical CompanyProfileContract documents.
     * Strictly IDEMPOTENT:
     * - If stable identity (sourceResearchId + sourceContractEntryId) already exists: NO-OP.
     * - NEVER overwrites existing canonical contracts or Manager edits.
     */
    @Transactional
    public int promoteFromApprovedResearch(ContractResearch research) {
        return promoteFromApprovedResearch(research, null);
    }

    @Transactional
    public int promoteFromApprovedResearch(ContractResearch research, String targetCompanyProfileId) {
        if (research == null || research.getContracts() == null || research.getContracts().isEmpty()) {
            return 0;
        }

        String companyProfileId = StringUtils.hasText(targetCompanyProfileId)
                ? resolveCanonicalCompanyProfileId(targetCompanyProfileId)
                : resolveCompanyProfileId(research);

        if (companyProfileId == null) {
            log.warn("Cannot promote ContractResearch {}: companyProfileId could not be resolved", research.getId());
            return 0;
        }

        int promotedCount = 0;
        LocalDateTime now = LocalDateTime.now();

        for (ContractEntry entry : research.getContracts()) {
            if (entry.getId() == null || entry.getId().isBlank()) {
                log.warn("Skipping ContractEntry without stable ID in research {}", research.getId());
                continue;
            }

            // Only promote approved entries
            if (entry.getReviewStatus() != null && entry.getReviewStatus() != ContractEntryReviewStatus.APPROVED) {
                log.debug("Skipping non-approved ContractEntry {} with status {} in research {}",
                        entry.getId(), entry.getReviewStatus(), research.getId());
                continue;
            }

            // Strictly idempotent check: do NOT overwrite existing canonical contract!
            boolean exists = contractRepository.existsBySourceResearchIdAndSourceContractEntryId(
                    research.getId(),
                    entry.getId()
            );

            if (exists) {
                // Idempotent: NO-OP
                log.debug("Canonical contract already exists for research {} entry {}. Skipping promotion.",
                        research.getId(), entry.getId());
                continue;
            }

            // Derive contract status strictly from dates
            LocalDate eff = (entry.getCommonData() != null && entry.getCommonData().getEffectiveDate() != null)
                    ? entry.getCommonData().getEffectiveDate().getValue()
                    : null;
            LocalDate exp = (entry.getCommonData() != null && entry.getCommonData().getExpiryDate() != null)
                    ? entry.getCommonData().getExpiryDate().getValue()
                    : null;
            ContractStatus derivedStatus = contractExtractionNormalizer.deriveContractStatus(eff, exp, false);
            if (derivedStatus == ContractStatus.UNKNOWN && entry.getDerivedContractStatus() != null) {
                derivedStatus = entry.getDerivedContractStatus();
            }

            ContractType resolvedType = entry.getConfirmedContractType() != null
                    ? entry.getConfirmedContractType()
                    : (entry.getDeclaredContractType() != null
                    ? entry.getDeclaredContractType()
                    : entry.getDetectedContractType());

            String resolvedTitle = StringUtils.hasText(entry.getTitle())
                    ? entry.getTitle()
                    : (entry.getCommonData() != null && entry.getCommonData().getContractTitle() != null
                    ? entry.getCommonData().getContractTitle().getValue()
                    : null);

            CompanyProfileContract canonical = CompanyProfileContract.builder()
                    .companyProfileId(companyProfileId)
                    .title(resolvedTitle)
                    .contractType(resolvedType)
                    .derivedContractStatus(derivedStatus)
                    .documentDate(entry.getDocumentDate())
                    .commonData(entry.getCommonData())
                    .cooperationAgreementData(entry.getCooperationAgreementData())
                    .partnershipAgreementData(entry.getPartnershipAgreementData())
                    .jointVentureAgreementData(entry.getJointVentureAgreementData())
                    .businessCooperationContractData(entry.getBusinessCooperationContractData())
                    .sourceType("PROMOTED")
                    .sourceResearchId(research.getId())
                    .sourceContractEntryId(entry.getId())
                    .sourceDocumentId(entry.getDocumentId())
                    .sourceDocumentName(entry.getDocumentName())
                    .projectId(entry.getProjectId() != null ? entry.getProjectId() : research.getProjectId())
                    .taskId(entry.getTaskId() != null ? entry.getTaskId() : research.getTaskId())
                    .createdAt(now)
                    .updatedAt(now)
                    .build();

            contractRepository.save(canonical);
            promotedCount++;
        }

        log.info("Promoted {} canonical contracts for profile {} from research {}",
                promotedCount, companyProfileId, research.getId());
        return promotedCount;
    }

    /**
     * Explicit profile-scoped backfill/repair:
     * Scans ONLY the approved researches belonging to the specified companyProfileId.
     * Creates missing canonical contracts without re-approval or touching research state.
     * Never overwrites existing canonical rows.
     */
    @Transactional
    public int backfillApprovedResearchesForProfile(String companyProfileId) {
        return backfillApprovedResearchesForProfile(companyProfileId, null);
    }

    @Transactional
    public int backfillApprovedResearchesForProfile(String companyProfileId, Long userId) {
        if (!StringUtils.hasText(companyProfileId)) {
            return 0;
        }

        Set<String> possibleIds = resolvePossibleProfileIds(companyProfileId);
        String canonicalProfileId = resolveCanonicalCompanyProfileId(companyProfileId);

        Set<String> processedResearchIds = new HashSet<>();
        List<ContractResearch> approvedResearches = new ArrayList<>();

        // 1. Direct match by companyProfileId in possibleIds and status APPROVED
        for (String pid : possibleIds) {
            List<ContractResearch> list = contractResearchRepository.findByCompanyProfileIdAndStatus(pid, ContractResearchStatus.APPROVED);
            for (ContractResearch cr : list) {
                if (cr.getId() != null && processedResearchIds.add(cr.getId())) {
                    approvedResearches.add(cr);
                }
            }
        }

        // 2. Match by Project IDs in CompanyProfile sourceRefs
        for (String pid : possibleIds) {
            Optional<CompanyProfile> pOpt = companyProfileRepository.findById(pid);
            if (pOpt.isEmpty()) {
                pOpt = companyProfileRepository.findByCompanyId(pid);
            }
            if (pOpt.isPresent() && pOpt.get().getSourceRefs() != null && pOpt.get().getSourceRefs().getProjectIds() != null) {
                for (String projIdStr : pOpt.get().getSourceRefs().getProjectIds()) {
                    try {
                        Long projId = Long.parseLong(projIdStr);
                        List<ContractResearch> list = contractResearchRepository.findByProjectIdAndStatus(projId, ContractResearchStatus.APPROVED);
                        for (ContractResearch cr : list) {
                            if (cr.getId() != null && processedResearchIds.add(cr.getId())) {
                                approvedResearches.add(cr);
                            }
                        }
                    } catch (NumberFormatException ignored) {}
                }
            }
        }

        // 3. Match by Project Task linkage
        try {
            for (String pid : possibleIds) {
                List<ProjectTask> tasks = new ArrayList<>();
                tasks.addAll(projectTaskRepository.findByTargetCompanyProfileId(pid));
                tasks.addAll(projectTaskRepository.findByProject_TargetCompanyProfileId(pid));

                for (ProjectTask task : tasks) {
                    contractResearchRepository.findByTaskId(task.getId()).ifPresent(r -> {
                        if (r.getStatus() == ContractResearchStatus.APPROVED) {
                            if (r.getId() != null && processedResearchIds.add(r.getId())) {
                                approvedResearches.add(r);
                            }
                        }
                    });
                }
            }
        } catch (Exception e) {
            log.warn("Failed to find tasks for backfill of company profile {}: {}", companyProfileId, e.getMessage());
        }

        int totalPromoted = 0;
        for (ContractResearch research : approvedResearches) {
            totalPromoted += promoteFromApprovedResearch(research, canonicalProfileId);
        }

        if (totalPromoted > 0 && userId != null) {
            auditLogService.log(
                    userId,
                    AuditAction.COMPANY_PROFILE_CONTRACTS_BACKFILLED,
                    "CompanyProfileContract",
                    canonicalProfileId,
                    String.format("Backfilled %d missing canonical contracts for company profile %s", totalPromoted, canonicalProfileId)
            );
        }

        return totalPromoted;
    }

    /**
     * Updates an existing canonical contract for a company profile.
     * - Enforces companyProfileId ownership.
     * - Updates editable business fields only.
     * - Recomputes derivedContractStatus strictly from dates.
     * - Keeps contractType view-only (immutable in this task).
     * - Preserves provenance and source evidence.
     */
    @Transactional
    public CompanyProfileContractDto updateContract(
            String companyProfileId,
            String contractId,
            UpdateCompanyProfileContractRequest request,
            Long userId) {

        Set<String> possibleIds = resolvePossibleProfileIds(companyProfileId);
        CompanyProfileContract contract = contractRepository.findByCompanyProfileIdInAndId(possibleIds, contractId)
                .orElseThrow(() -> new BusinessValidationException("Contract not found: " + contractId));

        if (!possibleIds.contains(contract.getCompanyProfileId())) {
            throw new AccessDeniedException("Contract " + contractId + " does not belong to company " + companyProfileId);
        }

        // 1. Update Title and Document Date
        if (request.getTitle() != null) {
            contract.setTitle(request.getTitle().trim());
        }
        if (request.getDocumentDate() != null) {
            contract.setDocumentDate(request.getDocumentDate());
        }

        // 2. Update Common Data Business Fields (Preserving Evidence)
        CommonContractData commonData = contract.getCommonData();
        if (commonData == null) {
            commonData = CommonContractData.builder().build();
            contract.setCommonData(commonData);
        }

        // contractNumber
        if (request.getContractNumber() != null) {
            commonData.setContractNumber(updateStringField(commonData.getContractNumber(), request.getContractNumber()));
        }

        // title in commonData
        if (request.getTitle() != null) {
            commonData.setContractTitle(updateStringField(commonData.getContractTitle(), request.getTitle()));
        }

        // signingDate
        if (request.getSigningDate() != null) {
            commonData.setSigningDate(updateDateField(commonData.getSigningDate(), request.getSigningDate()));
        }

        // effectiveDate
        if (request.getEffectiveDate() != null) {
            commonData.setEffectiveDate(updateDateField(commonData.getEffectiveDate(), request.getEffectiveDate()));
        }

        // expiryDate
        if (request.getExpiryDate() != null) {
            commonData.setExpiryDate(updateDateField(commonData.getExpiryDate(), request.getExpiryDate()));
        }

        // term
        if (request.getTerm() != null) {
            commonData.setTerm(updateStringField(commonData.getTerm(), request.getTerm()));
        }

        // purpose
        if (request.getPurpose() != null) {
            commonData.setPurpose(updateStringField(commonData.getPurpose(), request.getPurpose()));
        }

        // governingLaw
        if (request.getGoverningLaw() != null) {
            commonData.setGoverningLaw(updateStringField(commonData.getGoverningLaw(), request.getGoverningLaw()));
        }

        // contractValue
        if (request.getContractValue() != null) {
            commonData.setContractValue(updateContractValueField(commonData.getContractValue(), request.getContractValue()));
        }

        // parties
        if (request.getParties() != null) {
            updateParties(commonData, request.getParties());
        }

        // 3. Subtype Payloads (Editing existing subtype values without changing contractType)
        if (request.getCooperationAgreementData() != null) {
            contract.setCooperationAgreementData(request.getCooperationAgreementData());
        }
        if (request.getPartnershipAgreementData() != null) {
            contract.setPartnershipAgreementData(request.getPartnershipAgreementData());
        }
        if (request.getJointVentureAgreementData() != null) {
            contract.setJointVentureAgreementData(request.getJointVentureAgreementData());
        }
        if (request.getBusinessCooperationContractData() != null) {
            contract.setBusinessCooperationContractData(request.getBusinessCooperationContractData());
        }

        // 4. RULE 5: Compute derivedContractStatus strictly from dates (Manager cannot edit directly)
        LocalDate eff = commonData.getEffectiveDate() != null ? commonData.getEffectiveDate().getValue() : null;
        LocalDate exp = commonData.getExpiryDate() != null ? commonData.getExpiryDate().getValue() : null;
        ContractStatus computedStatus = contractExtractionNormalizer.deriveContractStatus(eff, exp, false);
        contract.setDerivedContractStatus(computedStatus);

        // 5. RULE 10: Preserve sourceType and provenance intact
        contract.setUpdatedAt(LocalDateTime.now());
        contract.setLastModifiedBy(userId);

        CompanyProfileContract saved = contractRepository.save(contract);

        if (userId != null) {
            auditLogService.log(
                    userId,
                    AuditAction.COMPANY_PROFILE_CONTRACT_UPDATED,
                    "CompanyProfileContract",
                    saved.getId(),
                    String.format("Updated canonical contract %s for company profile %s: title='%s', status=%s",
                            saved.getId(), companyProfileId, saved.getTitle(), saved.getDerivedContractStatus())
            );
        }

        return toDto(saved);
    }

    /**
     * Batch update multiple canonical contracts for a company profile.
     */
    @Transactional
    public List<CompanyProfileContractDto> batchUpdateContracts(
            String companyProfileId,
            BatchUpdateCompanyContractsRequest request,
            Long userId) {

        if (request == null || request.getContracts() == null || request.getContracts().isEmpty()) {
            return getContractsForProfile(companyProfileId);
        }

        List<CompanyProfileContractDto> results = new ArrayList<>();
        for (UpdateCompanyProfileContractRequest updateReq : request.getContracts()) {
            if (updateReq == null || updateReq.getId() == null || updateReq.getId().isBlank()) {
                continue;
            }
            results.add(updateContract(companyProfileId, updateReq.getId(), updateReq, userId));
        }

        return results;
    }

    private String resolveCompanyProfileId(ContractResearch research) {
        if (research == null) return null;

        if (StringUtils.hasText(research.getCompanyProfileId())) {
            return resolveCanonicalCompanyProfileId(research.getCompanyProfileId());
        }
        if (research.getTaskId() != null) {
            String fromTask = projectTaskRepository.findWithProjectById(research.getTaskId())
                    .map(t -> StringUtils.hasText(t.getTargetCompanyProfileId())
                            ? t.getTargetCompanyProfileId()
                            : (t.getProject() != null && StringUtils.hasText(t.getProject().getTargetCompanyProfileId())
                            ? t.getProject().getTargetCompanyProfileId() : null))
                    .orElse(null);
            if (StringUtils.hasText(fromTask)) {
                return resolveCanonicalCompanyProfileId(fromTask);
            }
        }
        if (research.getProjectId() != null) {
            List<CompanyProfile> profiles = companyProfileRepository.findByProjectId(String.valueOf(research.getProjectId()));
            if (!profiles.isEmpty()) {
                CompanyProfile p = profiles.get(0);
                return StringUtils.hasText(p.getCompanyId()) ? p.getCompanyId() : p.getId();
            }
        }
        return null;
    }

    private ExtractedContractField<String> updateStringField(ExtractedContractField<String> existing, String newValue) {
        String trimmed = newValue != null ? newValue.trim() : "";
        if (existing == null) {
            return ExtractedContractField.<String>builder()
                    .value(trimmed)
                    .inputMethod(ContractFieldInputMethod.MANUAL)
                    .qualityStatus(ContractFieldQualityStatus.VALID)
                    .build();
        }
        existing.setValue(trimmed);
        existing.setInputMethod(ContractFieldInputMethod.MANUAL);
        return existing;
    }

    private ExtractedContractField<LocalDate> updateDateField(ExtractedContractField<LocalDate> existing, LocalDate newDate) {
        if (existing == null) {
            return ExtractedContractField.<LocalDate>builder()
                    .value(newDate)
                    .inputMethod(ContractFieldInputMethod.MANUAL)
                    .qualityStatus(ContractFieldQualityStatus.VALID)
                    .build();
        }
        existing.setValue(newDate);
        existing.setInputMethod(ContractFieldInputMethod.MANUAL);
        return existing;
    }

    private ExtractedContractField<ContractValue> updateContractValueField(
            ExtractedContractField<ContractValue> existing,
            ContractValue newValue) {

        if (existing == null) {
            return ExtractedContractField.<ContractValue>builder()
                    .value(newValue)
                    .inputMethod(ContractFieldInputMethod.MANUAL)
                    .qualityStatus(ContractFieldQualityStatus.VALID)
                    .build();
        }
        existing.setValue(newValue);
        existing.setInputMethod(ContractFieldInputMethod.MANUAL);
        return existing;
    }

    private void updateParties(CommonContractData commonData, List<ContractParty> newParties) {
        Map<String, ContractParty> existingMap = new HashMap<>();
        if (commonData.getParties() != null) {
            for (ContractParty p : commonData.getParties()) {
                if (p.getId() != null) {
                    existingMap.put(p.getId(), p);
                }
            }
        }

        List<ContractParty> updatedList = new ArrayList<>();
        for (ContractParty p : newParties) {
            if (p == null) continue;
            ContractParty existing = p.getId() != null ? existingMap.get(p.getId()) : null;
            if (existing != null) {
                // Preserve evidence & metadata, update business values
                existing.setLegalName(p.getLegalName() != null ? p.getLegalName().trim() : "");
                existing.setTaxCode(p.getTaxCode() != null ? p.getTaxCode().trim() : null);
                existing.setAddress(p.getAddress() != null ? p.getAddress().trim() : null);
                existing.setRepresentative(p.getRepresentative() != null ? p.getRepresentative().trim() : null);
                existing.setRole(p.getRole() != null ? p.getRole().trim() : null);
                existing.setInputMethod(ContractFieldInputMethod.MANUAL);
                updatedList.add(existing);
            } else {
                p.setId(StringUtils.hasText(p.getId()) ? p.getId() : UUID.randomUUID().toString());
                p.setInputMethod(ContractFieldInputMethod.MANUAL);
                p.setQualityStatus(ContractFieldQualityStatus.VALID);
                updatedList.add(p);
            }
        }
        commonData.setParties(updatedList);
    }

    public CompanyProfileContractDto toDto(CompanyProfileContract entity) {
        if (entity == null) return null;
        return CompanyProfileContractDto.builder()
                .id(entity.getId())
                .companyProfileId(entity.getCompanyProfileId())
                .title(entity.getTitle())
                .contractType(entity.getContractType())
                .derivedContractStatus(entity.getDerivedContractStatus())
                .documentDate(entity.getDocumentDate())
                .commonData(entity.getCommonData())
                .cooperationAgreementData(entity.getCooperationAgreementData())
                .partnershipAgreementData(entity.getPartnershipAgreementData())
                .jointVentureAgreementData(entity.getJointVentureAgreementData())
                .businessCooperationContractData(entity.getBusinessCooperationContractData())
                .sourceType(entity.getSourceType())
                .sourceResearchId(entity.getSourceResearchId())
                .sourceContractEntryId(entity.getSourceContractEntryId())
                .sourceDocumentId(entity.getSourceDocumentId())
                .sourceDocumentName(entity.getSourceDocumentName())
                .projectId(entity.getProjectId())
                .taskId(entity.getTaskId())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .lastModifiedBy(entity.getLastModifiedBy())
                .build();
    }
}
