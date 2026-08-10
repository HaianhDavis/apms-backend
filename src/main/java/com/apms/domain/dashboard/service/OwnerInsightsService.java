package com.apms.domain.dashboard.service;

import com.apms.common.enums.ExternalDataCategory;
import com.apms.domain.contract.entity.PartnerContract;
import com.apms.domain.contract.enums.ContractLifecycleStatus;
import com.apms.domain.contract.enums.ContractReviewStatus;
import com.apms.domain.contract.repository.sql.PartnerContractRepository;
import com.apms.domain.dashboard.dto.InsightSourceRef;
import com.apms.domain.dashboard.dto.InsightType;
import com.apms.domain.dashboard.dto.OwnerInsightDto;
import com.apms.domain.externaldata.ExternalDataItem;
import com.apms.domain.externaldata.repository.mongo.ExternalDataRepository;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.closeness.CompanyRelationshipCloseness;
import com.apms.domain.profile.closeness.CompanyRelationshipClosenessRepository;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import com.apms.domain.profile.service.OwnerOrganizationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OwnerInsightsService {

    private final OwnerOrganizationService ownerOrganizationService;
    private final Neo4jClient neo4jClient;
    private final CompanyProfileRepository profileRepository;
    private final ExternalDataRepository externalDataRepository;
    private final CompanyRelationshipClosenessRepository closenessRepository;
    private final PartnerContractRepository partnerContractRepository;

    @Transactional(readOnly = true)
    public Page<OwnerInsightDto> getInsights(String typeStr, String companyProfileId, LocalDateTime fromDate, LocalDateTime toDate, Pageable pageable) {
        String ownerCompanyId = ownerOrganizationService.getOwnerCompanyId();
        String ownerCompanyProfileId = ownerOrganizationService.getOwnerCompanyProfileId();
        
        // 1. Resolve distinct connected target UUIDs
        List<String> targetBusinessCompanyIds = getTargetBusinessCompanyIds(ownerCompanyId);
        
        // 2. Map UUIDs to Mongo IDs
        List<CompanyProfile> targetProfiles = targetBusinessCompanyIds.isEmpty() ? List.of() : profileRepository.findByCompanyIdIn(targetBusinessCompanyIds);
        Map<String, CompanyProfile> targetProfileMap = targetProfiles.stream().collect(Collectors.toMap(CompanyProfile::getId, p -> p));
        Set<String> distinctTargetMongoIds = targetProfileMap.keySet();
        
        Set<String> ecosystemBusinessIds = new HashSet<>(targetBusinessCompanyIds);
        ecosystemBusinessIds.add(ownerCompanyId);

        List<OwnerInsightDto> allInsights = new ArrayList<>();
        
        // 3. Generate insights
        allInsights.addAll(generateExternalDataInsights(ecosystemBusinessIds, targetProfiles));
        allInsights.addAll(generateClosenessInsights(ownerCompanyProfileId, distinctTargetMongoIds, targetProfileMap));
        allInsights.addAll(generateContractInsights(ownerCompanyId, distinctTargetMongoIds, targetProfileMap));
        
        // 4. Apply Filters
        InsightType targetType = StringUtils.hasText(typeStr) ? InsightType.valueOf(typeStr) : null;
        
        List<OwnerInsightDto> filteredInsights = allInsights.stream()
                .filter(insight -> targetType == null || insight.getType() == targetType)
                .filter(insight -> !StringUtils.hasText(companyProfileId) || companyProfileId.equals(insight.getCompanyProfileId()))
                .filter(insight -> {
                    if (fromDate != null || toDate != null) {
                        if (insight.getGeneratedAt() == null) return false;
                        if (fromDate != null && insight.getGeneratedAt().isBefore(fromDate)) return false;
                        if (toDate != null && insight.getGeneratedAt().isAfter(toDate)) return false;
                    }
                    return true;
                })
                .collect(Collectors.toList());
                
        // 5. De-duplicate by ID
        Map<String, OwnerInsightDto> dedupedMap = new HashMap<>();
        for (OwnerInsightDto insight : filteredInsights) {
            dedupedMap.putIfAbsent(insight.getId(), insight);
        }
        
        List<OwnerInsightDto> uniqueInsights = new ArrayList<>(dedupedMap.values());
        
        // 6. Sort deterministically: generatedAt DESC NULLS LAST, then id ASC
        uniqueInsights.sort((a, b) -> {
            if (a.getGeneratedAt() == null && b.getGeneratedAt() == null) {
                return a.getId().compareTo(b.getId());
            }
            if (a.getGeneratedAt() == null) return 1;
            if (b.getGeneratedAt() == null) return -1;
            
            int timeCompare = b.getGeneratedAt().compareTo(a.getGeneratedAt()); // DESC
            if (timeCompare != 0) return timeCompare;
            
            return a.getId().compareTo(b.getId());
        });
        
        // 7. Paginate
        int start = (int) pageable.getOffset();
        int end = Math.min((start + pageable.getPageSize()), uniqueInsights.size());
        
        List<OwnerInsightDto> pagedInsights = start <= end && start < uniqueInsights.size() 
                ? uniqueInsights.subList(start, end) 
                : List.of();
                
        return new PageImpl<>(pagedInsights, pageable, uniqueInsights.size());
    }

    private List<String> getTargetBusinessCompanyIds(String ownerCompanyId) {
        String cypher = "MATCH (:Company {companyId: $ownerId})-[r:PARTNER_WITH|POTENTIAL_PARTNER_OF|COMPETITOR_OF|CUSTOMER_OF|SUPPLIER_OF]-(t:Company) RETURN DISTINCT t.companyId as targetId";
        return neo4jClient.query(cypher)
                .bindAll(Map.of("ownerId", ownerCompanyId))
                .fetchAs(String.class)
                .mappedBy((ts, record) -> record.get("targetId").asString())
                .all()
                .stream().toList();
    }
    
    private List<OwnerInsightDto> generateExternalDataInsights(Set<String> ecosystemBusinessIds, List<CompanyProfile> targetProfiles) {
        if (ecosystemBusinessIds.isEmpty()) return List.of();
        
        // Fetch all RISK and OPPORTUNITY in the ecosystem
        List<ExternalDataItem> items = new ArrayList<>();
        items.addAll(externalDataRepository.findTop5ByCategoryAndRelatedCompanyIdInOrderByPublishedAtDesc(ExternalDataCategory.RISK, ecosystemBusinessIds));
        items.addAll(externalDataRepository.findTop5ByCategoryAndRelatedCompanyIdInOrderByPublishedAtDesc(ExternalDataCategory.OPPORTUNITY, ecosystemBusinessIds));
        
        return items.stream().map(item -> {
            String profileId = resolveProfileId(item.getRelatedCompanyId(), targetProfiles);
            return OwnerInsightDto.builder()
                .id("EXTERNAL_" + item.getCategory().name() + ":" + item.getId())
                .type(item.getCategory() == ExternalDataCategory.RISK ? InsightType.RISK : InsightType.OPPORTUNITY)
                .title(item.getTitle())
                .summary(item.getSummary())
                .companyProfileId(profileId)
                .companyName(item.getRelatedCompanyName())
                .sources(List.of(InsightSourceRef.builder().type("EXTERNAL_DATA").id(item.getId()).title(item.getSource()).build()))
                .generatedAt(item.getPublishedAt())
                .actionable(false)
                .generationMethod("EXTERNAL_SIGNAL_DERIVED")
                .build();
        }).collect(Collectors.toList());
    }

    private String resolveProfileId(String businessId, List<CompanyProfile> profiles) {
        if (businessId == null) return null;
        return profiles.stream()
            .filter(p -> businessId.equals(p.getCompanyId()))
            .map(CompanyProfile::getId)
            .findFirst()
            .orElse(null);
    }

    private List<OwnerInsightDto> generateClosenessInsights(String ownerMongoId, Set<String> distinctTargetMongoIds, Map<String, CompanyProfile> targetProfileMap) {
        if (distinctTargetMongoIds.isEmpty()) return List.of();

        List<CompanyRelationshipCloseness> closenessRecords = closenessRepository.findByOwnerCompanyProfileIdAndTargetCompanyProfileIdIn(ownerMongoId, distinctTargetMongoIds);
        Map<String, CompanyRelationshipCloseness> closenessMap = closenessRecords.stream()
                .collect(Collectors.toMap(CompanyRelationshipCloseness::getTargetCompanyProfileId, c -> c));

        List<OwnerInsightDto> insights = new ArrayList<>();

        for (String targetId : distinctTargetMongoIds) {
            CompanyProfile profile = targetProfileMap.get(targetId);
            String companyName = profile != null && profile.getIdentity() != null ? profile.getIdentity().getLegalName() : "Unknown";

            CompanyRelationshipCloseness closeness = closenessMap.get(targetId);
            
            if (closeness == null) {
                // UNRATED
                insights.add(OwnerInsightDto.builder()
                        .id("CLOSENESS_UNRATED:" + ownerMongoId + ":" + targetId)
                        .type(InsightType.RECOMMENDATION)
                        .title("Unrated Relationship")
                        .summary("The relationship with " + companyName + " has not been rated yet. Consider reviewing and rating this relationship.")
                        .companyProfileId(targetId)
                        .companyName(companyName)
                        .sources(List.of(InsightSourceRef.builder().type("COMPANY_PROFILE").id(targetId).title(companyName).build()))
                        .generatedAt(null)
                        .actionable(true)
                        .generationMethod("RELATIONSHIP_DERIVED")
                        .build());
            } else if (closeness.getStars() != null && closeness.getStars() <= 2) {
                // LOW (1 or 2 stars)
                String label = closeness.getStars() == 1 ? "CONTACT_ONLY" : "WEAK";
                insights.add(OwnerInsightDto.builder()
                        .id("CLOSENESS_LOW:" + ownerMongoId + ":" + targetId)
                        .type(InsightType.RECOMMENDATION)
                        .title("Low Relationship Closeness")
                        .summary("The relationship with " + companyName + " is currently rated as " + label + ". Consider reviewing relationship status.")
                        .companyProfileId(targetId)
                        .companyName(companyName)
                        .sources(List.of(InsightSourceRef.builder().type("CLOSENESS_RECORD").id(String.valueOf(closeness.getId())).title("Closeness Record").build()))
                        .generatedAt(closeness.getUpdatedAt() != null ? closeness.getUpdatedAt() : closeness.getRatedAt())
                        .actionable(true)
                        .generationMethod("RELATIONSHIP_DERIVED")
                        .build());
            }
        }

        return insights;
    }

    private List<OwnerInsightDto> generateContractInsights(String ownerCompanyId, Set<String> distinctTargetMongoIds, Map<String, CompanyProfile> targetProfileMap) {
        if (distinctTargetMongoIds.isEmpty()) return List.of();

        List<PartnerContract> expiredContracts = partnerContractRepository.findByReferenceCompanyIdAndPartnerCompanyIdInAndReviewStatusAndLifecycleStatus(
                ownerCompanyId,
                distinctTargetMongoIds,
                ContractReviewStatus.APPROVED,
                ContractLifecycleStatus.EXPIRED
        );

        return expiredContracts.stream().map(c -> {
            String partnerId = c.getPartnerCompanyId();
            CompanyProfile profile = targetProfileMap.get(partnerId);
            String companyName = profile != null && profile.getIdentity() != null ? profile.getIdentity().getLegalName() : "Unknown";

            LocalDateTime expiry = null;
            if (c.getExpiryDate() != null) {
                expiry = c.getExpiryDate().atStartOfDay();
            }

            return OwnerInsightDto.builder()
                    .id("CONTRACT_EXPIRED:" + c.getId())
                    .type(InsightType.RECOMMENDATION)
                    .title("Expired Official Contract")
                    .summary("An official contract with " + companyName + " has expired. Consider reviewing for renewal.")
                    .companyProfileId(partnerId)
                    .companyName(companyName)
                    .sources(List.of(InsightSourceRef.builder().type("PARTNER_CONTRACT").id(String.valueOf(c.getId())).title("Expired Contract").build()))
                    .generatedAt(expiry)
                    .actionable(true)
                    .generationMethod("RULE_BASED")
                    .build();
        }).collect(Collectors.toList());
    }
}
