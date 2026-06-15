package com.apms.domain.dashboard.service;

import com.apms.common.enums.CandidateStatus;
import com.apms.domain.candidate.repository.mongo.CompanyCandidateRepository;
import com.apms.domain.dashboard.dto.DashboardSummaryDto;
import com.apms.domain.graph.dto.GraphCompanyDto;
import com.apms.domain.graph.service.GraphService;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.apms.domain.score.ScoreSnapshot;
import com.apms.domain.score.dto.ScoreSnapshotDto;
import com.apms.domain.score.repository.sql.ScoreSnapshotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class DashboardService {

    private final CompanyProfileRepository profileRepository;
    private final ProjectRepository projectRepository;
    private final CompanyCandidateRepository candidateRepository;
    private final ScoreSnapshotRepository scoreSnapshotRepository;
    private final GraphService graphService;
    private final Neo4jClient neo4jClient;

    @Transactional(readOnly = true)
    public DashboardSummaryDto getSummary() {
        return DashboardSummaryDto.builder()
                .totalCompanyProfiles(profileRepository.count())
                .totalProjects(projectRepository.count())
                .totalCandidates(candidateRepository.count())
                .approvedCandidates(candidateRepository.countByStatus(CandidateStatus.APPROVED))
                .pendingReviewCandidates(candidateRepository.countByStatus(CandidateStatus.PENDING_REVIEW))
                .partnerCount(countNeo4jRelationship("PARTNER_WITH"))
                .competitorCount(countNeo4jRelationship("COMPETITOR_OF"))
                .supplierCount(countNeo4jRelationship("SUPPLIER_OF"))
                .potentialPartnerCount(countNeo4jRelationship("POTENTIAL_PARTNER_OF"))
                .build();
    }

    private long countNeo4jRelationship(String relType) {
        String cypher = String.format("MATCH ()-[r:%s]->() RETURN count(r) as cnt", relType);
        return neo4jClient.query(cypher)
                .fetchAs(Long.class)
                .mappedBy((ts, record) -> record.get("cnt").asLong())
                .one()
                .orElse(0L);
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
    public List<ScoreSnapshotDto> getRecentScores() {
        return scoreSnapshotRepository.findTop10ByOrderByCreatedAtDesc().stream()
                .map(this::toScoreDto)
                .toList();
    }

    private ScoreSnapshotDto toScoreDto(ScoreSnapshot s) {
        return ScoreSnapshotDto.builder()
                .scoreSnapshotId(s.getScoreSnapshotId())
                .companyId(s.getCompanyId())
                .projectId(s.getProjectId())
                .candidateId(s.getCandidateId())
                .partnerFitScore(s.getPartnerFitScore())
                .competitionLevel(s.getCompetitionLevel())
                .riskLevel(s.getRiskLevel())
                .relationshipStrength(s.getRelationshipStrength())
                .totalScore(s.getTotalScore())
                .factorsJson(s.getFactorsJson())
                .ruleVersion(s.getRuleVersion())
                .generatedBy(s.getGeneratedBy())
                .createdAt(s.getCreatedAt())
                .build();
    }
}
