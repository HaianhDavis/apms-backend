package com.apms.domain.score.service;

import com.apms.common.enums.RelationshipType;
import com.apms.common.event.CandidateApprovedEvent;
import com.apms.common.exception.ResourceNotFoundException;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import com.apms.domain.score.ScoreRule;
import com.apms.domain.score.ScoreSnapshot;
import com.apms.domain.score.dto.ScoreRuleDto;
import com.apms.domain.score.dto.ScoreSnapshotDto;
import com.apms.domain.score.repository.sql.ScoreRuleRepository;
import com.apms.domain.score.repository.sql.ScoreSnapshotRepository;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScoreService {

    private final ScoreSnapshotRepository scoreSnapshotRepository;
    private final ScoreRuleRepository scoreRuleRepository;
    private final CompanyProfileRepository profileRepository;
    private final ProjectRepository projectRepository;
    private final ObjectMapper objectMapper;

    private static final Map<String, Integer> DEFAULT_WEIGHTS = Map.of(
            "PARTNER_FIT", 30,
            "COMPETITION", 20,
            "RISK", 20,
            "RELATIONSHIP", 30
    );

    // ─────────────────────────────────────────────
    // EVENT LISTENER (Score Generation)
    // ─────────────────────────────────────────────

    @EventListener
    @Order(3)
    @Transactional
    public void handleCandidateApprovedEvent(CandidateApprovedEvent event) {
        log.info("ScoreService received CandidateApprovedEvent for candidateId: {}", event.getCandidateId());

        if (event.getProjectId() == null || event.getProjectId().isBlank()) {
            log.error("CandidateApprovedEvent has null/blank projectId for candidateId: {}. Skipping score generation.", event.getCandidateId());
            return;
        }

        Long projectId;
        try {
            projectId = Long.valueOf(event.getProjectId());
        } catch (NumberFormatException e) {
            log.error("CandidateApprovedEvent has non-numeric projectId='{}' for candidateId: {}. Skipping score generation.", event.getProjectId(), event.getCandidateId());
            return;
        }

        CompanyProfile profile = profileRepository.findByCandidateId(event.getCandidateId())
                .orElse(null);

        if (profile == null) {
            log.error("CompanyProfile not found for candidateId: {}. Cannot generate ScoreSnapshot.", event.getCandidateId());
            return;
        }

        Double confidence = event.getConfidenceScore() != null ? event.getConfidenceScore() : 0.5;
        RelationshipType relType = event.getFinalRelationshipType();

        List<ScoreRule> activeRules = scoreRuleRepository.findByIsActiveTrue();
        Map<String, Integer> weights = resolveWeights(activeRules);
        Map<String, Map<String, Object>> conditions = resolveConditions(activeRules);

        int partnerFit = computePartnerFitScore(confidence, relType, conditions.getOrDefault("PARTNER_FIT", Map.of()));
        int competition = computeCompetitionLevel(confidence, relType, conditions.getOrDefault("COMPETITION", Map.of()));
        int risk = computeRiskLevel(confidence, relType, conditions.getOrDefault("RISK", Map.of()));
        int strength = computeRelationshipStrength(confidence, relType, conditions.getOrDefault("RELATIONSHIP", Map.of()));

        int total = computeWeightedTotal(partnerFit, competition, risk, strength, weights);

        ScoreSnapshot snapshot = ScoreSnapshot.builder()
                .companyId(profile.getCompanyId())
                .project(projectRepository.getReferenceById(projectId))
                .candidateId(event.getCandidateId())
                .partnerFitScore(partnerFit)
                .competitionLevel(competition)
                .riskLevel(risk)
                .relationshipStrength(strength)
                .totalScore(total)
                .factorsJson(String.format("{\"confidence\":%.2f,\"relationshipType\":\"%s\",\"ruleWeights\":%s}",
                        confidence, relType != null ? relType.name() : "UNKNOWN", weights))
                .ruleVersion("v1.0")
                .generatedByAccount(null) // SYSTEM generated
                .build();

        snapshot = scoreSnapshotRepository.save(snapshot);
        log.info("Generated ScoreSnapshot ID {} for companyId: {} (total={}, weights={})", snapshot.getScoreSnapshotId(), profile.getCompanyId(), total, weights);
    }

    // ─────────────────────────────────────────────
    // SCORING ENGINE (reads from ScoreRule table)
    // ─────────────────────────────────────────────

    Map<String, Integer> resolveWeights(List<ScoreRule> activeRules) {
        Map<String, Integer> weights = new HashMap<>();
        if (activeRules.isEmpty()) {
            return new HashMap<>(DEFAULT_WEIGHTS);
        }
        for (ScoreRule rule : activeRules) {
            String category = rule.getRuleCategory();
            if (category != null && !category.isBlank()) {
                weights.put(category.toUpperCase(), rule.getWeight());
            }
        }
        if (weights.isEmpty()) {
            return new HashMap<>(DEFAULT_WEIGHTS);
        }
        return weights;
    }

    Map<String, Map<String, Object>> resolveConditions(List<ScoreRule> activeRules) {
        Map<String, Map<String, Object>> conditions = new HashMap<>();
        for (ScoreRule rule : activeRules) {
            String category = rule.getRuleCategory();
            if (category == null || category.isBlank()) continue;
            if (rule.getRuleConditionJson() != null && !rule.getRuleConditionJson().isBlank()) {
                try {
                    Map<String, Object> parsed = objectMapper.readValue(
                            rule.getRuleConditionJson(), new TypeReference<>() {});
                    conditions.put(category.toUpperCase(), parsed);
                } catch (Exception e) {
                    log.warn("Failed to parse ruleConditionJson for rule '{}' (category={}): {}",
                            rule.getRuleName(), rule.getRuleCategory(), e.getMessage());
                }
            }
        }
        return conditions;
    }

    int computeWeightedTotal(int partnerFit, int competition, int risk, int strength, Map<String, Integer> weights) {
        int wPartner = weights.getOrDefault("PARTNER_FIT", 30);
        int wCompetition = weights.getOrDefault("COMPETITION", 20);
        int wRisk = weights.getOrDefault("RISK", 20);
        int wRelationship = weights.getOrDefault("RELATIONSHIP", 30);
        int totalWeight = wPartner + wCompetition + wRisk + wRelationship;
        if (totalWeight == 0) totalWeight = 100;

        return Math.round(
                (partnerFit * wPartner
                + (100 - competition) * wCompetition
                + (100 - risk) * wRisk
                + strength * wRelationship) / (float) totalWeight);
    }

    int computePartnerFitScore(Double confidence, RelationshipType relType, Map<String, Object> conditions) {
        int base = (int) (confidence * 60);
        if (relType == RelationshipType.POTENTIAL_PARTNER_OF || relType == RelationshipType.PARTNER_WITH) {
            base += 25;
        } else if (relType == RelationshipType.CUSTOMER_OF) {
            base += 15;
        }
        Object minConfidence = conditions.get("minConfidence");
        if (minConfidence instanceof Number n && confidence < n.doubleValue()) {
            base = (int) (base * (confidence / n.doubleValue()));
        }
        return Math.min(100, Math.max(0, base));
    }

    int computeCompetitionLevel(Double confidence, RelationshipType relType, Map<String, Object> conditions) {
        int score;
        if (relType == RelationshipType.COMPETITOR_OF) {
            score = Math.min(100, (int) (confidence * 70) + 20);
        } else {
            score = Math.min(50, (int) ((1.0 - confidence) * 40));
        }
        Object maxScore = conditions.get("maxScore");
        if (maxScore instanceof Number n) {
            score = Math.min(score, n.intValue());
        }
        return Math.max(0, score);
    }

    int computeRiskLevel(Double confidence, RelationshipType relType, Map<String, Object> conditions) {
        int base = (int) ((1.0 - confidence) * 40);
        if (relType == RelationshipType.COMPETITOR_OF) {
            base += 20;
        }
        Object maxRisk = conditions.get("maxRisk");
        if (maxRisk instanceof Number n) {
            base = Math.min(base, n.intValue());
        }
        return Math.min(100, Math.max(0, base));
    }

    int computeRelationshipStrength(Double confidence, RelationshipType relType, Map<String, Object> conditions) {
        int base = (int) (confidence * 70);
        if (relType == RelationshipType.PARTNER_WITH) {
            base += 20;
        } else if (relType == RelationshipType.POTENTIAL_PARTNER_OF) {
            base += 10;
        } else if (relType == RelationshipType.COMPETITOR_OF) {
            base -= 10;
        }
        Object minPartnerTenure = conditions.get("minPartnerTenureYears");
        if (minPartnerTenure instanceof Number n && n.intValue() > 0) {
            base = Math.min(base, 60);
        }
        return Math.min(100, Math.max(0, base));
    }

    // ─────────────────────────────────────────────
    // READ SNAPSHOTS
    // ─────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ScoreSnapshotDto> getCompanyScores(String companyId) {
        return scoreSnapshotRepository.findByCompanyIdAndEvaluatedRoleIsNullOrderByCreatedAtDesc(companyId).stream()
                .map(this::toDto)
                .toList();
    }

    // ─────────────────────────────────────────────
    // MANAGE RULES
    // ─────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ScoreRuleDto> getAllRules() {
        return scoreRuleRepository.findAll().stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional
    public ScoreRuleDto createRule(ScoreRuleDto request) {
        ScoreRule rule = ScoreRule.builder()
                .ruleName(request.getRuleName())
                .ruleCategory(request.getRuleCategory())
                .weight(request.getWeight())
                .ruleConditionJson(request.getRuleConditionJson())
                .isActive(request.getIsActive() != null ? request.getIsActive() : true)
                .build();

        return toDto(scoreRuleRepository.save(rule));
    }

    @Transactional
    public ScoreRuleDto updateRule(Long id, ScoreRuleDto request) {
        ScoreRule rule = scoreRuleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ScoreRule not found: " + id));

        rule.setRuleName(request.getRuleName());
        rule.setRuleCategory(request.getRuleCategory());
        rule.setWeight(request.getWeight());
        rule.setRuleConditionJson(request.getRuleConditionJson());
        if (request.getIsActive() != null) {
            rule.setIsActive(request.getIsActive());
        }

        return toDto(scoreRuleRepository.save(rule));
    }

    @Transactional
    public void deleteRule(Long id) {
        if (!scoreRuleRepository.existsById(id)) {
            throw new ResourceNotFoundException("ScoreRule not found: " + id);
        }
        scoreRuleRepository.deleteById(id);
    }

    // ─────────────────────────────────────────────
    // MAPPERS
    // ─────────────────────────────────────────────

    private ScoreSnapshotDto toDto(ScoreSnapshot s) {
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

    private ScoreRuleDto toDto(ScoreRule r) {
        return ScoreRuleDto.builder()
                .id(r.getId())
                .ruleName(r.getRuleName())
                .ruleCategory(r.getRuleCategory())
                .weight(r.getWeight())
                .ruleConditionJson(r.getRuleConditionJson())
                .isActive(r.getIsActive())
                .createdAt(r.getCreatedAt())
                .updatedAt(r.getUpdatedAt())
                .build();
    }
}
