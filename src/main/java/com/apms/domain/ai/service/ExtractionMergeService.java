package com.apms.domain.ai.service;

import com.apms.common.enums.AuditAction;
import com.apms.common.enums.CandidateStatus;
import com.apms.common.enums.MergeAction;
import com.apms.common.enums.SubmissionStatus;
import com.apms.common.exception.ResourceNotFoundException;
import com.apms.domain.ai.AiExtractionCache;
import com.apms.domain.ai.dto.*;
import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.ai.repository.mongo.AiExtractionCacheRepository;
import com.apms.domain.ai.util.ExtractionMergeUtil;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.candidate.CompanyCandidate;
import com.apms.domain.candidate.repository.mongo.CompanyCandidateRepository;
import com.apms.domain.company.model.ComplianceInfo;
import com.apms.domain.company.model.FinancialInfo;
import com.apms.domain.company.model.InnovationInfo;
import com.apms.domain.company.model.MarketInfo;
import com.apms.domain.company.model.RiskInfo;
import com.apms.domain.document.ImportJob;
import com.apms.domain.document.repository.sql.ImportJobRepository;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.CompanyProfileUpdateProposal;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import com.apms.domain.profile.repository.mongo.CompanyProfileUpdateProposalRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static com.apms.domain.ai.util.ExtractionMergeUtil.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExtractionMergeService {

    private final AiExtractionCacheRepository extractionCacheRepository;
    private final ImportJobRepository importJobRepository;
    private final CompanyCandidateRepository candidateRepository;
    private final CompanyProfileRepository companyProfileRepository;
    private final CompanyProfileUpdateProposalRepository proposalRepository;
    private final AuditLogService auditLogService;

    // ─────────────────────────────────────────────
    // GENERATE CANDIDATE DRAFT FROM SELECTED EXTRACTIONS (RESEARCH_NEW_COMPANY)
    // ─────────────────────────────────────────────

    public MergeCandidateResponse mergeExtractionsIntoCandidate(
            Long projectId, Long taskId, List<String> extractionIds, String note, Long creatorId) {
        if (extractionIds == null || extractionIds.isEmpty()) {
            throw new IllegalArgumentException("At least one extractionId must be provided");
        }
        if (candidateRepository.existsByTaskIdAndStatus(taskId, CandidateStatus.REVISION_REQUIRED)) {
            throw new BusinessValidationException("This task already has a Candidate requiring revision. Continue editing the returned Candidate before creating another draft.");
        }

        // 1. Load all extractions
        List<AiExtractionCache> extractions = loadExtractions(extractionIds);

        for (AiExtractionCache ex : extractions) {
            if (ex.getQualityStatus() != null && ex.getQualityStatus() != ExtractionQualityStatus.REVIEWED) {
                throw new BusinessValidationException("Extraction result " + ex.getId() + " must be REVIEWED before generating a draft.");
            }
        }

        // 2. Collect source refs
        List<String> sourceDocIds = new ArrayList<>();
        List<String> importJobIds = new ArrayList<>();
        for (AiExtractionCache ex : extractions) {
            if (ex.getRawDocumentId() != null) sourceDocIds.add(ex.getRawDocumentId());
            if (ex.getImportJobId() != null) importJobIds.add(String.valueOf(ex.getImportJobId()));
        }

        // 3. Build merged data and field evidence
        List<FieldEvidence> fieldEvidence = new ArrayList<>();
        MergedCandidateData merged = mergeCandidateFields(extractions, extractionIds, sourceDocIds, importJobIds, fieldEvidence);

        // 4. Count conflicts
        long conflictCount = fieldEvidence.stream().filter(fe -> Boolean.TRUE.equals(fe.getConflict())).count();
        boolean hasConflicts = conflictCount > 0;

        if (hasConflicts) {
            auditLogService.log(creatorId, AuditAction.FIELD_EVIDENCE_CONFLICT_DETECTED, "CompanyCandidate", "merge", conflictCount + " conflict(s) detected during merge");
        }

        LocalDateTime now = LocalDateTime.now();
        CompanyCandidate.Metadata metadata = CompanyCandidate.Metadata.builder()
                .createdBy(String.valueOf(creatorId))
                .createdAt(now)
                .lastModifiedBy(String.valueOf(creatorId))
                .updatedAt(now)
                .build();

        // 5. Build and save a new DRAFT CompanyCandidate for this reviewed extraction set.
        CompanyCandidate candidate = CompanyCandidate.builder()
                .projectId(String.valueOf(projectId))
                .taskId(taskId)
                .extractionIds(extractionIds)
                .sourceDocumentIds(sourceDocIds)
                .status(CandidateStatus.DRAFT)
                .identity(merged.identity)
                .business(merged.business)
                .companySize(merged.companySize)
                .contact(merged.contact)
                .insights(merged.insights)
                .keyPeople(merged.keyPeople)
                .financial(merged.financial)
                .market(merged.market)
                .innovation(merged.innovation)
                .risk(merged.risk)
                .compliance(merged.compliance)
                .lifecycle(CompanyCandidate.Lifecycle.builder().status(CandidateStatus.DRAFT).build())
                .extractionSource(CompanyCandidate.ExtractionSource.builder()
                        .extractionMethod("MULTI_DOCUMENT_MERGE").build())
                .metadata(metadata)
                .revisionNumber(1)
                .build();

        candidate.setFieldResults(initializeCandidateFieldResults(candidate));

        candidate = candidateRepository.save(candidate);

        auditLogService.log(creatorId, AuditAction.EXTRACTION_MERGED_TO_CANDIDATE, "CompanyCandidate", candidate.getId(),
                "Generated candidate draft from " + extractionIds.size() + " selected extraction(s)");

        // 6. Build response with section maps for display
        return MergeCandidateResponse.builder()
                .candidateId(candidate.getId())
                .identity(sectionToMap(merged.identity))
                .business(sectionToMap(merged.business))
                .companySize(sectionToMap(merged.companySize))
                .contact(sectionToMap(merged.contact))
                .insights(sectionToMap(merged.insights))
                .keyPeople(merged.keyPeople)
                .financial(sectionToMap(merged.financial))
                .market(sectionToMap(merged.market))
                .innovation(sectionToMap(merged.innovation))
                .risk(sectionToMap(merged.risk))
                .compliance(sectionToMap(merged.compliance))
                .fieldEvidence(fieldEvidence)
                .hasConflicts(hasConflicts)
                .conflictCount((int) conflictCount)
                .sourceDocumentIds(sourceDocIds)
                .importJobIds(importJobIds)
                .extractionIds(extractionIds)
                .build();
    }

    // ─────────────────────────────────────────────
    // GENERATE PROFILE UPDATE PROPOSAL DRAFT FROM SELECTED EXTRACTIONS (UPDATE_EXISTING_COMPANY)
    // ─────────────────────────────────────────────

    public MergeProposalResponse createProfileUpdateProposalFromExtractions(
            Long projectId, Long taskId, String companyProfileId, List<String> extractionIds, String changeSummary, Long creatorId) {

        if (extractionIds == null || extractionIds.isEmpty()) {
            throw new IllegalArgumentException("At least one extractionId must be provided");
        }

        CompanyProfile currentProfile = companyProfileRepository.findById(companyProfileId)
                .orElseThrow(() -> new ResourceNotFoundException("CompanyProfile not found: " + companyProfileId));
        if (Boolean.TRUE.equals(currentProfile.getIsDeleted())) {
            throw new IllegalStateException("Cannot create proposal for a deleted CompanyProfile");
        }

        List<AiExtractionCache> extractions = loadExtractions(extractionIds);

        for (AiExtractionCache ex : extractions) {
            if (ex.getQualityStatus() != null && ex.getQualityStatus() != ExtractionQualityStatus.REVIEWED) {
                throw new BusinessValidationException("Extraction result " + ex.getId() + " must be REVIEWED before generating a draft.");
            }
        }

        List<String> sourceDocIds = new ArrayList<>();
        List<String> importJobIds = new ArrayList<>();
        for (AiExtractionCache ex : extractions) {
            if (ex.getRawDocumentId() != null) sourceDocIds.add(ex.getRawDocumentId());
            if (ex.getImportJobId() != null) importJobIds.add(String.valueOf(ex.getImportJobId()));
        }

        List<FieldEvidence> fieldEvidence = new ArrayList<>();
        Map<String, Object> proposedIdentity = new LinkedHashMap<>();
        Map<String, Object> proposedBusiness = new LinkedHashMap<>();
        Map<String, Object> proposedContact = new LinkedHashMap<>();
        Map<String, Object> proposedInsights = new LinkedHashMap<>();
        Map<String, Object> proposedFinancial = new LinkedHashMap<>();
        Map<String, Object> proposedMarket = new LinkedHashMap<>();
        Map<String, Object> proposedInnovation = new LinkedHashMap<>();
        Map<String, Object> proposedRisk = new LinkedHashMap<>();
        Map<String, Object> proposedCompliance = new LinkedHashMap<>();

        // Merge extractions against current profile
        mergeProfileFields(extractions, currentProfile, extractionIds, sourceDocIds, importJobIds, fieldEvidence,
                proposedIdentity, proposedBusiness, proposedContact, proposedInsights,
                proposedFinancial, proposedMarket, proposedInnovation, proposedRisk, proposedCompliance);

        long conflictCount = fieldEvidence.stream().filter(fe -> Boolean.TRUE.equals(fe.getConflict())).count();
        boolean hasConflicts = conflictCount > 0;

        if (hasConflicts) {
            auditLogService.log(creatorId, AuditAction.FIELD_EVIDENCE_CONFLICT_DETECTED, "CompanyProfileUpdateProposal",
                    companyProfileId, conflictCount + " conflict(s) detected");
        }

        CompanyProfileUpdateProposal proposal = CompanyProfileUpdateProposal.builder()
                .projectId(projectId)
                .taskId(taskId)
                .companyProfileId(companyProfileId)
                .proposedIdentity(proposedIdentity.isEmpty() ? null : proposedIdentity)
                .proposedBusiness(proposedBusiness.isEmpty() ? null : proposedBusiness)
                .proposedContact(proposedContact.isEmpty() ? null : proposedContact)
                .proposedInsights(proposedInsights.isEmpty() ? null : proposedInsights)
                .proposedFinancial(proposedFinancial.isEmpty() ? null : proposedFinancial)
                .proposedMarket(proposedMarket.isEmpty() ? null : proposedMarket)
                .proposedInnovation(proposedInnovation.isEmpty() ? null : proposedInnovation)
                .proposedRisk(proposedRisk.isEmpty() ? null : proposedRisk)
                .proposedCompliance(proposedCompliance.isEmpty() ? null : proposedCompliance)
                .sourceDocumentIds(sourceDocIds)
                .extractionIds(extractionIds)
                .fieldEvidence(fieldEvidence)
                .hasConflicts(hasConflicts)
                .conflictCount((int) conflictCount)
                .changeSummary(changeSummary)
                .status(SubmissionStatus.DRAFT)
                .submittedBy(creatorId)
                .build();

        proposal = proposalRepository.save(proposal);

        auditLogService.log(creatorId, AuditAction.EXTRACTION_MERGED_TO_PROFILE_PROPOSAL, "CompanyProfileUpdateProposal", proposal.getId(),
                "Merged " + extractionIds.size() + " extraction(s) into proposal for profile " + companyProfileId);

        return MergeProposalResponse.builder()
                .proposalId(proposal.getId())
                .proposedIdentity(proposedIdentity.isEmpty() ? null : proposedIdentity)
                .proposedBusiness(proposedBusiness.isEmpty() ? null : proposedBusiness)
                .proposedContact(proposedContact.isEmpty() ? null : proposedContact)
                .proposedInsights(proposedInsights.isEmpty() ? null : proposedInsights)
                .proposedFinancial(proposedFinancial.isEmpty() ? null : proposedFinancial)
                .proposedMarket(proposedMarket.isEmpty() ? null : proposedMarket)
                .proposedInnovation(proposedInnovation.isEmpty() ? null : proposedInnovation)
                .proposedRisk(proposedRisk.isEmpty() ? null : proposedRisk)
                .proposedCompliance(proposedCompliance.isEmpty() ? null : proposedCompliance)
                .fieldEvidence(fieldEvidence)
                .hasConflicts(hasConflicts)
                .conflictCount((int) conflictCount)
                .sourceDocumentIds(sourceDocIds)
                .importJobIds(importJobIds)
                .extractionIds(extractionIds)
                .status(SubmissionStatus.DRAFT)
                .build();
    }

    // ─────────────────────────────────────────────
    // PRIVATE HELPERS
    // ─────────────────────────────────────────────

    private static final Set<String> STAFF_REVIEWABLE_FIELDS = Set.of(
            "identity.legalName", "identity.tradeName", "identity.taxCode",
            "contact.address", "contact.website", "contact.emails", "contact.phones",
            "business.businessModel", "business.industries", "business.markets", "business.targetCustomers", "business.products",
            "companySize.employeeTier", "companySize.employeeCount", "companySize.revenueTier",
            "insights.strengths", "insights.weaknesses", "insights.opportunities", "insights.threats",
            "financial", "innovation", "market", "risk", "compliance"
    );

    private Map<String, ExtractionFieldResult> initializeCandidateFieldResults(CompanyCandidate candidate) {
        Map<String, ExtractionFieldResult> results = new HashMap<>();
        for (String fieldPath : STAFF_REVIEWABLE_FIELDS) {
            results.put(FieldKeyCodec.encode(fieldPath), ExtractionFieldResult.builder()
                    .fieldName(fieldPath)
                    .value(readEmbeddedField(candidate, fieldPath))
                    .staffReviewStatus(StaffFieldReviewStatus.PENDING)
                    .managerReviewStatus(ExtractionReviewStatus.PENDING)
                    .build());
        }
        return results;
    }

    private Object readEmbeddedField(CompanyCandidate candidate, String fieldName) {
        if (candidate == null || fieldName == null) return null;
        return switch (fieldName) {
            case "identity.legalName" -> candidate.getIdentity() != null ? candidate.getIdentity().getLegalName() : null;
            case "identity.tradeName" -> candidate.getIdentity() != null ? candidate.getIdentity().getTradeName() : null;
            case "identity.taxCode" -> candidate.getIdentity() != null ? candidate.getIdentity().getTaxCode() : null;
            case "contact.address" -> {
                if (candidate.getContact() == null || candidate.getContact().getAddresses() == null || candidate.getContact().getAddresses().isEmpty()) {
                    yield null;
                }
                yield candidate.getContact().getAddresses().get(0).getFullAddress();
            }
            case "contact.website" -> candidate.getContact() != null ? candidate.getContact().getWebsite() : null;
            case "contact.emails" -> candidate.getContact() != null ? candidate.getContact().getEmails() : null;
            case "contact.phones" -> candidate.getContact() != null ? candidate.getContact().getPhones() : null;
            case "business.businessModel" -> candidate.getBusiness() != null ? candidate.getBusiness().getBusinessModel() : null;
            case "business.industries" -> candidate.getBusiness() != null ? candidate.getBusiness().getIndustries() : null;
            case "business.markets" -> candidate.getBusiness() != null ? candidate.getBusiness().getMarkets() : null;
            case "business.targetCustomers" -> candidate.getBusiness() != null ? candidate.getBusiness().getTargetCustomers() : null;
            case "business.products" -> candidate.getBusiness() != null ? candidate.getBusiness().getProducts() : null;
            case "companySize.employeeTier" -> candidate.getCompanySize() != null ? candidate.getCompanySize().getEmployeeTier() : null;
            case "companySize.employeeCount" -> candidate.getCompanySize() != null ? candidate.getCompanySize().getEmployeeCount() : null;
            case "companySize.revenueTier" -> candidate.getCompanySize() != null ? candidate.getCompanySize().getRevenueTier() : null;
            case "insights.strengths" -> candidate.getInsights() != null ? candidate.getInsights().getStrengths() : null;
            case "insights.weaknesses" -> candidate.getInsights() != null ? candidate.getInsights().getWeaknesses() : null;
            case "insights.opportunities" -> candidate.getInsights() != null ? candidate.getInsights().getOpportunities() : null;
            case "insights.threats" -> candidate.getInsights() != null ? candidate.getInsights().getThreats() : null;
            case "financial" -> candidate.getFinancial();
            case "innovation" -> candidate.getInnovation();
            case "market" -> candidate.getMarket();
            case "risk" -> candidate.getRisk();
            case "compliance" -> candidate.getCompliance();
            default -> null;
        };
    }

    private List<AiExtractionCache> loadExtractions(List<String> extractionIds) {
        List<AiExtractionCache> result = new ArrayList<>();
        for (String id : extractionIds) {
            AiExtractionCache cache = extractionCacheRepository.findById(id)
                    .orElseThrow(() -> new ResourceNotFoundException("AiExtractionCache not found: " + id));
            result.add(cache);
        }
        return result;
    }

    private Object getFieldValue(AiExtractionCache ex, String fieldName, Object fallbackValue) {
        if (ex.getLastModifiedBy() != null && !ex.getLastModifiedBy().isBlank()) {
            return fallbackValue;
        }
        if (ex.getFieldResults() != null && ex.getFieldResults().containsKey(fieldName)) {
            ExtractionFieldResult fr = ex.getFieldResults().get(fieldName);
            if (fr.getManagerReviewStatus() == ExtractionReviewStatus.REJECTED) return null;
            if (fr.getManagerReviewStatus() == ExtractionReviewStatus.EDITED) return fr.getStaffReviewedValue();
            if (fr.getManagerReviewStatus() == ExtractionReviewStatus.ACCEPTED) {
                return fr.getStaffReviewedValue() != null ? fr.getStaffReviewedValue() : fr.getValue();
            }
            // If PENDING or NEEDS_REVIEW, return staffReviewedValue if available, else value
            return fr.getStaffReviewedValue() != null ? fr.getStaffReviewedValue() : fr.getValue();
        }
        return fallbackValue;
    }

    @SuppressWarnings("unchecked")
    private List<String> getListFieldValue(AiExtractionCache ex, String fieldName, List<String> fallbackValue) {
        Object val = getFieldValue(ex, fieldName, fallbackValue);
        if (val instanceof List) {
            return (List<String>) val;
        }
        return fallbackValue;
    }

    // ─────────────────────────────────────────────
    // CANDIDATE MERGE LOGIC
    // ─────────────────────────────────────────────

    private MergedCandidateData mergeCandidateFields(List<AiExtractionCache> extractions,
                                                     List<String> extractionIds, List<String> sourceDocIds, List<String> importJobIds, List<FieldEvidence> evidence) {

        String mergedLegalName = null;
        String mergedTradeName = null;
        String mergedTaxCode = null;

        List<String> mergedIndustries = new ArrayList<>();
        String mergedBusinessModel = null;
        List<String> mergedMarkets = new ArrayList<>();
        List<String> mergedTargetCustomers = new ArrayList<>();
        List<CompanyCandidate.Product> mergedProducts = new ArrayList<>();

        String mergedWebsite = null;
        List<String> mergedEmails = new ArrayList<>();
        List<String> mergedPhones = new ArrayList<>();
        String mergedAddress = null;
        String mergedCompanySize = null;
        List<String> mergedKeyPeople = new ArrayList<>();

        List<String> mergedStrengths = new ArrayList<>();
        List<String> mergedWeaknesses = new ArrayList<>();
        List<String> mergedOpportunities = new ArrayList<>();
        List<String> mergedThreats = new ArrayList<>();

        String mergedEmployeeTier = null;
        FinancialInfo mergedFinancial = null;
        MarketInfo mergedMarket = null;
        InnovationInfo mergedInnovation = null;
        RiskInfo mergedRisk = null;
        ComplianceInfo mergedCompliance = null;

        for (AiExtractionCache ex : extractions) {
            if (ex.getExtractedData() == null) continue;
            ExtractedCompanyData data = ex.getExtractedData();
            String exId = ex.getId();
            String docId = ex.getRawDocumentId();
            String jobId = ex.getImportJobId() != null ? String.valueOf(ex.getImportJobId()) : null;

            // legalName
            String exLegalName = (String) getFieldValue(ex, "legalName", data.getLegalName());
            if (!isUnknown(exLegalName)) {
                if (mergedLegalName == null) {
                    mergedLegalName = exLegalName;
                } else if (!isSameValue(mergedLegalName, exLegalName)) {
                    evidence.add(conflictEvidence("identity.legalName", mergedLegalName, exLegalName, docId, jobId, exId,
                            "Conflicting legal names detected"));
                } else {
                    mergedLegalName = bestDisplayValue(mergedLegalName, exLegalName);
                }
            }

            // tradeName
            String exTradeName = (String) getFieldValue(ex, "tradeName", data.getTradeName());
            if (!isUnknown(exTradeName)) {
                mergedTradeName = bestDisplayValue(mergedTradeName, exTradeName);
            }

            // taxCode
            String exTaxCode = (String) getFieldValue(ex, "taxCode", data.getTaxCode());
            if (!isUnknown(exTaxCode)) {
                if (mergedTaxCode == null) {
                    mergedTaxCode = exTaxCode;
                } else if (!isSameValue(mergedTaxCode, exTaxCode)) {
                    evidence.add(conflictEvidence("identity.taxCode", mergedTaxCode, exTaxCode, docId, jobId, exId,
                            "Conflicting tax codes detected"));
                }
            }

            // industries
            mergeStringList(mergedIndustries, getListFieldValue(ex, "industries", data.getIndustries()));

            // businessModel
            String exBusinessModel = (String) getFieldValue(ex, "businessModel", data.getBusinessModel());
            if (!isUnknown(exBusinessModel)) {
                mergedBusinessModel = bestDisplayValue(mergedBusinessModel, exBusinessModel);
            }

            // markets
            mergeStringList(mergedMarkets, getListFieldValue(ex, "markets", data.getMarkets()));
            mergeStringList(mergedTargetCustomers, getListFieldValue(ex, "targetCustomers", data.getTargetCustomers()));
            mergeProducts(mergedProducts, getFieldValue(ex, "products", data.getProducts()));

            // website
            String exWebsite = (String) getFieldValue(ex, "website", data.getWebsite());
            if (!isUnknown(exWebsite)) {
                if (mergedWebsite == null) {
                    mergedWebsite = exWebsite;
                } else if (!normalizeWebsite(mergedWebsite).equals(normalizeWebsite(exWebsite))) {
                    // Keep first, note the alternative
                    evidence.add(FieldEvidence.builder()
                            .fieldPath("contact.website")
                            .proposedValue(exWebsite)
                            .existingValue(mergedWebsite)
                            .mergeAction(MergeAction.KEEP_EXISTING)
                            .conflict(false)
                            .sourceDocumentIds(docId != null ? List.of(docId) : List.of())
                            .importJobIds(jobId != null ? List.of(jobId) : List.of())
                            .extractionIds(List.of(exId))
                            .note("Alternative website found; keeping first valid value")
                            .build());
                }
            }
            mergeStringList(mergedEmails, getListFieldValue(ex, "email", data.getEmail()));
            mergeStringList(mergedPhones, getListFieldValue(ex, "phone", data.getPhone()));

            String exAddress = (String) getFieldValue(ex, "address", data.getAddress());
            if (!isUnknown(exAddress)) {
                mergedAddress = bestDisplayValue(mergedAddress, exAddress);
            }

            // employeeTier
            String exEmployeeTier = (String) getFieldValue(ex, "employeeTier", data.getEmployeeTier());
            if (!isUnknown(exEmployeeTier)) {
                mergedEmployeeTier = bestDisplayValue(mergedEmployeeTier, exEmployeeTier);
            }
            String exCompanySize = (String) getFieldValue(ex, "companySize", data.getCompanySize());
            if (!isUnknown(exCompanySize)) {
                mergedCompanySize = bestDisplayValue(mergedCompanySize, exCompanySize);
            }
            mergeStringList(mergedKeyPeople, getListFieldValue(ex, "keyPeople", data.getKeyPeople()));

            Object exFinancial = getFieldValue(ex, "financial", data.getFinancial());
            FinancialInfo financialInfo = toModel(exFinancial, FinancialInfo.class);
            mergedFinancial = mergeFinancialInfo(mergedFinancial, financialInfo);
            Object exMarket = getFieldValue(ex, "market", data.getMarket());
            MarketInfo marketInfo = toModel(exMarket, MarketInfo.class);
            mergedMarket = mergeMarketInfo(mergedMarket, marketInfo);
            Object exInnovation = getFieldValue(ex, "innovation", data.getInnovation());
            InnovationInfo innovationInfo = toModel(exInnovation, InnovationInfo.class);
            mergedInnovation = mergeInnovationInfo(mergedInnovation, innovationInfo);
            Object exRisk = getFieldValue(ex, "risk", data.getRisk());
            RiskInfo riskInfo = toModel(exRisk, RiskInfo.class);
            mergedRisk = mergeRiskInfo(mergedRisk, riskInfo);
            Object exCompliance = getFieldValue(ex, "compliance", data.getCompliance());
            ComplianceInfo complianceInfo = toModel(exCompliance, ComplianceInfo.class);
            mergedCompliance = mergeComplianceInfo(mergedCompliance, complianceInfo);

            // insights
            mergeStringList(mergedStrengths, getListFieldValue(ex, "strengths", data.getStrengths()));
            mergeStringList(mergedWeaknesses, getListFieldValue(ex, "weaknesses", data.getWeaknesses()));
            mergeStringList(mergedOpportunities, getListFieldValue(ex, "opportunities", data.getOpportunities()));
            mergeStringList(mergedThreats, getListFieldValue(ex, "threats", data.getThreats()));
        }

        // Build evidence for merged lists
        if (!mergedIndustries.isEmpty()) evidence.add(listEvidence("business.industries", mergedIndustries, sourceDocIds, importJobIds, extractionIds));
        if (!mergedMarkets.isEmpty()) evidence.add(listEvidence("business.markets", mergedMarkets, sourceDocIds, importJobIds, extractionIds));
        if (!mergedTargetCustomers.isEmpty()) evidence.add(listEvidence("business.targetCustomers", mergedTargetCustomers, sourceDocIds, importJobIds, extractionIds));
        if (!mergedStrengths.isEmpty()) evidence.add(listEvidence("insights.strengths", mergedStrengths, sourceDocIds, importJobIds, extractionIds));
        if (!mergedWeaknesses.isEmpty()) evidence.add(listEvidence("insights.weaknesses", mergedWeaknesses, sourceDocIds, importJobIds, extractionIds));
        if (!mergedOpportunities.isEmpty()) evidence.add(listEvidence("insights.opportunities", mergedOpportunities, sourceDocIds, importJobIds, extractionIds));
        if (!mergedThreats.isEmpty()) evidence.add(listEvidence("insights.threats", mergedThreats, sourceDocIds, importJobIds, extractionIds));

        CompanyCandidate.Identity identity = CompanyCandidate.Identity.builder()
                .legalName(mergedLegalName)
                .tradeName(mergedTradeName)
                .taxCode(mergedTaxCode)
                .build();

        CompanyCandidate.Business business = CompanyCandidate.Business.builder()
                .industries(mergedIndustries.isEmpty() ? null : mergedIndustries)
                .businessModel(mergedBusinessModel)
                .products(mergedProducts.isEmpty() ? null : mergedProducts)
                .markets(mergedMarkets.isEmpty() ? null : mergedMarkets)
                .targetCustomers(mergedTargetCustomers.isEmpty() ? null : mergedTargetCustomers)
                .build();

        CompanyCandidate.CompanySize companySize = CompanyCandidate.CompanySize.builder()
                .employeeTier(mergedEmployeeTier)
                .revenueTier(mergedCompanySize)
                .build();

        CompanyCandidate.Contact contact = CompanyCandidate.Contact.builder()
                .website(mergedWebsite)
                .emails(mergedEmails.isEmpty() ? null : mergedEmails)
                .phones(mergedPhones.isEmpty() ? null : mergedPhones)
                .addresses(mergedAddress != null ? List.of(CompanyCandidate.Address.builder()
                        .fullAddress(mergedAddress)
                        .build()) : null)
                .build();

        CompanyCandidate.Insights insights = CompanyCandidate.Insights.builder()
                .strengths(mergedStrengths.isEmpty() ? null : mergedStrengths)
                .weaknesses(mergedWeaknesses.isEmpty() ? null : mergedWeaknesses)
                .opportunities(mergedOpportunities.isEmpty() ? null : mergedOpportunities)
                .threats(mergedThreats.isEmpty() ? null : mergedThreats)
                .build();

        return new MergedCandidateData(identity, business, companySize, contact, insights,
                mergedKeyPeople.isEmpty() ? null : mergedKeyPeople, mergedFinancial, mergedMarket, mergedInnovation,
                mergedRisk, mergedCompliance);
    }

    // ─────────────────────────────────────────────
    // PROFILE UPDATE PROPOSAL MERGE LOGIC
    // ─────────────────────────────────────────────

    private void mergeProfileFields(List<AiExtractionCache> extractions, CompanyProfile current,
                                    List<String> extractionIds, List<String> sourceDocIds, List<String> importJobIds,
                                    List<FieldEvidence> evidence,
                                    Map<String, Object> proposedIdentity, Map<String, Object> proposedBusiness,
                                    Map<String, Object> proposedContact, Map<String, Object> proposedInsights,
                                    Map<String, Object> proposedFinancial, Map<String, Object> proposedMarket,
                                    Map<String, Object> proposedInnovation, Map<String, Object> proposedRisk,
                                    Map<String, Object> proposedCompliance) {

        // Merge across all extractions first
        String mergedLegalName = null, mergedTradeName = null, mergedTaxCode = null;
        List<String> mergedIndustries = new ArrayList<>();
        String mergedBusinessModel = null;
        List<String> mergedMarkets = new ArrayList<>();
        List<String> mergedWebsite = new ArrayList<>();
        List<String> mergedStrengths = new ArrayList<>();
        List<String> mergedWeaknesses = new ArrayList<>();
        List<String> mergedOpportunities = new ArrayList<>();
        List<String> mergedThreats = new ArrayList<>();

        for (AiExtractionCache ex : extractions) {
            if (ex.getExtractedData() == null) continue;
            ExtractedCompanyData data = ex.getExtractedData();

            String exLegalName = (String) getFieldValue(ex, "legalName", data.getLegalName());
            if (!isUnknown(exLegalName)) mergedLegalName = bestDisplayValue(mergedLegalName, exLegalName);

            String exTradeName = (String) getFieldValue(ex, "tradeName", data.getTradeName());
            if (!isUnknown(exTradeName)) mergedTradeName = bestDisplayValue(mergedTradeName, exTradeName);

            String exTaxCode = (String) getFieldValue(ex, "taxCode", data.getTaxCode());
            if (!isUnknown(exTaxCode) && mergedTaxCode == null) mergedTaxCode = exTaxCode;

            mergeStringList(mergedIndustries, getListFieldValue(ex, "industries", data.getIndustries()));

            String exBusinessModel = (String) getFieldValue(ex, "businessModel", data.getBusinessModel());
            if (!isUnknown(exBusinessModel)) mergedBusinessModel = bestDisplayValue(mergedBusinessModel, exBusinessModel);

            mergeStringList(mergedMarkets, getListFieldValue(ex, "markets", data.getMarkets()));

            String exWebsite = (String) getFieldValue(ex, "website", data.getWebsite());
            if (!isUnknown(exWebsite)) { if (mergedWebsite.isEmpty()) mergedWebsite.add(exWebsite); }

            mergeStringList(mergedStrengths, getListFieldValue(ex, "strengths", data.getStrengths()));
            mergeStringList(mergedWeaknesses, getListFieldValue(ex, "weaknesses", data.getWeaknesses()));
            mergeStringList(mergedOpportunities, getListFieldValue(ex, "opportunities", data.getOpportunities()));
            mergeStringList(mergedThreats, getListFieldValue(ex, "threats", data.getThreats()));
        }

        // Now compare against current profile and build evidence
        CompanyProfile.Identity curId = current.getIdentity();

        // legalName
        String curLegal = curId != null ? curId.getLegalName() : null;
        if (mergedLegalName != null) {
            if (isUnknown(curLegal)) {
                proposedIdentity.put("legalName", mergedLegalName);
                evidence.add(evidence("identity.legalName", null, mergedLegalName, sourceDocIds, importJobIds, extractionIds, MergeAction.ADD_VALUE, false, null));
            } else if (isSameValue(curLegal, mergedLegalName)) {
                evidence.add(evidence("identity.legalName", curLegal, mergedLegalName, sourceDocIds, importJobIds, extractionIds, MergeAction.KEEP_EXISTING, false, "Same value confirmed"));
            } else {
                evidence.add(evidence("identity.legalName", curLegal, mergedLegalName, sourceDocIds, importJobIds, extractionIds, MergeAction.CONFLICT, true, "Conflicting legal name"));
            }
        }

        // taxCode
        String curTax = curId != null ? curId.getTaxCode() : null;
        if (mergedTaxCode != null) {
            if (isUnknown(curTax)) {
                proposedIdentity.put("taxCode", mergedTaxCode);
                evidence.add(evidence("identity.taxCode", null, mergedTaxCode, sourceDocIds, importJobIds, extractionIds, MergeAction.ADD_VALUE, false, null));
            } else if (isSameValue(curTax, mergedTaxCode)) {
                evidence.add(evidence("identity.taxCode", curTax, mergedTaxCode, sourceDocIds, importJobIds, extractionIds, MergeAction.KEEP_EXISTING, false, "Same tax code confirmed"));
            } else {
                evidence.add(evidence("identity.taxCode", curTax, mergedTaxCode, sourceDocIds, importJobIds, extractionIds, MergeAction.CONFLICT, true, "Conflicting tax codes — manager review required"));
            }
        }

        // industries (list merge)
        List<String> curIndustries = current.getBusiness() != null ? current.getBusiness().getIndustries() : null;
        if (!mergedIndustries.isEmpty()) {
            List<String> newOnly = newListItems(mergedIndustries, curIndustries);
            if (!newOnly.isEmpty()) {
                proposedBusiness.put("industries", mergeFullList(curIndustries, mergedIndustries));
                evidence.add(listEvidence("business.industries", mergedIndustries, sourceDocIds, importJobIds, extractionIds));
            } else {
                evidence.add(evidence("business.industries", curIndustries, mergedIndustries, sourceDocIds, importJobIds, extractionIds, MergeAction.KEEP_EXISTING, false, "Industries unchanged"));
            }
        }

        // markets (list merge)
        List<String> curMarkets = current.getBusiness() != null ? current.getBusiness().getMarkets() : null;
        if (!mergedMarkets.isEmpty()) {
            List<String> newOnly = newListItems(mergedMarkets, curMarkets);
            if (!newOnly.isEmpty()) {
                proposedBusiness.put("markets", mergeFullList(curMarkets, mergedMarkets));
                evidence.add(listEvidence("business.markets", mergedMarkets, sourceDocIds, importJobIds, extractionIds));
            }
        }

        // website
        String curWebsite = current.getContact() != null ? current.getContact().getWebsite() : null;
        if (!mergedWebsite.isEmpty()) {
            String newWeb = mergedWebsite.get(0);
            if (isUnknown(curWebsite)) {
                proposedContact.put("website", newWeb);
                evidence.add(evidence("contact.website", null, newWeb, sourceDocIds, importJobIds, extractionIds, MergeAction.ADD_VALUE, false, null));
            } else if (normalizeWebsite(curWebsite).equals(normalizeWebsite(newWeb))) {
                evidence.add(evidence("contact.website", curWebsite, newWeb, sourceDocIds, importJobIds, extractionIds, MergeAction.KEEP_EXISTING, false, null));
            }
        }

        // insights lists
        mergeInsightList("insights.strengths", current.getInsights() != null ? current.getInsights().getStrengths() : null,
                mergedStrengths, sourceDocIds, importJobIds, extractionIds, evidence, proposedInsights, "strengths");
        mergeInsightList("insights.weaknesses", current.getInsights() != null ? current.getInsights().getWeaknesses() : null,
                mergedWeaknesses, sourceDocIds, importJobIds, extractionIds, evidence, proposedInsights, "weaknesses");
        mergeInsightList("insights.opportunities", current.getInsights() != null ? current.getInsights().getOpportunities() : null,
                mergedOpportunities, sourceDocIds, importJobIds, extractionIds, evidence, proposedInsights, "opportunities");
        mergeInsightList("insights.threats", current.getInsights() != null ? current.getInsights().getThreats() : null,
                mergedThreats, sourceDocIds, importJobIds, extractionIds, evidence, proposedInsights, "threats");
    }

    // ─────────────────────────────────────────────
    // HELPER METHODS
    // ─────────────────────────────────────────────

    private void mergeInsightList(String fieldPath, List<String> current, List<String> merged,
                                  List<String> sourceDocIds, List<String> importJobIds, List<String> extractionIds,
                                  List<FieldEvidence> evidence, Map<String, Object> proposed, String key) {
        if (!merged.isEmpty()) {
            List<String> newOnly = newListItems(merged, current);
            if (!newOnly.isEmpty()) {
                proposed.put(key, mergeFullList(current, merged));
                evidence.add(listEvidence(fieldPath, merged, sourceDocIds, importJobIds, extractionIds));
            }
        }
    }

    private void mergeStringList(List<String> target, List<String> source) {
        if (source == null) return;
        for (String val : source) {
            if (isUnknown(val)) continue;
            boolean alreadyPresent = target.stream().anyMatch(t -> isSameValue(t, val));
            if (!alreadyPresent) {
                target.add(val);
            }
        }
    }

    private void mergeProducts(List<CompanyCandidate.Product> target, Object rawProducts) {
        if (!(rawProducts instanceof Iterable<?> products)) return;
        for (Object item : products) {
            String name = null;
            String category = null;
            String description = null;

            if (item instanceof ExtractedCompanyData.Product product) {
                name = product.getName();
                category = product.getCategory();
                description = product.getDescription();
            } else if (item instanceof Map<?, ?> map) {
                name = stringValue(map.get("name"));
                category = stringValue(map.get("category"));
                description = stringValue(map.get("description"));
            } else if (item != null) {
                name = String.valueOf(item);
            }

            if (isUnknown(name)) continue;
            String normalizedName = name.trim().toLowerCase(Locale.ROOT);
            boolean alreadyPresent = target.stream()
                    .map(CompanyCandidate.Product::getName)
                    .filter(Objects::nonNull)
                    .map(existing -> existing.trim().toLowerCase(Locale.ROOT))
                    .anyMatch(normalizedName::equals);
            if (!alreadyPresent) {
                target.add(CompanyCandidate.Product.builder()
                        .name(name)
                        .category(category)
                        .description(description)
                        .build());
            }
        }
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private <T> T toModel(Object value, Class<T> type) {
        if (value == null) return null;
        if (type.isInstance(value)) return type.cast(value);
        if (value instanceof Map<?, ?>) {
            try {
                return new com.fasterxml.jackson.databind.ObjectMapper().convertValue(value, type);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }

    private FinancialInfo mergeFinancialInfo(FinancialInfo current, FinancialInfo incoming) {
        if (incoming == null) return current;
        if (current == null) return incoming;

        return FinancialInfo.builder()
                .revenue(current.getRevenue() != null ? current.getRevenue() : incoming.getRevenue())
                .revenueCurrency(bestDisplayValue(current.getRevenueCurrency(), incoming.getRevenueCurrency()))
                .revenueGrowth(current.getRevenueGrowth() != null ? current.getRevenueGrowth() : incoming.getRevenueGrowth())
                .debtRatio(current.getDebtRatio() != null ? current.getDebtRatio() : incoming.getDebtRatio())
                .profitMargin(current.getProfitMargin() != null ? current.getProfitMargin() : incoming.getProfitMargin())
                .fundingStage(bestDisplayValue(current.getFundingStage(), incoming.getFundingStage()))
                .profitability(bestDisplayValue(current.getProfitability(), incoming.getProfitability()))
                .build();
    }

    private MarketInfo mergeMarketInfo(MarketInfo current, MarketInfo incoming) {
        if (incoming == null) return current;
        if (current == null) return incoming;

        List<String> mainMarkets = mergeFullList(current.getMainMarkets(), incoming.getMainMarkets());

        return MarketInfo.builder()
                .marketShare(current.getMarketShare() != null ? current.getMarketShare() : incoming.getMarketShare())
                .brandRank(current.getBrandRank() != null ? current.getBrandRank() : incoming.getBrandRank())
                .clientCount(current.getClientCount() != null ? current.getClientCount() : incoming.getClientCount())
                .mainMarkets(mainMarkets.isEmpty() ? null : mainMarkets)
                .build();
    }

    private InnovationInfo mergeInnovationInfo(InnovationInfo current, InnovationInfo incoming) {
        if (incoming == null) return current;
        if (current == null) return incoming;

        List<String> techStack = mergeFullList(current.getTechStack(), incoming.getTechStack());
        List<String> capabilities = mergeFullList(current.getTechnologyCapabilities(), incoming.getTechnologyCapabilities());

        return InnovationInfo.builder()
                .patents(current.getPatents() != null ? current.getPatents() : incoming.getPatents())
                .rdInvestmentPercent(current.getRdInvestmentPercent() != null ? current.getRdInvestmentPercent() : incoming.getRdInvestmentPercent())
                .techStack(techStack.isEmpty() ? null : techStack)
                .techMaturityLevel(current.getTechMaturityLevel() != null ? current.getTechMaturityLevel() : incoming.getTechMaturityLevel())
                .productInnovationRate(current.getProductInnovationRate() != null ? current.getProductInnovationRate() : incoming.getProductInnovationRate())
                .technologyCapabilities(capabilities.isEmpty() ? null : capabilities)
                .build();
    }

    private RiskInfo mergeRiskInfo(RiskInfo current, RiskInfo incoming) {
        if (incoming == null) return current;
        if (current == null) return incoming;

        return RiskInfo.builder()
                .legalRisk(bestDisplayValue(current.getLegalRisk(), incoming.getLegalRisk()))
                .financialRisk(bestDisplayValue(current.getFinancialRisk(), incoming.getFinancialRisk()))
                .reputationRisk(bestDisplayValue(current.getReputationRisk(), incoming.getReputationRisk()))
                .securityRisk(bestDisplayValue(current.getSecurityRisk(), incoming.getSecurityRisk()))
                .conflictOfInterestRisk(bestDisplayValue(current.getConflictOfInterestRisk(), incoming.getConflictOfInterestRisk()))
                .supplyInterruptionRisk(bestDisplayValue(current.getSupplyInterruptionRisk(), incoming.getSupplyInterruptionRisk()))
                .dependencyRisk(bestDisplayValue(current.getDependencyRisk(), incoming.getDependencyRisk()))
                .overallRiskLevel(bestDisplayValue(current.getOverallRiskLevel(), incoming.getOverallRiskLevel()))
                .build();
    }

    private ComplianceInfo mergeComplianceInfo(ComplianceInfo current, ComplianceInfo incoming) {
        if (incoming == null) return current;
        if (current == null) return incoming;

        List<String> qualityCertifications = mergeFullList(current.getQualityCertifications(), incoming.getQualityCertifications());
        List<String> securityCertifications = mergeFullList(current.getSecurityCertifications(), incoming.getSecurityCertifications());

        return ComplianceInfo.builder()
                .status(bestDisplayValue(current.getStatus(), incoming.getStatus()))
                .qualityCertifications(qualityCertifications.isEmpty() ? null : qualityCertifications)
                .securityCertifications(securityCertifications.isEmpty() ? null : securityCertifications)
                .antiCorruptionPolicy(bestDisplayValue(current.getAntiCorruptionPolicy(), incoming.getAntiCorruptionPolicy()))
                .laborCompliance(bestDisplayValue(current.getLaborCompliance(), incoming.getLaborCompliance()))
                .environmentalPolicy(bestDisplayValue(current.getEnvironmentalPolicy(), incoming.getEnvironmentalPolicy()))
                .build();
    }

    private List<String> newListItems(List<String> newVals, List<String> existing) {
        if (newVals == null) return Collections.emptyList();
        if (existing == null || existing.isEmpty()) return newVals;
        return newVals.stream()
                .filter(v -> existing.stream().noneMatch(e -> isSameValue(e, v)))
                .collect(Collectors.toList());
    }

    private List<String> mergeFullList(List<String> existing, List<String> incoming) {
        List<String> result = new ArrayList<>();
        if (existing != null) result.addAll(existing);
        mergeStringList(result, incoming);
        return result;
    }

    private FieldEvidence conflictEvidence(String path, Object existing, Object proposed,
                                           String docId, String jobId, String exId, String note) {
        return FieldEvidence.builder()
                .fieldPath(path)
                .existingValue(existing)
                .proposedValue(proposed)
                .mergeAction(MergeAction.CONFLICT)
                .conflict(true)
                .sourceDocumentIds(docId != null ? List.of(docId) : List.of())
                .importJobIds(jobId != null ? List.of(jobId) : List.of())
                .extractionIds(exId != null ? List.of(exId) : List.of())
                .note(note)
                .build();
    }

    private FieldEvidence evidence(String path, Object existing, Object proposed,
                                   List<String> docIds, List<String> jobIds, List<String> exIds,
                                   MergeAction action, boolean conflict, String note) {
        return FieldEvidence.builder()
                .fieldPath(path)
                .existingValue(existing)
                .proposedValue(proposed)
                .mergeAction(action)
                .conflict(conflict)
                .sourceDocumentIds(docIds)
                .importJobIds(jobIds)
                .extractionIds(exIds)
                .note(note)
                .build();
    }

    private FieldEvidence listEvidence(String path, List<String> merged,
                                       List<String> docIds, List<String> jobIds, List<String> exIds) {
        return FieldEvidence.builder()
                .fieldPath(path)
                .proposedValue(merged)
                .mergeAction(MergeAction.MERGE_LIST)
                .conflict(false)
                .sourceDocumentIds(docIds)
                .importJobIds(jobIds)
                .extractionIds(exIds)
                .build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sectionToMap(Object section) {
        if (section == null) return Collections.emptyMap();
        try {
            // Use simple reflection-free approach via toString structure — use Jackson if available
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
            return mapper.convertValue(section, Map.class);
        } catch (Exception e) {
            log.warn("Could not convert section to map", e);
            return Collections.emptyMap();
        }
    }

    private static class MergedCandidateData {
        final CompanyCandidate.Identity identity;
        final CompanyCandidate.Business business;
        final CompanyCandidate.CompanySize companySize;
        final CompanyCandidate.Contact contact;
        final CompanyCandidate.Insights insights;
        final List<String> keyPeople;
        final FinancialInfo financial;
        final MarketInfo market;
        final InnovationInfo innovation;
        final RiskInfo risk;
        final ComplianceInfo compliance;

        MergedCandidateData(CompanyCandidate.Identity id, CompanyCandidate.Business b,
                            CompanyCandidate.CompanySize s, CompanyCandidate.Contact c, CompanyCandidate.Insights i,
                            List<String> people, FinancialInfo f, MarketInfo m, InnovationInfo n,
                            RiskInfo r, ComplianceInfo co) {
            identity = id; business = b; companySize = s; contact = c; insights = i;
            keyPeople = people; financial = f; market = m; innovation = n; risk = r; compliance = co;
        }
    }
}
