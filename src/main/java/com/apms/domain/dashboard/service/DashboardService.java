package com.apms.domain.dashboard.service;

import com.apms.common.enums.CandidateStatus;
import com.apms.common.enums.ExternalDataCategory;
import com.apms.domain.candidate.repository.mongo.CompanyCandidateRepository;
import com.apms.domain.dashboard.dto.*;
import com.apms.domain.externaldata.ExternalDataItem;
import com.apms.domain.externaldata.repository.mongo.ExternalDataRepository;
import com.apms.domain.graph.dto.GraphCompanyDto;
import com.apms.domain.graph.service.GraphService;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.closeness.CompanyRelationshipCloseness;
import com.apms.domain.profile.closeness.CompanyRelationshipClosenessRepository;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import com.apms.domain.profile.service.OwnerOrganizationService;
import com.apms.domain.project.repository.sql.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DashboardService {

    private final CompanyProfileRepository profileRepository;
    private final ProjectRepository projectRepository;
    private final CompanyCandidateRepository candidateRepository;
    private final GraphService graphService;
    private final Neo4jClient neo4jClient;
    private final OwnerOrganizationService ownerOrganizationService;
    private final CompanyRelationshipClosenessRepository closenessRepository;
    private final ExternalDataRepository externalDataRepository;
    private final com.apms.domain.project.repository.sql.ProjectTaskSubmissionRepository submissionRepository;

    @Transactional(readOnly = true)
    public DashboardSummaryDto getSummary(Long managerId) {
        String ownerCompanyId = ownerOrganizationService.getOwnerCompanyId();
        String ownerCompanyProfileId = ownerOrganizationService.getOwnerCompanyProfileId();

        List<String> targetBusinessCompanyIds = getTargetBusinessCompanyIds(ownerCompanyId);
        long totalRelatedCompanies = targetBusinessCompanyIds.stream().distinct().count();

        List<CompanyProfile> targetProfiles = targetBusinessCompanyIds.isEmpty() ? List.of() : profileRepository.findByCompanyIdIn(targetBusinessCompanyIds)
                .stream().filter(p -> "APPROVED".equals(p.getReviewStatus()) && !Boolean.TRUE.equals(p.getIsHidden()))
                .collect(Collectors.toList());
        
        Set<String> targetCompanyProfileIds = targetProfiles.stream().map(CompanyProfile::getId).collect(Collectors.toSet());

        RelationshipClosenessOverviewDto closenessOverview = buildClosenessOverview(ownerCompanyProfileId, targetCompanyProfileIds);
        
        Set<String> ecosystemBusinessIds = new HashSet<>(targetBusinessCompanyIds);
        ecosystemBusinessIds.add(ownerCompanyId);

        SignalOverviewDto riskOverview = buildSignalOverview(ExternalDataCategory.RISK, ecosystemBusinessIds, targetProfiles);
        SignalOverviewDto opportunityOverview = buildSignalOverview(ExternalDataCategory.OPPORTUNITY, ecosystemBusinessIds, targetProfiles);

        List<CompanyProfile> allExceptOwner = profileRepository.findAll().stream()
            .filter(p -> !ownerCompanyId.equals(p.getCompanyId()))
            .filter(p -> "APPROVED".equals(p.getReviewStatus()) && !Boolean.TRUE.equals(p.getIsHidden()))
            .filter(p -> {
                if (managerId == null) return true;
                boolean isCreator = p.getMetadata() != null && managerId.toString().equals(p.getMetadata().getCreatedBy());
                boolean isManager = managerId.equals(p.getResponsibleManagerId());
                return isCreator || isManager;
            })
            .collect(Collectors.toList());

        long partnerCount = 0;
        long competitorCount = 0;
        long supplierCount = 0;
        long customerCount = 0;
        long potentialPartnerCount = 0;

        if (managerId == null) {
            partnerCount = countNeo4jRelationship("PARTNER_WITH", ownerCompanyId);
            competitorCount = countNeo4jRelationship("COMPETITOR_OF", ownerCompanyId);
            supplierCount = countNeo4jRelationship("SUPPLIER_OF", ownerCompanyId);
            customerCount = countNeo4jRelationship("CUSTOMER_OF", ownerCompanyId);
            potentialPartnerCount = countNeo4jRelationship("POTENTIAL_PARTNER_OF", ownerCompanyId);
        } else {
            // For a specific manager, count relationships based on their companies' Neo4j links, or just use the filtered list's relationships if we map them.
            // But doing graph traversal for specific nodes is hard here without restructuring. 
            // We can approximate by querying neo4j for relationships to companies in allExceptOwner.
            Set<String> myCompanyIds = allExceptOwner.stream().map(CompanyProfile::getCompanyId).collect(Collectors.toSet());
            if (!myCompanyIds.isEmpty()) {
                partnerCount = countNeo4jRelationshipFilterByTargets("PARTNER_WITH", ownerCompanyId, myCompanyIds);
                competitorCount = countNeo4jRelationshipFilterByTargets("COMPETITOR_OF", ownerCompanyId, myCompanyIds);
                supplierCount = countNeo4jRelationshipFilterByTargets("SUPPLIER_OF", ownerCompanyId, myCompanyIds);
                customerCount = countNeo4jRelationshipFilterByTargets("CUSTOMER_OF", ownerCompanyId, myCompanyIds);
                potentialPartnerCount = countNeo4jRelationshipFilterByTargets("POTENTIAL_PARTNER_OF", ownerCompanyId, myCompanyIds);
            }
        }

        List<RelationshipCompositionDto> relationshipComposition = List.of(
            new RelationshipCompositionDto("PARTNER", partnerCount),
            new RelationshipCompositionDto("COMPETITOR", competitorCount),
            new RelationshipCompositionDto("SUPPLIER", supplierCount),
            new RelationshipCompositionDto("CUSTOMER", customerCount),
            new RelationshipCompositionDto("POTENTIAL_PARTNER", potentialPartnerCount)
        );

        List<RecentActivityDto> recentActivities = buildRecentActivities(ecosystemBusinessIds, targetProfiles);

        long verifiedCompanyCount = allExceptOwner.stream()
            .filter(p -> "APPROVED".equals(p.getReviewStatus()) || "VERIFIED".equals(p.getReviewStatus()))
            .count();

        long totalIndustries = allExceptOwner.stream()
            .flatMap(p -> p.getBusiness() != null && p.getBusiness().getIndustries() != null ? p.getBusiness().getIndustries().stream() : java.util.stream.Stream.empty())
            .filter(Objects::nonNull)
            .distinct()
            .count();

        return DashboardSummaryDto.builder()
                .totalCompanyProfiles(allExceptOwner.size())
                .verifiedCompanyCount(verifiedCompanyCount)
                .totalIndustries(totalIndustries)
                .totalProjects(projectRepository.count())
                .totalCandidates(candidateRepository.count())
                .approvedCandidates(candidateRepository.countByStatus(CandidateStatus.APPROVED))
                .pendingReviewCandidates(candidateRepository.countByStatus(CandidateStatus.PENDING_REVIEW))
                
                .partnerCount(partnerCount)
                .competitorCount(competitorCount)
                .supplierCount(supplierCount)
                .customerCount(customerCount)
                .potentialPartnerCount(potentialPartnerCount)
                
                .totalRelatedCompanies(totalRelatedCompanies)
                .relationshipClosenessOverview(closenessOverview)
                .relationshipComposition(relationshipComposition)
                .riskOverview(riskOverview)
                .opportunityOverview(opportunityOverview)
                .recentActivities(recentActivities)
                .build();
    }

    private long countNeo4jRelationship(String relType, String ownerCompanyId) {
        String cypher = String.format("MATCH (:Company {companyId: $ownerId})-[r:%s]->() RETURN count(r) as cnt", relType);
        return neo4jClient.query(cypher)
                .bindAll(Map.of("ownerId", ownerCompanyId))
                .fetchAs(Long.class)
                .mappedBy((ts, record) -> record.get("cnt").asLong())
                .one()
                .orElse(0L);
    }

    private long countNeo4jRelationshipFilterByTargets(String relType, String ownerCompanyId, Set<String> targetIds) {
        String cypher = String.format("MATCH (:Company {companyId: $ownerId})-[r:%s]->(t:Company) WHERE t.companyId IN $targetIds RETURN count(r) as cnt", relType);
        return neo4jClient.query(cypher)
                .bindAll(Map.of("ownerId", ownerCompanyId, "targetIds", targetIds))
                .fetchAs(Long.class)
                .mappedBy((ts, record) -> record.get("cnt").asLong())
                .one()
                .orElse(0L);
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

    private RelationshipClosenessOverviewDto buildClosenessOverview(String ownerMongoId, Set<String> distinctTargetMongoIds) {
        if (distinctTargetMongoIds.isEmpty()) {
            return RelationshipClosenessOverviewDto.builder()
                    .ratedRelationshipCount(0)
                    .unratedRelationshipCount(0)
                    .distribution(List.of())
                    .build();
        }

        List<CompanyRelationshipCloseness> closenessRecords = closenessRepository.findByOwnerCompanyProfileIdAndTargetCompanyProfileIdIn(ownerMongoId, distinctTargetMongoIds);
        
        long ratedCount = closenessRecords.stream().map(CompanyRelationshipCloseness::getTargetCompanyProfileId).distinct().count();
        long unratedCount = distinctTargetMongoIds.size() - ratedCount;

        Map<Integer, Long> starCounts = closenessRecords.stream()
                .collect(Collectors.groupingBy(c -> c.getStars(), Collectors.counting()));

        List<RelationshipClosenessDistributionDto> distribution = new ArrayList<>();
        distribution.add(createDist(5, "STRATEGIC", starCounts.getOrDefault(5, 0L)));
        distribution.add(createDist(4, "CLOSE", starCounts.getOrDefault(4, 0L)));
        distribution.add(createDist(3, "ESTABLISHED", starCounts.getOrDefault(3, 0L)));
        distribution.add(createDist(2, "WEAK", starCounts.getOrDefault(2, 0L)));
        distribution.add(createDist(1, "CONTACT_ONLY", starCounts.getOrDefault(1, 0L)));
        distribution.add(createDist(null, "UNRATED", unratedCount));

        return RelationshipClosenessOverviewDto.builder()
                .ratedRelationshipCount(ratedCount)
                .unratedRelationshipCount(unratedCount)
                .distribution(distribution)
                .build();
    }

    private RelationshipClosenessDistributionDto createDist(Integer stars, String label, long count) {
        return RelationshipClosenessDistributionDto.builder().stars(stars).label(label).count(count).build();
    }

    private SignalOverviewDto buildSignalOverview(ExternalDataCategory category, Set<String> ecosystemBusinessIds, List<CompanyProfile> profiles) {
        if (ecosystemBusinessIds.isEmpty()) {
            return SignalOverviewDto.builder().totalCount(0).recentSignals(List.of()).build();
        }
        
        long total = externalDataRepository.countByCategoryAndRelatedCompanyIdIn(category, ecosystemBusinessIds);
        List<ExternalDataItem> recent = externalDataRepository.findTop5ByCategoryAndRelatedCompanyIdInOrderByPublishedAtDesc(category, ecosystemBusinessIds);
        
        List<SignalEntryDto> entries = recent.stream().map(item -> {
            String profileId = resolveProfileId(item.getRelatedCompanyId(), profiles);
            return SignalEntryDto.builder()
                .id(item.getId())
                .title(item.getTitle())
                .summary(item.getSummary())
                .companyId(item.getRelatedCompanyId())
                .companyProfileId(profileId)
                .companyName(item.getRelatedCompanyName())
                .source(item.getSource())
                .occurredAt(item.getPublishedAt())
                .build();
        }).toList();

        return SignalOverviewDto.builder()
                .totalCount(total)
                .recentSignals(entries)
                .build();
    }

    private List<RecentActivityDto> buildRecentActivities(Set<String> ecosystemBusinessIds, List<CompanyProfile> profiles) {
        if (ecosystemBusinessIds.isEmpty()) {
            return List.of();
        }
        List<ExternalDataItem> recent = externalDataRepository.findTop5ByRelatedCompanyIdInOrderByPublishedAtDesc(ecosystemBusinessIds);
        return recent.stream().map(item -> {
            String profileId = resolveProfileId(item.getRelatedCompanyId(), profiles);
            return RecentActivityDto.builder()
                .id(item.getId())
                .type(item.getCategory() != null ? item.getCategory().name() : "UPDATE")
                .description(item.getTitle())
                .companyId(item.getRelatedCompanyId())
                .companyProfileId(profileId)
                .companyName(item.getRelatedCompanyName())
                .occurredAt(item.getPublishedAt())
                .build();
        }).toList();
    }

    private String resolveProfileId(String businessId, List<CompanyProfile> profiles) {
        if (businessId == null) return null;
        return profiles.stream()
            .filter(p -> businessId.equals(p.getCompanyId()))
            .map(CompanyProfile::getId)
            .findFirst()
            .orElse(null);
    }

    public List<GraphCompanyDto> getPartners() {
        return graphService.getCompaniesByRelationshipType("PARTNER_WITH");
    }

    public List<GraphCompanyDto> getCompetitors() {
        return graphService.getCompaniesByRelationshipType("COMPETITOR_OF");
    }

    public List<GraphCompanyDto> getSuppliers() {
        return graphService.getCompaniesByRelationshipType("SUPPLIER_OF");
    }

    public List<GraphCompanyDto> getPotentialPartners() {
        return graphService.getCompaniesByRelationshipType("POTENTIAL_PARTNER_OF");
    }

    @Transactional(readOnly = true)
    public List<ManagerReviewHistoryItemResponse> getManagerReviewHistory(Long managerId) {
        if (managerId == null) {
            return Collections.emptyList();
        }
        List<com.apms.domain.project.ProjectTaskSubmission> submissions = submissionRepository.findReviewHistoryByManagerId(managerId);
        return submissions.stream().map(s -> {
            com.apms.domain.project.ProjectTask task = s.getProjectTask();
            com.apms.domain.project.Project project = s.getProject();
            com.apms.domain.user.Account submitter = s.getSubmittedByAccount();
            com.apms.domain.user.Account reviewer = s.getReviewedByAccount();

            return ManagerReviewHistoryItemResponse.builder()
                    .submissionId(s.getId())
                    .projectId(project != null ? project.getId() : null)
                    .projectName(project != null ? project.getProjectName() : "Unknown Project")
                    .targetCompanyName(project != null ? project.getTargetCompanyName() : null)
                    .taskId(task != null ? task.getId() : null)
                    .taskTitle(task != null ? task.getTitle() : "Unknown Task")
                    .taskType(task != null ? task.getTaskType() : null)
                    .submissionType(s.getSubmissionType())
                    .submittedRevisionNumber(s.getSubmittedRevisionNumber())
                    .submittedByUserId(submitter != null ? submitter.getId() : null)
                    .submittedByName(submitter != null ? submitter.getEmail() : null)
                    .submittedAt(s.getSubmittedAt())
                    .status(s.getStatus())
                    .reviewedByUserId(reviewer != null ? reviewer.getId() : null)
                    .reviewedByName(reviewer != null ? reviewer.getEmail() : "Manager")
                    .reviewedAt(s.getReviewedAt())
                    .reviewComment(s.getReviewComment())
                    .build();
        }).collect(Collectors.toList());
    }
}
