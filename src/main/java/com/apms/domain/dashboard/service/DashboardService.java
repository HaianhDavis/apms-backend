package com.apms.domain.dashboard.service;

import com.apms.common.enums.CandidateStatus;
import com.apms.domain.audit.repository.sql.AuditLogRepository;
import com.apms.domain.candidate.repository.mongo.CompanyCandidateRepository;
import com.apms.domain.dashboard.dto.DashboardSummaryDto;
import com.apms.domain.graph.dto.GraphCompanyDto;
import com.apms.domain.graph.service.GraphService;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.apms.domain.score.ScoreSnapshot;
import com.apms.domain.score.dto.ScoreSnapshotDto;
import com.apms.domain.score.repository.sql.ScoreSnapshotRepository;
import com.apms.domain.user.repository.sql.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.time.LocalDate;

@Service
@RequiredArgsConstructor
public class DashboardService {

    private final CompanyProfileRepository profileRepository;
    private final ProjectRepository projectRepository;
    private final CompanyCandidateRepository candidateRepository;
    private final ScoreSnapshotRepository scoreSnapshotRepository;
    private final AuditLogRepository auditLogRepository;
    private final AccountRepository accountRepository;
    private final GraphService graphService;
    private final Neo4jClient neo4jClient;

    @Transactional(readOnly = true)
    public DashboardSummaryDto getSummary() {
        return DashboardSummaryDto.builder()
                .totalCompanyProfiles(countProfilesSafe())
                .totalProjects(countProjectsSafe())
                .totalCandidates(countCandidatesSafe())
                .approvedCandidates(countCandidatesByStatusSafe(CandidateStatus.APPROVED))
                .pendingReviewCandidates(countCandidatesByStatusSafe(CandidateStatus.PENDING_REVIEW))
                .partnerCount(countNeo4jRelationship("PARTNER_WITH"))
                .competitorCount(countNeo4jRelationship("COMPETITOR_OF"))
                .supplierCount(countNeo4jRelationship("SUPPLIER_OF"))
                .potentialPartnerCount(countNeo4jRelationship("POTENTIAL_PARTNER_OF"))
                .securityAlerts(countSecurityAlertsSafe())
                .newUsers(countNewUsersSafe())
                .activitiesToday(countActivitiesTodaySafe())
                .activitiesTrend(calculateActivitiesTrendSafe())
                .systemHealth(calculateSystemHealthSafe())
                .build();
    }

    private long countProfilesSafe() {
        try {
            return profileRepository.count();
        } catch (Exception e) {
            return 0L;
        }
    }

    private long countProjectsSafe() {
        try {
            return projectRepository.count();
        } catch (Exception e) {
            return 0L;
        }
    }

    private long countCandidatesSafe() {
        try {
            return candidateRepository.count();
        } catch (Exception e) {
            return 0L;
        }
    }

    private long countCandidatesByStatusSafe(CandidateStatus status) {
        try {
            return candidateRepository.countByStatus(status);
        } catch (Exception e) {
            return 0L;
        }
    }

    private long countNeo4jRelationship(String relType) {
        try {
            String cypher = String.format("MATCH ()-[r:%s]->() RETURN count(r) as cnt", relType);
            return neo4jClient.query(cypher)
                    .fetchAs(Long.class)
                    .mappedBy((ts, record) -> record.get("cnt").asLong())
                    .one()
                    .orElse(0L);
        } catch (Exception e) {
            return 0L;
        }
    }

    private long countSecurityAlertsSafe() {
        try {
            long unverifiedProfiles = profileRepository.findAll().stream()
                    .filter(p -> p.getReviewStatus() == null || !"VERIFIED".equalsIgnoreCase(p.getReviewStatus()))
                    .count();
            long pendingCandidates = candidateRepository.countByStatus(CandidateStatus.PENDING_REVIEW);
            return unverifiedProfiles + pendingCandidates;
        } catch (Exception e) {
            return 0L;
        }
    }

    private long countNewUsersSafe() {
        try {
            LocalDate cutoff = LocalDate.now().minusDays(7);
            return accountRepository.findAll().stream()
                    .filter(a -> a.getCreatedAt() != null && !a.getCreatedAt().toLocalDate().isBefore(cutoff))
                    .count();
        } catch (Exception e) {
            return 0L;
        }
    }

    private long countActivitiesTodaySafe() {
        try {
            LocalDate today = LocalDate.now();
            return auditLogRepository.findAll().stream()
                    .filter(a -> a.getTimestamp() != null && a.getTimestamp().toLocalDate().equals(today))
                    .count();
        } catch (Exception e) {
            return 0L;
        }
    }

    private String calculateActivitiesTrendSafe() {
        try {
            long today = countActivitiesTodaySafe();
            long yesterday = auditLogRepository.findAll().stream()
                    .filter(a -> a.getTimestamp() != null && a.getTimestamp().toLocalDate().equals(LocalDate.now().minusDays(1)))
                    .count();
            if (yesterday == 0) {
                return today > 0 ? "↑ New activity" : "—";
            }
            long delta = Math.round(((today - yesterday) * 100.0) / yesterday);
            return (delta >= 0 ? "↑ " : "↓ ") + Math.abs(delta) + "%";
        } catch (Exception e) {
            return "—";
        }
    }

    private double calculateSystemHealthSafe() {
        try {
            long total = accountRepository.count();
            long active = accountRepository.findAll().stream().filter(a -> Boolean.TRUE.equals(a.getIsActive())).count();
            if (total == 0) {
                return 100.0;
            }
            return Math.round((active * 1000.0) / total) / 10.0;
        } catch (Exception e) {
            return 99.0;
        }
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
        return scoreSnapshotRepository.findTop10ByEvaluatedRoleIsNullOrderByCreatedAtDesc().stream()
                .map(this::toScoreDto)
                .toList();
    }

    private ScoreSnapshotDto toScoreDto(ScoreSnapshot s) {
        String companyName = resolveCompanyName(s.getCompanyId());
        return ScoreSnapshotDto.builder()
                .scoreSnapshotId(s.getScoreSnapshotId())
                .companyId(s.getCompanyId())
                .companyName(companyName)
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

    private String resolveCompanyName(String companyId) {
        if (companyId == null || companyId.isBlank()) return null;
        try {
            return profileRepository.findByCompanyId(companyId)
                    .or(() -> profileRepository.findById(companyId))
                    .map(p -> {
                        if (p.getIdentity() != null) {
                            if (p.getIdentity().getTradeName() != null && !p.getIdentity().getTradeName().isBlank()) {
                                return p.getIdentity().getTradeName();
                            }
                            if (p.getIdentity().getLegalName() != null && !p.getIdentity().getLegalName().isBlank()) {
                                return p.getIdentity().getLegalName();
                            }
                        }
                        return p.getCompanyId();
                    })
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }
}
