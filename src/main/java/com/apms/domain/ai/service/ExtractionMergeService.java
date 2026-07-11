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

        // 5. Build and save DRAFT CompanyCandidate
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
                .lifecycle(CompanyCandidate.Lifecycle.builder().status(CandidateStatus.DRAFT).build())
                .extractionSource(CompanyCandidate.ExtractionSource.builder()
                        .extractionMethod("MULTI_DOCUMENT_MERGE").build())
                .metadata(CompanyCandidate.Metadata.builder()
                        .createdBy(String.valueOf(creatorId))
                        .createdAt(LocalDateTime.now())
                        .updatedAt(LocalDateTime.now())
                        .build())
                .revisionNumber(1)
                .build();

        candidate = candidateRepository.save(candidate);

        auditLogService.log(creatorId, AuditAction.EXTRACTION_MERGED_TO_CANDIDATE, "CompanyCandidate", candidate.getId(),
                "Generated candidate draft from " + extractionIds.size() + " selected extraction(s)");

        // 6. Build response with section maps for display
        return MergeCandidateResponse.builder()
                .candidateId(candidate.getId())
                .identity(sectionToMap(merged.identity))
                .business(sectionToMap(merged.business))
                .contact(sectionToMap(merged.contact))
                .insights(sectionToMap(merged.insights))
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
        if (ex.getFieldResults() != null && ex.getFieldResults().containsKey(fieldName)) {
            ExtractionFieldResult fr = ex.getFieldResults().get(fieldName);
            if (fr.getReviewStatus() == ExtractionReviewStatus.REJECTED) return null;
            if (fr.getReviewStatus() == ExtractionReviewStatus.EDITED) return fr.getReviewedValue();
            if (fr.getReviewStatus() == ExtractionReviewStatus.ACCEPTED) return fr.getValue();
            // If PENDING or NEEDS_REVIEW, technically it shouldn't be here since the extraction is REVIEWED,
            // but just in case, we'll return the value.
            return fr.getValue();
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

        String mergedWebsite = null;
        List<String> mergedEmails = new ArrayList<>();

        List<String> mergedStrengths = new ArrayList<>();
        List<String> mergedWeaknesses = new ArrayList<>();
        List<String> mergedOpportunities = new ArrayList<>();
        List<String> mergedThreats = new ArrayList<>();

        String mergedEmployeeTier = null;

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

            // employeeTier
            String exEmployeeTier = (String) getFieldValue(ex, "employeeTier", data.getEmployeeTier());
            if (!isUnknown(exEmployeeTier)) {
                mergedEmployeeTier = bestDisplayValue(mergedEmployeeTier, exEmployeeTier);
            }

            // insights
            mergeStringList(mergedStrengths, getListFieldValue(ex, "strengths", data.getStrengths()));
            mergeStringList(mergedWeaknesses, getListFieldValue(ex, "weaknesses", data.getWeaknesses()));
            mergeStringList(mergedOpportunities, getListFieldValue(ex, "opportunities", data.getOpportunities()));
            mergeStringList(mergedThreats, getListFieldValue(ex, "threats", data.getThreats()));
        }

        // Build evidence for merged lists
        if (!mergedIndustries.isEmpty()) evidence.add(listEvidence("business.industries", mergedIndustries, sourceDocIds, importJobIds, extractionIds));
        if (!mergedMarkets.isEmpty()) evidence.add(listEvidence("business.markets", mergedMarkets, sourceDocIds, importJobIds, extractionIds));
        if (!mergedStrengths.isEmpty()) evidence.add(listEvidence("insights.strengths", mergedStrengths, sourceDocIds, importJobIds, extractionIds));

        CompanyCandidate.Identity identity = CompanyCandidate.Identity.builder()
                .legalName(mergedLegalName)
                .tradeName(mergedTradeName)
                .taxCode(mergedTaxCode)
                .build();

        CompanyCandidate.Business business = CompanyCandidate.Business.builder()
                .industries(mergedIndustries.isEmpty() ? null : mergedIndustries)
                .businessModel(mergedBusinessModel)
                .markets(mergedMarkets.isEmpty() ? null : mergedMarkets)
                .targetCustomers(mergedTargetCustomers.isEmpty() ? null : mergedTargetCustomers)
                .build();

        CompanyCandidate.CompanySize companySize = CompanyCandidate.CompanySize.builder()
                .employeeTier(mergedEmployeeTier)
                .build();

        CompanyCandidate.Contact contact = CompanyCandidate.Contact.builder()
                .website(mergedWebsite)
                .emails(mergedEmails.isEmpty() ? null : mergedEmails)
                .build();

        CompanyCandidate.Insights insights = CompanyCandidate.Insights.builder()
                .strengths(mergedStrengths.isEmpty() ? null : mergedStrengths)
                .weaknesses(mergedWeaknesses.isEmpty() ? null : mergedWeaknesses)
                .opportunities(mergedOpportunities.isEmpty() ? null : mergedOpportunities)
                .threats(mergedThreats.isEmpty() ? null : mergedThreats)
                .build();

        return new MergedCandidateData(identity, business, companySize, contact, insights);
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

        MergedCandidateData(CompanyCandidate.Identity id, CompanyCandidate.Business b,
                CompanyCandidate.CompanySize s, CompanyCandidate.Contact c, CompanyCandidate.Insights i) {
            identity = id; business = b; companySize = s; contact = c; insights = i;
        }
    }
}
