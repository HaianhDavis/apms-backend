package com.apms.domain.score.service;

import com.apms.common.enums.ExternalDataCategory;
import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.externaldata.ExternalDataItem;
import com.apms.domain.profile.CompanyProfileVersion;
import com.apms.domain.profile.repository.mongo.CompanyProfileVersionRepository;
import com.apms.domain.score.dto.draft.CompetitorCriterionContext;
import com.apms.domain.score.draft.EvidenceRecord;
import com.apms.domain.score.draft.RoleEvaluationDraft;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CompetitorCriterionEvidenceService {

    private final CompanyProfileVersionRepository profileVersionRepository;
    private final MongoTemplate mongoTemplate;
    private final ObjectMapper objectMapper;

    public CompetitorCriterionContext buildContext(RoleEvaluationDraft draft, String criterionKey, LocalDate periodStart, LocalDate periodEnd) {

        CompanyProfileVersion referenceVersion = getAndValidateVersion(
                draft.getReferenceProfileDocumentId(),
                draft.getReferenceProfileVersion(),
                draft.getReferenceCompanyId()
        );

        CompanyProfileVersion targetVersion = getAndValidateVersion(
                draft.getTargetProfileDocumentId(),
                draft.getTargetProfileVersion(),
                draft.getTargetCompanyId()
        );

        Map<String, Object> referenceFacts = extractCriterionSpecificFacts(referenceVersion.getSnapshot(), criterionKey);
        Map<String, Object> targetFacts = extractCriterionSpecificFacts(targetVersion.getSnapshot(), criterionKey);

        List<EvidenceRecord> allDraftEvidence = draft.getCriterionEvidence().getOrDefault(criterionKey, List.of());
        List<Map<String, Object>> draftEvidenceMaps = allDraftEvidence.stream()
                .map(e -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("evidenceId", e.getEvidenceId());
                    map.put("sourceType", e.getSourceType());
                    map.put("sourceFieldPath", e.getExtractedFieldPath());
                    map.put("note", e.getNote());
                    map.put("evidenceDate", e.getEvidenceDate());
                    map.put("eventType", e.getEventType());
                    map.put("referenceCompanyId", e.getReferenceCompanyId());
                    map.put("targetCompanyId", e.getTargetCompanyId());
                    map.put("directCompetitiveClaim", e.getDirectCompetitiveClaim());
                    map.put("source", e.getExternalUrl() != null ? e.getExternalUrl() : e.getRawDocumentId()); // Map generic source
                    if (e.getEventDetails() != null) {
                        map.putAll(e.getEventDetails());
                    }
                    return map;
                })
                .collect(Collectors.toList());

        List<ExternalDataItem> externalItems = fetchExternalSignals(draft.getTargetCompanyId(), criterionKey, periodStart, periodEnd);
        List<Map<String, Object>> externalSignalMaps = externalItems.stream()
                .map(item -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("id", item.getId());
                    map.put("title", item.getTitle());
                    map.put("summary", item.getSummary());
                    map.put("category", item.getCategory().name());
                    map.put("date", item.getPublishedAt());
                    map.put("source", item.getSource());
                    map.put("riskLevel", item.getRiskLevel());
                    map.put("opportunityLevel", item.getOpportunityLevel());
                    return map;
                })
                .collect(Collectors.toList());

        return CompetitorCriterionContext.builder()
                .criterionKey(criterionKey)
                .periodStart(periodStart)
                .periodEnd(periodEnd)
                .referenceFacts(referenceFacts)
                .targetFacts(targetFacts)
                .draftEvidence(draftEvidenceMaps)
                .externalSignals(externalSignalMaps)
                .build();
    }

    public CompanyProfileVersion getAndValidateVersion(String documentId, Integer versionNum, String expectedCompanyId) {
        if (documentId == null || versionNum == null) {
            throw new BusinessValidationException("RoleEvaluationDraft is missing pinned profile version references.");
        }
        CompanyProfileVersion version = profileVersionRepository.findByCompanyProfileIdAndVersion(documentId, versionNum)
                .orElseThrow(() -> new BusinessValidationException("Pinned CompanyProfileVersion not found: " + documentId + " v" + versionNum));

        if (!expectedCompanyId.equals(version.getCompanyId())) {
            throw new BusinessValidationException("Pinned CompanyProfileVersion belongs to unexpected company ID.");
        }
        return version;
    }

    private Map<String, Object> extractCriterionSpecificFacts(Map<String, Object> snapshot, String criterionKey) {
        Map<String, Object> facts = new HashMap<>();

        if (snapshot == null) return facts;

        Map<String, Object> business = snapshot.containsKey("business") ?
            objectMapper.convertValue(snapshot.get("business"), new TypeReference<Map<String, Object>>() {}) : new HashMap<>();

        Map<String, Object> operations = snapshot.containsKey("operations") ?
            objectMapper.convertValue(snapshot.get("operations"), new TypeReference<Map<String, Object>>() {}) : new HashMap<>();

        Map<String, Object> financials = snapshot.containsKey("financials") ?
            objectMapper.convertValue(snapshot.get("financials"), new TypeReference<Map<String, Object>>() {}) : new HashMap<>();

        Map<String, Object> market = snapshot.containsKey("market") ?
            objectMapper.convertValue(snapshot.get("market"), new TypeReference<Map<String, Object>>() {}) : new HashMap<>();

        Map<String, Object> innovation = snapshot.containsKey("innovation") ?
            objectMapper.convertValue(snapshot.get("innovation"), new TypeReference<Map<String, Object>>() {}) : new HashMap<>();

        Map<String, Object> companySize = snapshot.containsKey("companySize") ?
            objectMapper.convertValue(snapshot.get("companySize"), new TypeReference<Map<String, Object>>() {}) : new HashMap<>();

        Map<String, Object> compliance = snapshot.containsKey("compliance") ?
            objectMapper.convertValue(snapshot.get("compliance"), new TypeReference<Map<String, Object>>() {}) : new HashMap<>();

        switch (criterionKey) {
            case "marketPositionScore":
                if (business.containsKey("markets")) facts.put("markets", business.get("markets"));
                if (business.containsKey("industries")) facts.put("industries", business.get("industries"));
                if (business.containsKey("targetCustomers")) facts.put("targetCustomers", business.get("targetCustomers"));
                if (market.containsKey("marketShare")) facts.put("marketShare", market.get("marketShare"));
                if (market.containsKey("brandRank")) facts.put("brandRank", market.get("brandRank"));
                if (market.containsKey("clientCount")) facts.put("clientCount", market.get("clientCount"));
                if (market.containsKey("mainMarkets")) facts.put("mainMarkets", market.get("mainMarkets"));
                break;
            case "competitiveCapabilityScore":
                if (business.containsKey("products")) facts.put("products", business.get("products"));
                if (business.containsKey("services")) facts.put("services", business.get("services"));
                if (operations.containsKey("certifications")) facts.put("certifications", operations.get("certifications"));
                if (operations.containsKey("technologies")) facts.put("technologies", operations.get("technologies"));
                if (innovation.containsKey("technologyCapabilities")) facts.put("technologyCapabilities", innovation.get("technologyCapabilities"));
                if (innovation.containsKey("techStack")) facts.put("techStack", innovation.get("techStack"));
                if (innovation.containsKey("patents")) facts.put("patents", innovation.get("patents"));
                if (innovation.containsKey("rdInvestmentPercent")) facts.put("rdInvestmentPercent", innovation.get("rdInvestmentPercent"));
                if (innovation.containsKey("techMaturityLevel")) facts.put("techMaturityLevel", innovation.get("techMaturityLevel"));
                if (innovation.containsKey("productInnovationRate")) facts.put("productInnovationRate", innovation.get("productInnovationRate"));
                if (companySize.containsKey("employeeCount")) facts.put("employeeCount", companySize.get("employeeCount"));
                if (compliance.containsKey("qualityCertifications")) facts.put("qualityCertifications", compliance.get("qualityCertifications"));
                break;
            case "strategicIntentScore":
                facts.put("vision", snapshot.get("vision"));
                facts.put("mission", snapshot.get("mission"));
                facts.put("partnerships", business.get("partnerships"));
                break;
            case "growthMomentumScore":
                facts.put("employeeCount", operations.get("employeeCount"));
                facts.put("revenue", financials.get("revenue"));
                facts.put("fundingRounds", financials.get("fundingRounds"));
                break;
            case "competitiveThreatScore":
                facts.put("legalName", snapshot.get("legalName"));
                facts.put("markets", business.get("markets"));
                facts.put("keyClients", business.get("keyClients"));
                break;
        }

        return facts;
    }

    private List<ExternalDataItem> fetchExternalSignals(String targetCompanyId, String criterionKey, LocalDate periodStart, LocalDate periodEnd) {
        Criteria criteria = Criteria.where("relatedCompanyId").is(targetCompanyId);

        if (periodStart != null) {
            criteria.and("publishedAt").gte(periodStart.atStartOfDay());
        }
        if (periodEnd != null) {
            criteria.andOperator(Criteria.where("publishedAt").lte(periodEnd.plusDays(1).atStartOfDay()));
        }

        List<ExternalDataCategory> allowedCategories = new ArrayList<>();
        switch (criterionKey) {
            case "marketPositionScore":
            case "strategicIntentScore":
                allowedCategories.add(ExternalDataCategory.NEWS);
                allowedCategories.add(ExternalDataCategory.MARKET_SIGNAL);
                break;
            case "competitiveCapabilityScore":
                allowedCategories.add(ExternalDataCategory.NEWS);
                break;
            case "growthMomentumScore":
                allowedCategories.add(ExternalDataCategory.NEWS);
                allowedCategories.add(ExternalDataCategory.OPPORTUNITY);
                break;
            case "competitiveThreatScore":
                allowedCategories.add(ExternalDataCategory.RISK);
                allowedCategories.add(ExternalDataCategory.MARKET_SIGNAL);
                break;
        }

        if (!allowedCategories.isEmpty()) {
            criteria.and("category").in(allowedCategories);
        }

        Query query = new Query(criteria).with(Sort.by(Sort.Direction.DESC, "publishedAt"));
        return mongoTemplate.find(query, ExternalDataItem.class);
    }
}
